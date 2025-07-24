/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;

import java.io.IOException;

/**
 * {@link EvalOperator.ExpressionEvaluator} to run a Lucene {@link Query} during
 * the compute engine's normal execution, yielding matches into a {@link BytesRefVector}.
 */
public class LuceneQueryBytesRefEvaluator extends LuceneQueryEvaluator<BytesRefVector.Builder> implements EvalOperator.ExpressionEvaluator {

    LuceneQueryBytesRefEvaluator(BlockFactory blockFactory, ShardConfig[] shards) {
        super(blockFactory, shards);
    }

    @Override
    public Block eval(Page page) {
        return executeQuery(page);
    }

    @Override
    protected ScoreMode scoreMode() {
        return ScoreMode.COMPLETE_NO_SCORES;
    }

    @Override
    protected Vector createNoMatchVector(BlockFactory blockFactory, int size) {
        return blockFactory.newConstantBytesRefVector(new BytesRef(), size);
    }

    @Override
    protected BytesRefVector.Builder createVectorBuilder(BlockFactory blockFactory, int size) {
        return blockFactory.newBytesRefVectorBuilder(size);
    }

    @Override
    protected void appendNoMatch(BytesRefVector.Builder builder) {
        builder.appendBytesRef(new BytesRef());
    }

    @Override
    protected void appendMatch(BytesRefVector.Builder builder, Scorable scorer) throws IOException {
        builder.appendBytesRef(new BytesRef()); // TODO: what do we add here?
    }

    public record Factory(ShardConfig[] shardConfigs) implements EvalOperator.ExpressionEvaluator.Factory {
        @Override
        public EvalOperator.ExpressionEvaluator get(DriverContext context) {
            return new LuceneQueryBytesRefEvaluator(context.blockFactory(), shardConfigs);
        }
    }
}
