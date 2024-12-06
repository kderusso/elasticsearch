/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.ml.search;

import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.ResolvedIndices;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.InferenceFieldMetadata;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.query.InterceptedQueryBuilderWrapper;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.QueryRewriteInterceptor;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SparseVectorQueryRewriteInterceptor implements QueryRewriteInterceptor {

    private static final Logger logger = LogManager.getLogger(SparseVectorQueryRewriteInterceptor.class);

    private static final String NESTED_FIELD_PATH = ".inference.chunks";
    private static final String NESTED_EMBEDDINGS_FIELD = NESTED_FIELD_PATH + ".embeddings";

    public static final NodeFeature SEMANTIC_VECTOR_REWRITE_INTERCEPTION_SUPPORTED = new NodeFeature(
        "search.semantic_vector_rewrite_interception_supported"
    );

    public SparseVectorQueryRewriteInterceptor() {}

    @Override
    public QueryBuilder rewrite(QueryRewriteContext context, QueryBuilder queryBuilder) {
        if (queryBuilder instanceof SparseVectorQueryBuilder == false) {
            return queryBuilder;
        }

        SparseVectorQueryBuilder sparseVectorQueryBuilder = (SparseVectorQueryBuilder) queryBuilder;
        QueryBuilder rewritten = queryBuilder;
        ResolvedIndices resolvedIndices = context.getResolvedIndices();
        if (resolvedIndices != null) {
            Collection<IndexMetadata> indexMetadataCollection = resolvedIndices.getConcreteLocalIndicesMetadata().values();
            List<String> inferenceIndices = new ArrayList<>();
            List<String> nonInferenceIndices = new ArrayList<>();
            for (IndexMetadata indexMetadata : indexMetadataCollection) {
                String indexName = indexMetadata.getIndex().getName();
                InferenceFieldMetadata inferenceFieldMetadata = indexMetadata.getInferenceFields()
                    .get(sparseVectorQueryBuilder.getFieldName());
                if (inferenceFieldMetadata != null) {
                    inferenceIndices.add(indexName);
                } else {
                    nonInferenceIndices.add(indexName);
                }
            }

            if (inferenceIndices.isEmpty()) {
                logger.info("No semantic text fields, returning vanilla sparse vector query builder");
                return rewritten;
            } else if (nonInferenceIndices.isEmpty() == false) {
                throw new UnsupportedOperationException("Not implemented yet");
                // BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
                // for (String inferenceIndexName : inferenceIndices) {
                // // Add a separate clause for each semantic query, because they may be using different inference endpoints
                // boolQueryBuilder.should(createNestedSparseVectorQuery(inferenceIndexName, sparseVectorQueryBuilder));
                // }
                // boolQueryBuilder.should(createSparseVectorSubQuery(nonInferenceIndices, sparseVectorQueryBuilder));
                // rewritten = boolQueryBuilder;
            } else {
                logger.info("Only semantic text fields, rewriting to a nested query");
                rewritten = QueryBuilders.nestedQuery(
                    getNestedFieldPath(sparseVectorQueryBuilder.getFieldName()),
                    new InterceptedSparseVectorQueryWrapper(
                        getNestedEmbeddingsField(sparseVectorQueryBuilder.getFieldName()),
                        sparseVectorQueryBuilder.getQueryVectors(),
                        sparseVectorQueryBuilder.getInferenceId(),
                        sparseVectorQueryBuilder.getQuery(),
                        sparseVectorQueryBuilder.shouldPruneTokens(),
                        sparseVectorQueryBuilder.getTokenPruningConfig()
                    ),
                    ScoreMode.Max
                );
            }
        }

        return rewritten;
    }

    // private QueryBuilder createNestedSparseVectorQuery(String indexName, SparseVectorQueryBuilder sparseVectorQueryBuilder) {
    // BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
    // boolQueryBuilder.must(
    // new NestedQueryBuilder(
    // sparseVectorQueryBuilder.getFieldName(),
    // new InterceptedSparseVectorQueryWrapper(sparseVectorQueryBuilder),
    // ScoreMode.Max
    // )
    // );
    // boolQueryBuilder.filter(new TermQueryBuilder(IndexFieldMapper.NAME, indexName));
    // return boolQueryBuilder;
    // }

    // private QueryBuilder createSparseVectorSubQuery(List<String> indices, SparseVectorQueryBuilder sparseVectorQueryBuilder) {
    // BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
    // boolQueryBuilder.must(new InterceptedSparseVectorQueryWrapper(sparseVectorQueryBuilder));
    // boolQueryBuilder.filter(new TermsQueryBuilder(IndexFieldMapper.NAME, indices));
    // return boolQueryBuilder;
    // }

    @Override
    public String getName() {
        return SparseVectorQueryBuilder.NAME;
    }

    private static String getNestedFieldPath(String fieldName) {
        return fieldName + NESTED_FIELD_PATH;
    }

    private static String getNestedEmbeddingsField(String fieldName) {
        return fieldName + NESTED_EMBEDDINGS_FIELD;
    }

    static class InterceptedSparseVectorQueryWrapper extends InterceptedQueryBuilderWrapper<SparseVectorQueryBuilder> {
        // InterceptedSparseVectorQueryWrapper(SparseVectorQueryBuilder qb) {
        // super(
        // new SparseVectorQueryBuilder(
        // qb.getFieldName(),
        // qb.getQueryVectors(),
        // qb.getInferenceId(),
        // qb.getQuery(),
        // qb.shouldPruneTokens(),
        // qb.getTokenPruningConfig()
        // )
        // );
        // }

        InterceptedSparseVectorQueryWrapper(
            String fieldName,
            @Nullable List<WeightedToken> queryVectors,
            @Nullable String inferenceId,
            @Nullable String query,
            @Nullable Boolean shouldPruneTokens,
            @Nullable TokenPruningConfig tokenPruningConfig
        ) {
            super(new SparseVectorQueryBuilder(fieldName, queryVectors, inferenceId, query, shouldPruneTokens, tokenPruningConfig));
        }
    }
}
