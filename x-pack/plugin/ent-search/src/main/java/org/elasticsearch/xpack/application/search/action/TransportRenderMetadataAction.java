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
import org.elasticsearch.script.TemplateScript;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xpack.application.search.SearchApplicationTemplateService;

public class TransportRenderMetadataAction extends SearchApplicationTransportAction<
    SearchApplicationSearchRequest,
    RenderMetadataAction.Response> {

    private static final Logger logger = LogManager.getLogger(TransportRenderMetadataAction.class);

    private final SearchApplicationTemplateService templateService;

    @Inject
    public TransportRenderMetadataAction(
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
            RenderMetadataAction.NAME,
            transportService,
            actionFilters,
            SearchApplicationSearchRequest::new,
            client,
            clusterService,
            namedWriteableRegistry,
            bigArrays,
            licenseState
        );
        this.templateService = new SearchApplicationTemplateService(scriptService, xContentRegistry);
    }

    @Override
    protected void doExecute(SearchApplicationSearchRequest request, ActionListener<RenderMetadataAction.Response> listener) {
        systemIndexService.getSearchApplication(request.name(), listener.delegateFailure((l, searchApplication) -> {
            try {
                final String renderedTemplate = templateService.renderTemplate(searchApplication, request);
                listener.onResponse(new RenderMetadataAction.Response(renderedTemplate));
            } catch (Exception e) {
                listener.onFailure(e);
            }
        }));
    }

}
