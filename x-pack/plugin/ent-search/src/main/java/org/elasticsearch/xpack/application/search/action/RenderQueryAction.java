/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.search.action;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public class RenderQueryAction extends ActionType<RenderQueryAction.Response> {

    public static final RenderQueryAction INSTANCE = new RenderQueryAction();
    public static final String NAME = "cluster:admin/xpack/application/search_application/render_query";

    public RenderQueryAction() {
        super(NAME, RenderQueryAction.Response::new);
    }

    public static class Request extends ActionRequest {

        private final String name;
        private final String renderedTemplate;

        public Request(StreamInput in) throws IOException {
            super(in);
            this.name = in.readString();
            this.renderedTemplate = in.readString();
        }

        public Request(String name, String renderedTemplate) {
            this.name = name;
            this.renderedTemplate = renderedTemplate;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;

            if (name == null || name.isEmpty()) {
                validationException = addValidationError("name is required", validationException);
            }

            if (renderedTemplate == null || renderedTemplate.isEmpty()) {
                validationException = addValidationError("renderedTemplate is required", validationException);
            }
            return validationException;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(name);
            out.writeString(renderedTemplate);
        }

        public String name() { return name; }
        public String renderedTemplate() { return renderedTemplate; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Request request = (Request) o;
            return renderedTemplate.equals(request.renderedTemplate) && name.equals(request.name);
        }

        @Override
        public int hashCode() { return Objects.hash(renderedTemplate, name); }
    }

    public static class Response extends ActionResponse implements ToXContentObject {

        private final SearchSourceBuilder searchSourceBuilder;

        public Response(StreamInput in) throws IOException {
            super(in);
            this.searchSourceBuilder = new SearchSourceBuilder(in);
        }

        public Response(SearchSourceBuilder searchSourceBuilder) {
            this.searchSourceBuilder = searchSourceBuilder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            searchSourceBuilder.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return searchSourceBuilder.toXContent(builder, params);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Response response = (Response) o;
            return searchSourceBuilder.equals(response.searchSourceBuilder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(searchSourceBuilder);
        }
    }
}
