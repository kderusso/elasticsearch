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
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

public class RenderQueryAction extends ActionType<RenderQueryAction.Response> {

    public static final RenderQueryAction INSTANCE = new RenderQueryAction();
    public static final String NAME = "cluster:admin/xpack/application/search_application/render_query";

    public RenderQueryAction() {
        super(NAME, RenderQueryAction.Response::new);
    }

    public static class Request extends ActionRequest {

        private static final ParseField QUERY_PARAMS_FIELD = new ParseField("params");
        private final String name;
        private final Map<String, Object> renderedTemplateParams;

        private static final ConstructingObjectParser<Request, String> PARSER = new ConstructingObjectParser<>(
            QUERY_PARAMS_FIELD,
            false,
            (params, searchAppName) -> {
                @SuppressWarnings("unchecked")
                final Map<String, Object> queryParams = (Map<String, Object>) params[0];
                return new Request(searchAppName, queryParams);
            }
        );

        static {
            PARSER.declareObject(constructorArg(), (p, c) -> p.map(), QUERY_PARAMS_FIELD);
        }

        public Request(StreamInput in) throws IOException {
            super(in);
            this.name = in.readString();
            this.renderedTemplateParams = in.readMap();
        }

        public Request(String name, Map<String, Object> renderedTemplateParams) {
            this.name = name;
            this.renderedTemplateParams = renderedTemplateParams;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;

            if (name == null || name.isEmpty()) {
                validationException = addValidationError("name is required", validationException);
            }

            if (renderedTemplateParams == null || renderedTemplateParams.isEmpty()) {
                validationException = addValidationError("params is required", validationException);
            }
            return validationException;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(name);
            out.writeGenericMap(renderedTemplateParams);
        }

        public static Request fromXContent(String name, XContentParser contentParser) {
            return PARSER.apply(contentParser, name);
        }

        public String name() {
            return name;
        }

        public Map<String, Object> renderedTemplateParams() {
            return renderedTemplateParams;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Request request = (Request) o;
            return renderedTemplateParams.equals(request.renderedTemplateParams) && name.equals(request.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(renderedTemplateParams, name);
        }
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
