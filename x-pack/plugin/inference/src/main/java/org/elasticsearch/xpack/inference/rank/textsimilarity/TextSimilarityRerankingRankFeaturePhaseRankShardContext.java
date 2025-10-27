/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.rank.textsimilarity;

import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.mapper.vectors.SparseVectorFieldMapper;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.search.DefaultSearchContext;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.rank.RankBuilder;
import org.elasticsearch.search.rank.RankShardResult;
import org.elasticsearch.search.rank.feature.RankFeatureDoc;
import org.elasticsearch.search.rank.feature.RankFeatureShardResult;
import org.elasticsearch.search.rank.rerank.RerankingRankFeaturePhaseRankShardContext;
import org.elasticsearch.xpack.core.common.chunks.MemoryIndexChunkScorer;
import org.elasticsearch.xpack.core.inference.chunking.Chunker;
import org.elasticsearch.xpack.core.inference.chunking.ChunkerBuilder;
import org.elasticsearch.xpack.inference.mapper.SemanticTextField;
import org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
                    // TODO semantic text field - so I can tell here that it's a semantic text field but this may be too late to construct a query that could be executed and go through the rewrite phase - can we manually do this?
                    assert searchContext instanceof DefaultSearchContext;
                    IndexService indexService = ((DefaultSearchContext) searchContext).indexService();
                    MapperService mapperService = indexService.mapperService();
                    MappingLookup mappingLookup = mapperService.mappingLookup();
                    Mapper mapper = mappingLookup.getMapper(field);
                    boolean isSemanticTextField = (mapper instanceof SemanticTextFieldMapper);
                    if (isSemanticTextField) {
                        SemanticTextFieldMapper semanticTextFieldMapper = (SemanticTextFieldMapper) mapper;
                        SemanticTextFieldMapper.SemanticTextFieldType fieldType = semanticTextFieldMapper.fieldType();
                        ChunkingSettings semanticTextChunkingSettings = fieldType.getChunkingSettings(); // We could compare here to verify that passed in chunking settings are equivalent
                        FieldMapper embeddingsField = fieldType.getEmbeddingsField();
                        if (embeddingsField instanceof SparseVectorFieldMapper) {
                            SparseVectorFieldMapper sparseVectorFieldMapper = (SparseVectorFieldMapper) embeddingsField;
                        } else if (embeddingsField instanceof DenseVectorFieldMapper) {
                            throw new UnsupportedOperationException("not yet");
                        } else {
                            throw new UnsupportedOperationException("unsupported field [" + embeddingsField.fieldType().name() + "]");
                        }

                        // Actually identify chunks and rescore them
                        int size = chunkScorerConfig.size() != null ? chunkScorerConfig.size() : DEFAULT_SIZE;

                        List<String> chunks = new ArrayList<>(); // TODO populate

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
                            throw new IllegalStateException("Could not pull semantic_text chunks from field", e);
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

}
