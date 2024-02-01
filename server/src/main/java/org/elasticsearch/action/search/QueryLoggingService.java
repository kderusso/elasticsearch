/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.search;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.datastreams.CreateDataStreamAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;

public class QueryLoggingService {

    private static final String DATA_STREAM_NAME = ".poc-query-logging";

    private static final Logger logger = LogManager.getLogger(QueryLoggingService.class);

    private final Client client;

    private boolean dataStreamExists = false;

    public QueryLoggingService(Client client) {
        this.client = client;
    }

    public void createDataStreamIfItDoesNotExist() {
        if (dataStreamExists == false) {
            createDataStream();
            dataStreamExists = true;
        }
    }

    public void logQuery(SearchRequest searchRequest, SearchResponse searchResponse) {
        try {

            XContentBuilder builder = XContentFactory.jsonBuilder();

            builder.startObject();
            builder.field("@timestamp", System.currentTimeMillis());
            builder.startObject("searchRequest");
            builder.field("source", searchRequest.source());
            builder.field("indices", searchRequest.indices());
            builder.field("hasKnnSearch", searchRequest.hasKnnSearch());
            builder.field("buildDescription", searchRequest.buildDescription());
            builder.endObject();

            builder.startObject("searchResponse");
            builder.field("tookInMillis", searchResponse.getTook().getMillis());
            builder.field("totalHits", searchResponse.getHits().getTotalHits().value);
            builder.startArray("hits");
            for (SearchHit hit : searchResponse.getHits().getHits()) {
                builder.startObject();
                builder.field("id", hit.getId());
                builder.field("index", hit.getIndex());
                builder.field("score", hit.getScore());
                builder.field("source");
                builder.map(hit.getSourceAsMap());
                builder.endObject();
            }
            builder.endArray();
            builder.endObject();
            builder.endObject();

            client.prepareIndex(DATA_STREAM_NAME).setCreate(true).setSource(builder).execute();
        } catch (Exception e) {
            logger.error("Failed to log query", e);
        }
    }

    private void createDataStream() {
        CreateDataStreamAction.Request request = new CreateDataStreamAction.Request(DATA_STREAM_NAME);
        client.execute(
            CreateDataStreamAction.INSTANCE,
            request,
            ActionListener.wrap(
                response -> logger.info("Created data stream: {}", DATA_STREAM_NAME),
                exception -> logger.error("Failed to create data stream: {}", DATA_STREAM_NAME, exception)
            )
        );
    }

}
