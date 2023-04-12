/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.search.action;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public class RenderMetadataAction extends ActionType<RenderMetadataAction.Response> {

    public static final RenderMetadataAction INSTANCE = new RenderMetadataAction();
    public static final String NAME = "cluster:admin/xpack/application/search_application/search";

    public RenderMetadataAction() {
        super(NAME, RenderMetadataAction.Response::new);
    }

    public static class Response extends ActionResponse implements ToXContentObject {

        private final Map<String, Object> renderedTemplateParams;

        public Response(StreamInput in) throws IOException {
            super(in);
            this.renderedTemplateParams = in.readMap();
        }

        public Response(Map<String, Object> renderedTemplateParams) {
            Objects.requireNonNull(renderedTemplateParams, "renderedTemplateParams must not be null");
            this.renderedTemplateParams = renderedTemplateParams;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeGenericMap(renderedTemplateParams);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("params", this.renderedTemplateParams);
            builder.endObject();
            return builder;
        }

        public Map<String, Object> renderedTemplateParams() {
            return this.renderedTemplateParams;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Response response = (Response) o;
            return renderedTemplateParams.equals(response.renderedTemplateParams);
        }

        @Override
        public int hashCode() {
            return Objects.hash(renderedTemplateParams);
        }
    }
}
