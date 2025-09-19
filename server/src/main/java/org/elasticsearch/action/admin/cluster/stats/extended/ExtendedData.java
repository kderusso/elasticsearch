/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.cluster.stats.extended;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ExtendedData implements Writeable, ToXContent {

    private final Map<String, Map<String,Long>> retrievers;

    public ExtendedData() {
        this.retrievers = new HashMap<>();
    }

    public ExtendedData(Map<String, Map<String,Long>> retrievers) {
        this.retrievers = retrievers;
    }

    public ExtendedData(StreamInput in) throws IOException {
        this.retrievers = in.readMap(StreamInput::readString, i -> i.readMap(StreamInput::readString, StreamInput::readLong));
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeMap(retrievers, StreamOutput::writeString, (o, v) -> o.writeMap(v, StreamOutput::writeString, StreamOutput::writeLong));
    }

    public void merge(ExtendedData other) {
        other.retrievers.forEach((key, otherMap) -> {
            retrievers.merge(key, otherMap, (existingMap, newMap) -> {
                Map<String, Long> mergedMap = new HashMap<>(existingMap);
                newMap.forEach((innerKey, innerValue) -> mergedMap.merge(innerKey, innerValue, Long::sum));
                return mergedMap;
            });
        });
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {

        builder.startObject();
       for (String retriever : retrievers.keySet()) {
           builder.startObject(retriever);
           Map<String, Long> extendedDataForRetriever = retrievers.get(retriever);
           for (String key : extendedDataForRetriever.keySet()) {
               builder.field(key, extendedDataForRetriever.get(key));
           }
           builder.endObject();
       }
       builder.endObject();
       return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExtendedData that = (ExtendedData) o;
        return Objects.equals(retrievers, that.retrievers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(retrievers);
    }
}
