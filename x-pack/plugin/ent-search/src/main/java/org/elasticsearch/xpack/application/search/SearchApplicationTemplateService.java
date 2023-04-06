/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.search;

import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.TemplateScript;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.application.search.action.SearchApplicationSearchRequest;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SearchApplicationTemplateService {

    private ScriptService scriptService;
    private NamedXContentRegistry xContentRegistry;

    public SearchApplicationTemplateService(ScriptService scriptService, NamedXContentRegistry xContentRegistry) {
        this.scriptService = scriptService;
        this.xContentRegistry = xContentRegistry;
    }

    /**
     * Renders the search application's associated template with the provided request parameters.
     *
     * @param searchApplication
     * @param request
     * @return SearchSourceBuilder
     * @throws IOException
     * @throws ValidationException
     */
    public SearchSourceBuilder renderTemplate(SearchApplication searchApplication, SearchApplicationSearchRequest request)
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

    private static Map<String, Object> mergeTemplateParams(SearchApplicationSearchRequest request, Script script) {
        Map<String, Object> mergedTemplateParams = new HashMap<>(script.getParams());
        mergedTemplateParams.putAll(request.queryParams());

        return mergedTemplateParams;
    }
}
