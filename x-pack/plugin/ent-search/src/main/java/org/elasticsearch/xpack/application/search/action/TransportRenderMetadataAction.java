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
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.TemplateScript;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.application.search.SearchApplication;
import org.elasticsearch.xpack.application.search.SearchApplicationTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class TransportRenderMetadataAction extends SearchApplicationTransportAction<
    RenderMetadataAction.Request,
    RenderMetadataAction.Response> {

    private static final Logger logger = LogManager.getLogger(TransportRenderMetadataAction.class);

    private final ScriptService scriptService;

    private final NamedXContentRegistry xContentRegistry;

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
            RenderMetadataAction.Request::new,
            client,
            clusterService,
            namedWriteableRegistry,
            bigArrays,
            licenseState
        );
        this.scriptService = scriptService;
        this.xContentRegistry = xContentRegistry;
    }

    @Override
    protected void doExecute(RenderMetadataAction.Request request, ActionListener<RenderMetadataAction.Response> listener) {
        systemIndexService.getSearchApplication(request.name(), listener.delegateFailure((l, searchApplication) -> {
            try {
                final SearchSourceBuilder sourceBuilder = renderTemplate(searchApplication, request);
                listener.onResponse(new RenderMetadataAction.Response(sourceBuilder));
            } catch (Exception e) {
                listener.onFailure(e);
            }
        }));
    }

    private SearchSourceBuilder renderTemplate(SearchApplication searchApplication, RenderMetadataAction.Request request)
        throws IOException, ValidationException {

        final SearchApplicationTemplate template = searchApplication.searchApplicationTemplate();
        final Map<String, Object> queryParams = request.queryParams();
        final Script script = template.script();

        template.validateTemplateParams(queryParams);

        TemplateScript compiledTemplate = scriptService.compile(script, TemplateScript.CONTEXT)
            .newInstance(mergeTemplateParams(request, script));
        String requestSource = compiledTemplate.execute();

        XContentParserConfiguration parserConfig = XContentParserConfiguration.EMPTY.withRegistry(xContentRegistry)
            .withDeprecationHandler(LoggingDeprecationHandler.INSTANCE);
        try (XContentParser parser = XContentFactory.xContent(XContentType.JSON).createParser(parserConfig, requestSource)) {
            SearchSourceBuilder builder = SearchSourceBuilder.searchSource();
            builder.parseXContent(parser, false);
            return builder;
        }
    }

    private static Map<String, Object> mergeTemplateParams(RenderMetadataAction.Request request, Script script) {
        Map<String, Object> mergedTemplateParams = new HashMap<>(script.getParams());
        mergedTemplateParams.putAll(request.queryParams());

        return mergedTemplateParams;
    }

}
