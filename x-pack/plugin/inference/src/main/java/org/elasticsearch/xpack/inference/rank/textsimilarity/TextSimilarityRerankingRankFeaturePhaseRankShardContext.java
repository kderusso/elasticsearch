/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.rank.textsimilarity;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnByteVectorQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.mapper.vectors.SparseVectorFieldMapper;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.search.DefaultSearchContext;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.rank.RankShardResult;
import org.elasticsearch.search.rank.feature.RankFeatureDoc;
import org.elasticsearch.search.rank.feature.RankFeatureShardResult;
import org.elasticsearch.search.rank.rerank.RerankingRankFeaturePhaseRankShardContext;
import org.elasticsearch.search.vectors.DenseVectorQuery;
import org.elasticsearch.search.vectors.SparseVectorQueryWrapper;
import org.elasticsearch.search.vectors.VectorData;
import org.elasticsearch.xpack.core.common.chunks.MemoryIndexChunkScorer;
import org.elasticsearch.xpack.core.inference.chunking.Chunker;
import org.elasticsearch.xpack.core.inference.chunking.ChunkerBuilder;
import org.elasticsearch.xpack.inference.mapper.OffsetSourceField;
import org.elasticsearch.xpack.inference.mapper.OffsetSourceFieldMapper;
import org.elasticsearch.xpack.inference.mapper.SemanticTextField;
import org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.inference.rank.textsimilarity.ChunkScorerConfig.DEFAULT_SIZE;

public class TextSimilarityRerankingRankFeaturePhaseRankShardContext extends RerankingRankFeaturePhaseRankShardContext {

    private final ChunkScorerConfig chunkScorerConfig;
    private final ChunkingSettings chunkingSettings;
    private final Chunker chunker;

    public TextSimilarityRerankingRankFeaturePhaseRankShardContext(String field, @Nullable ChunkScorerConfig chunkScorerConfig) {
        super(field);
        this.chunkScorerConfig = chunkScorerConfig;
        chunkingSettings = chunkScorerConfig != null ? chunkScorerConfig.chunkingSettings() : null;
        chunker = chunkingSettings != null ? ChunkerBuilder.fromChunkingStrategy(chunkingSettings.getChunkingStrategy()) : null;
    }

