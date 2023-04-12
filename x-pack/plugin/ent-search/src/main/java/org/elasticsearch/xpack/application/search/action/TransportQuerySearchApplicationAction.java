/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.search.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xpack.application.search.SearchApplicationTemplateService;

import java.util.Map;

public class TransportQuerySearchApplicationAction extends SearchApplicationTransportAction<
    SearchApplicationSearchRequest,
    SearchResponse> {

    private static final Logger logger = LogManager.getLogger(TransportQuerySearchApplicationAction.class);

    private final Client client;
    private final SearchApplicationTemplateService templateService;

    @Inject
    public TransportQuerySearchApplicationAction(
        TransportService transportService,
        ActionFilters actionFilters,
        Client client,
        ClusterService clusterService,
        NamedWriteableRegistry namedWriteableRegistry,
        NamedXContentRegistry xContentRegistry,
        BigArrays bigArrays,
        ScriptService scriptService,
        XPackLicenseState licenseState
    ) {
        super(
            QuerySearchApplicationAction.NAME,
            transportService,
            actionFilters,
            SearchApplicationSearchRequest::new,
            client,
            clusterService,
            namedWriteableRegistry,
            bigArrays,
            licenseState
        );
        this.client = client;
        this.templateService = new SearchApplicationTemplateService(scriptService, xContentRegistry);
    }

    @Override
    protected void doExecute(SearchApplicationSearchRequest request, ActionListener<SearchResponse> listener) {
        systemIndexService.getSearchApplication(request.name(), listener.delegateFailure((l, searchApplication) -> {
            try {
                final Map<String, Object> renderedTemplateParams = templateService.renderTemplate(searchApplication, request.queryParams());
                final SearchSourceBuilder sourceBuilder = templateService.renderQuery(searchApplication, renderedTemplateParams);
                SearchRequest searchRequest = new SearchRequest(searchApplication.indices()).source(sourceBuilder);

                client.execute(
                    SearchAction.INSTANCE,
                    searchRequest,
                    listener.delegateFailure((l2, searchResponse) -> l2.onResponse(searchResponse))
                );
            } catch (Exception e) {
                listener.onFailure(e);
            }
        }));
    }
}
