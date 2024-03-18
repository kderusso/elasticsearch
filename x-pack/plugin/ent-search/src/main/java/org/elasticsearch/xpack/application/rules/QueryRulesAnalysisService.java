/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.rules;

import org.elasticsearch.action.admin.indices.analyze.AnalyzeAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.util.List;

import static org.elasticsearch.xpack.core.ClientHelper.ENT_SEARCH_ORIGIN;

public class QueryRulesAnalysisService {

    private static final TimeValue TIMEOUT_MS = TimeValue.timeValueMillis(1000);

    private static final Logger logger = LogManager.getLogger(QueryRulesAnalysisService.class);

    private final Client clientWithOrigin;

    public QueryRulesAnalysisService(Client client) {
        this.clientWithOrigin = new OriginSettingClient(client, ENT_SEARCH_ORIGIN);
    }

    public String analyze(String text, QueryRulesAnalysisConfig analysisConfig) {

        logger.info("Analyzing original text [" + text + "]");

        String analyzer = analysisConfig.analyzer();
        String tokenizer = analysisConfig.tokenizer();
        List<String> filters = analysisConfig.filters();

        AnalyzeAction.Request analyzeRequest = new AnalyzeAction.Request().analyzer(analyzer)
            .tokenizer(tokenizer)
            .addTokenFilters(filters)
            .text(text);
        AnalyzeAction.Response analyzeResponse = clientWithOrigin.execute(AnalyzeAction.INSTANCE, analyzeRequest).actionGet(TIMEOUT_MS);
        List<AnalyzeAction.AnalyzeToken> analyzeTokens = analyzeResponse.getTokens();
        StringBuilder sb = new StringBuilder();
        for (AnalyzeAction.AnalyzeToken analyzeToken : analyzeTokens) {
            logger.info("Analyzed term: [" + analyzeToken.getTerm() + "]");
            sb.append(analyzeToken.getTerm()).append(" ");

        }
        return sb.toString().trim();
    }

}
