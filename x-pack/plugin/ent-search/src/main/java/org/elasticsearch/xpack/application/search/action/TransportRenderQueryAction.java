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

public class TransportRenderQueryAction extends SearchApplicationTransportAction<RenderQueryAction.Request, RenderQueryAction.Response> {

    private static final Logger logger = LogManager.getLogger(TransportRenderQueryAction.class);

    private final SearchApplicationTemplateService templateService;

    @Inject
    public TransportRenderQueryAction(
        TransportService transportService,
        ActionFilters actionFilters,
        Client client,
        ClusterService clusterService,
        NamedWriteableRegistry namedWriteableRegistry,
        BigArrays bigArrays,
        XPackLicenseState licenseState,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry
    ) {
        super(
            RenderQueryAction.NAME,
            transportService,
            actionFilters,
            RenderQueryAction.Request::new,
            client,
            clusterService,
            namedWriteableRegistry,
            bigArrays,
            licenseState
        );
        this.templateService = new SearchApplicationTemplateService(scriptService, xContentRegistry);
    }

    @Override
    protected void doExecute(RenderQueryAction.Request request, ActionListener<RenderQueryAction.Response> listener) {
        systemIndexService.getSearchApplication(request.name(), ActionListener.wrap(searchApplication -> {
            final SearchSourceBuilder sourceBuilder = templateService.renderQuery(searchApplication, request.renderedTemplateParams());
            listener.onResponse(new RenderQueryAction.Response(sourceBuilder));
        }, listener::onFailure));
    }

}
