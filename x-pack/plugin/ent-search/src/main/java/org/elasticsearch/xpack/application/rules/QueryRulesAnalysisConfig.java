/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.rules;

import java.util.List;

public class QueryRulesAnalysisConfig {

    private final String analyzer;
    private final String tokenizer;
    private final List<String> filters;

    public QueryRulesAnalysisConfig(String analyzer, String tokenizer, List<String> filters) {
        this.analyzer = analyzer;
        this.tokenizer = tokenizer;
        this.filters = filters;
    }

    public String analyzer() {
        return analyzer;
    }

    public String tokenizer() {
        return tokenizer;
    }

    public List<String> filters() {
        return filters;
    }

}