    @Override
    public RankShardResult doBuildRankFeatureShardResult(SearchHits hits, int shardId, SearchContext searchContext) {
        RankFeatureDoc[] rankFeatureDocs = new RankFeatureDoc[hits.getHits().length];
        for (int i = 0; i < hits.getHits().length; i++) {
            rankFeatureDocs[i] = new RankFeatureDoc(hits.getHits()[i].docId(), hits.getHits()[i].getScore(), shardId);
            SearchHit hit = hits.getHits()[i];
            DocumentField docField = hit.field(field);
            if (docField != null) {
                if (chunkScorerConfig != null) {
                    // TODO semantic text field - so I can tell here that it's a semantic text field but this may be too late to
                    //  construct a query that could be executed and go through the rewrite phase - can we manually do this?
                    assert searchContext instanceof DefaultSearchContext;
                    IndexService indexService = ((DefaultSearchContext) searchContext).indexService();
                    MapperService mapperService = indexService.mapperService();
                    MappingLookup mappingLookup = mapperService.mappingLookup();
                    Mapper mapper = mappingLookup.getMapper(field);
                    boolean isSemanticTextField = (mapper instanceof SemanticTextFieldMapper);
                    if (isSemanticTextField) {
                        SemanticTextFieldMapper semanticTextFieldMapper = (SemanticTextFieldMapper) mapper;
                        SemanticTextFieldMapper.SemanticTextFieldType fieldType = semanticTextFieldMapper.fieldType();
                        // We could compare here to verify that passed in chunking settings are equivalent
                        ChunkingSettings semanticTextChunkingSettings = fieldType.getChunkingSettings();
                        FieldMapper embeddingsField = fieldType.getEmbeddingsField();
                        if (embeddingsField instanceof SparseVectorFieldMapper) {
                            SparseVectorFieldMapper sparseVectorFieldMapper = (SparseVectorFieldMapper) embeddingsField;
                        } else if (embeddingsField instanceof DenseVectorFieldMapper) {
                            DenseVectorFieldMapper denseVectorFieldMapper = (DenseVectorFieldMapper) embeddingsField;
                        } else {
                            throw new UnsupportedOperationException("unsupported field [" + embeddingsField.fieldType().name() + "]");
                        }

                        // Actually identify chunks and rescore them
                        int size = chunkScorerConfig.size() != null ? chunkScorerConfig.size() : DEFAULT_SIZE;

                        final List<Query> queries = switch (fieldType.getModelSettings().taskType()) {
                            case SPARSE_EMBEDDING -> extractSparseVectorQueries(
                                (SparseVectorFieldMapper.SparseVectorFieldType) fieldType.getEmbeddingsField().fieldType(),
                                searchContext.query()
                            );
                            case TEXT_EMBEDDING -> extractDenseVectorQueries(
                                (DenseVectorFieldMapper.DenseVectorFieldType) fieldType.getEmbeddingsField().fieldType(),
                                searchContext.query()
                            );
                            default -> throw new IllegalStateException(
                                "Wrong task type for a semantic text field, got [" + fieldType.getModelSettings().taskType().name() + "]"
                            );
                        };
                        if (queries.isEmpty()) {
                            // nothing to highlight
                            return null;
                        }


                        IndexSearcher searcher = searchContext.searcher();
                        int docId = hits.getHits()[i].docId();
                        Map<String, Object> source = hit.getSourceAsMap();
                        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
                        // Find the leaf that contains this doc
                        LeafReaderContext leafContext = null;
                        for (LeafReaderContext ctx : leaves) {
                            if (docId >= ctx.docBase && docId < ctx.docBase + ctx.reader().maxDoc()) {
                                leafContext = ctx;
                                break;
                            }
                        }
                        List<String> bestChunks = List.of();
                        if (leafContext != null) {
                            LeafReader leafReader = leafContext.reader();
                            int localDocId = docId - leafContext.docBase;

                            List<OffsetAndScore> chunkOffsetsAndScores;
                            try {
                                chunkOffsetsAndScores = extractOffsetAndScores(
                                    searchContext.getSearchExecutionContext(),
                                    leafReader,
                                    fieldType,
                                    localDocId,
                                    queries
                                );
                            } catch (IOException e) {
                                throw new ElasticsearchException("failed to extract offset and scores", e);
                            }

                            // Sort by score descending
                            chunkOffsetsAndScores.sort(Comparator.comparingDouble(OffsetAndScore::score).reversed());

                            // Take top N and extract text content
                            int numChunks = Math.min(chunkOffsetsAndScores.size(), size);
                            List<String> chunks = new ArrayList<>();

                            if (fieldType.useLegacyFormat()) {
                                // Handle legacy format - extract from nested sources
                                List<Map<?, ?>> nestedSources = XContentMapValues.extractNestedSources(
                                    fieldType.getChunksField().fullPath(),
                                    hit.getSourceAsMap()
                                );
                                for (int j = 0; j < numChunks; j++) {
                                    OffsetAndScore offsetAndScore = chunkOffsetsAndScores.get(j);
                                    if (nestedSources.size() <= offsetAndScore.index()) {
                                        throw new IllegalStateException("Invalid chunk index: " + offsetAndScore.index());
                                    }
                                    String content = (String) nestedSources.get(offsetAndScore.index())
                                        .get(SemanticTextField.CHUNKED_TEXT_FIELD);
                                    chunks.add(content);
                                }
                            } else {
                                // Handle new format - extract using offsets
                                Map<String, String> fieldToContent = new HashMap<>();
                                for (int j = 0; j < numChunks; j++) {
                                    OffsetAndScore offsetAndScore = chunkOffsetsAndScores.get(j);

//                                    String content = fieldToContent.computeIfAbsent(offsetAndScore.offset().field(), key -> {
                                    String key = offsetAndScore.offset().field();
                                    String content = null;
                                        DocumentField docFieldContent = hit.field(key);
                                        if (docFieldContent != null && docFieldContent.getValues().size() > 0) {
                                            // TODO: This only ever populates the first chunk, not the best chunk(s).
                                            String fullContent = docFieldContent.getValue().toString();
                                            String chunk = fullContent.substring(offsetAndScore.offset().start(), offsetAndScore.offset().end());
                                            content = chunk;
                                        }
                                        // Fallback to source
                                        if (source != null && source.get(key) != null) {
                                            String fullContent = source.get(key).toString();
                                            String chunk = fullContent.substring(offsetAndScore.offset().start(), offsetAndScore.offset().end());
                                            content = chunk;
                                        }
//                                    });


                                    if (content != null) {
                                        chunks.add(content);
                                    }
                                }
                            }
                            bestChunks = chunks;
                        }
                        rankFeatureDocs[i].featureData(bestChunks);
                    } else {
                        int size = chunkScorerConfig.size() != null ? chunkScorerConfig.size() : DEFAULT_SIZE;
                        List<Chunker.ChunkOffset> chunkOffsets = chunker.chunk(docField.getValue().toString(), chunkingSettings);
                        List<String> chunks = chunkOffsets.stream()
                            .map(offset -> { return docField.getValue().toString().substring(offset.start(), offset.end()); })
                            .toList();

                        List<String> bestChunks;
                        try {
                            MemoryIndexChunkScorer scorer = new MemoryIndexChunkScorer();
                            List<MemoryIndexChunkScorer.ScoredChunk> scoredChunks = scorer.scoreChunks(
                                chunks,
                                chunkScorerConfig.inferenceText(),
                                size
                            );
                            bestChunks = scoredChunks.stream().map(MemoryIndexChunkScorer.ScoredChunk::content).limit(size).toList();
                        } catch (IOException e) {
                            throw new IllegalStateException("Could not generate chunks for input to reranker", e);
                        }
                        rankFeatureDocs[i].featureData(bestChunks);
                    }





                } else {
                    rankFeatureDocs[i].featureData(List.of(docField.getValue().toString()));
                }
            }
        }
        return new RankFeatureShardResult(rankFeatureDocs);
    }

