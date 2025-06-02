/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.rank.feature;

import org.apache.lucene.search.Explanation;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.rank.RankDoc;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.TransportVersions.RETRIEVER_RERANK_MULTIPLE_INPUT_MAX_SCORE;

/**
 * A {@link RankDoc} that contains field data to be used later by the reranker on the coordinator node.
 */
public class RankFeatureDoc extends RankDoc {

    public static final String NAME = "rank_feature_doc";

    // TODO don't restrict to String data
    public List<String> featureData;
    public List<Integer> docIndices;

    public RankFeatureDoc(int doc, float score, int shardIndex) {
        super(doc, score, shardIndex);
    }

    public RankFeatureDoc(StreamInput in) throws IOException {
        super(in);
        if (in.getTransportVersion().before(RETRIEVER_RERANK_MULTIPLE_INPUT_MAX_SCORE)) {
            String featureDataString = in.readOptionalString();
            featureData = featureDataString == null ? List.of() : List.of(featureDataString);
            docIndices = null;
        } else {
            featureData = in.readOptionalStringCollectionAsList();
            docIndices = in.readOptionalCollectionAsList(StreamInput::readVInt);
        }
    }

    @Override
    public Explanation explain(Explanation[] sources, String[] queryNames) {
        throw new UnsupportedOperationException("explain is not supported for {" + getClass() + "}");
    }

    public void featureData(List<String> featureData, List<Integer> docIndices) {
        this.featureData = featureData;
        this.docIndices = docIndices;
    }

    public void featureData(List<String> featureData) {
        this.featureData = featureData;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        if (out.getTransportVersion().before(RETRIEVER_RERANK_MULTIPLE_INPUT_MAX_SCORE)) {
            out.writeOptionalString(featureData.isEmpty() ? null : featureData.get(0));
        } else {
            out.writeOptionalStringCollection(featureData);
            out.writeOptionalCollection(docIndices, StreamOutput::writeVInt);
        }
    }

    @Override
    protected boolean doEquals(RankDoc rd) {
        RankFeatureDoc other = (RankFeatureDoc) rd;
        return Objects.equals(this.featureData, other.featureData) && Objects.equals(this.docIndices, other.docIndices);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(featureData, docIndices);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    protected void doToXContent(XContentBuilder builder, Params params) throws IOException {
        builder.array("featureData", featureData.toArray(new String[0]));
        builder.array("docIndices", docIndices.toArray(new String[0]));
    }
}
