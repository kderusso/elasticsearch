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
import org.elasticsearch.common.Strings;
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

public class RenderMetadataAction extends ActionType<RenderMetadataAction.Response> {

    public static final RenderMetadataAction INSTANCE = new RenderMetadataAction();
    public static final String NAME = "cluster:admin/xpack/application/search_application/render_metadata";

    private static final ParseField QUERY_PARAMS_FIELD = new ParseField("params");

    public RenderMetadataAction() {
        super(NAME, RenderMetadataAction.Response::new);
    }

    public static class Request extends ActionRequest {
        private final String name;

        private static final ConstructingObjectParser<Request, String> PARSER = new ConstructingObjectParser<>(
            "query_params",
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

        private final Map<String, Object> queryParams;

        public Request(StreamInput in) throws IOException {
            super(in);
            this.name = in.readString();
            this.queryParams = in.readMap();
        }

        public Request(String name) {
            this(name, Map.of());
        }

        public Request(String name, Map<String, Object> queryParams) {
            Objects.requireNonNull(name, "Application name must be specified");
            this.name = name;

            Objects.requireNonNull(queryParams, "Query parameters must be specified");
            this.queryParams = queryParams;
        }

        public static Request fromXContent(String name, XContentParser contentParser) {
            return PARSER.apply(contentParser, name);
        }

        public String name() {
            return name;
        }

        public Map<String, Object> queryParams() {
            return queryParams;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;

            if (Strings.isEmpty(name)) {
                validationException = addValidationError("Search Application name is missing", validationException);
            }

            return validationException;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(name);
            out.writeGenericMap(queryParams);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RenderMetadataAction.Request request = (RenderMetadataAction.Request) o;
            return Objects.equals(name, request.name) && Objects.equals(queryParams, request.queryParams);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, queryParams);
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
            return Objects.equals(searchSourceBuilder, response.searchSourceBuilder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(searchSourceBuilder);
        }
    }
}