    private record OffsetAndScore(int index, OffsetSourceFieldMapper.OffsetSource offset, float score) {}

    private List<OffsetAndScore> extractOffsetAndScores(
        SearchExecutionContext context,
        LeafReader reader,
        SemanticTextFieldMapper.SemanticTextFieldType fieldType,
        int docId,
        List<Query> leafQueries
    ) throws IOException {
        var bitSet = context.bitsetFilter(fieldType.getChunksField().parentTypeFilter()).getBitSet(reader.getContext());
        int previousParent = docId > 0 ? bitSet.prevSetBit(docId - 1) : -1;

        BooleanQuery.Builder bq = new BooleanQuery.Builder().add(fieldType.getChunksField().nestedTypeFilter(), BooleanClause.Occur.FILTER);
        leafQueries.stream().forEach(q -> bq.add(q, BooleanClause.Occur.SHOULD));
        Weight weight = new IndexSearcher(reader).createWeight(bq.build(), ScoreMode.COMPLETE, 1);
        Scorer scorer = weight.scorer(reader.getContext());
        if (scorer == null) {
            return List.of();
        }
        if (previousParent != -1) {
            if (scorer.iterator().advance(previousParent) == DocIdSetIterator.NO_MORE_DOCS) {
                return List.of();
            }
        } else if (scorer.iterator().nextDoc() == DocIdSetIterator.NO_MORE_DOCS) {
            return List.of();
        }

        OffsetSourceField.OffsetSourceLoader offsetReader = null;
        if (fieldType.useLegacyFormat() == false) {
            var terms = reader.terms(fieldType.getOffsetsField().fullPath());
            if (terms == null) {
                // The field is empty
                return List.of();
            }
            offsetReader = OffsetSourceField.loader(terms);
        }

        List<OffsetAndScore> results = new ArrayList<>();
        int index = 0;
        while (scorer.docID() < docId) {
            if (offsetReader != null) {
                var offset = offsetReader.advanceTo(scorer.docID());
                if (offset == null) {
                    throw new IllegalStateException(
                        "Cannot highlight field [" + fieldType.name() + "], missing offsets for doc [" + docId + "]"
                    );
                }
                results.add(new OffsetAndScore(index++, offset, scorer.score()));
            } else {
                results.add(new OffsetAndScore(index++, null, scorer.score()));
            }
            if (scorer.iterator().nextDoc() == DocIdSetIterator.NO_MORE_DOCS) {
                break;
            }
        }
        return results;
    }

    private List<Query> extractDenseVectorQueries(DenseVectorFieldMapper.DenseVectorFieldType fieldType, Query querySection) {
        // TODO: Handle knn section when semantic text field can be used.
        List<Query> queries = new ArrayList<>();
        querySection.visit(new QueryVisitor() {
            @Override
            public boolean acceptField(String field) {
                return fieldType.name().equals(field);
            }

            @Override
            public void consumeTerms(Query query, Term... terms) {
                super.consumeTerms(query, terms);
            }

            @Override
            public void visitLeaf(Query query) {
                if (query instanceof KnnFloatVectorQuery knnQuery) {
                    queries.add(fieldType.createExactKnnQuery(VectorData.fromFloats(knnQuery.getTargetCopy()), null));
                } else if (query instanceof KnnByteVectorQuery knnQuery) {
                    queries.add(fieldType.createExactKnnQuery(VectorData.fromBytes(knnQuery.getTargetCopy()), null));
                } else if (query instanceof MatchAllDocsQuery) {
                    queries.add(new MatchAllDocsQuery());
                } else if (query instanceof DenseVectorQuery.Floats floatsQuery) {
                    queries.add(fieldType.createExactKnnQuery(VectorData.fromFloats(floatsQuery.getQuery()), null));
                }
            }
        });
        return queries;
    }

    private List<Query> extractSparseVectorQueries(SparseVectorFieldMapper.SparseVectorFieldType fieldType, Query querySection) {
        List<Query> queries = new ArrayList<>();
        querySection.visit(new QueryVisitor() {
            @Override
            public boolean acceptField(String field) {
                return fieldType.name().equals(field);
            }

            @Override
            public void consumeTerms(Query query, Term... terms) {
                super.consumeTerms(query, terms);
            }

            @Override
            public QueryVisitor getSubVisitor(BooleanClause.Occur occur, Query parent) {
                if (parent instanceof SparseVectorQueryWrapper sparseVectorQuery) {
                    queries.add(sparseVectorQuery.getTermsQuery());
                }
                return this;
            }

            @Override
            public void visitLeaf(Query query) {
                if (query instanceof MatchAllDocsQuery) {
                    queries.add(new MatchAllDocsQuery());
                }
            }
        });
        return queries;
    }

}
