/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.rules;

import org.apache.lucene.search.spell.LevenshteinDistance;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Defines the different types of query rule criteria and their rules for matching input against the criteria.
 */
public enum QueryRuleCriteriaType {

    ALWAYS {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            return true;
        }
    },
    EXACT {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            throw new UnsupportedOperationException("[" + this + "] criteria type requires analysis service");
        }

        @Override
        public boolean isMatch(
            QueryRulesAnalysisService analysisService,
            Object input,
            Object criteriaValue,
            Map<String, Object> criteriaProperties
        ) {
            if (input instanceof String && criteriaValue instanceof String) {

                if (criteriaProperties.containsKey("analysis")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> analysisChain = (List<Map<String, Object>>) criteriaProperties.get("analysis");
                    String analyzedInput = (String) input;
                    String analyzedCriteriaValue = (String) criteriaValue;
                    for (Map<String, Object> analysisConfig : analysisChain) {
                        String tokenizer = analysisConfig.containsKey("tokenizer") ? (String) analysisConfig.get("tokenizer") : "keyword";
                        String filter = analysisConfig.containsKey("filter") ? (String) analysisConfig.get("filter") : "lowercase";
                        QueryRulesAnalysisConfig config = new QueryRulesAnalysisConfig(null, tokenizer, List.of(filter));
                        analyzedInput = analysisService.analyze(analyzedInput, config);
                        analyzedCriteriaValue = analysisService.analyze(analyzedCriteriaValue, config);
                    }
                    return analyzedInput.equals(analyzedCriteriaValue);
                } else {
                    return input.equals(criteriaValue);
                }

            } else {
                return parseDouble(input) == parseDouble(criteriaValue);
            }
        }
    },
    FUZZY {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            final LevenshteinDistance ld = new LevenshteinDistance();
            if (input instanceof String && criteriaValue instanceof String) {
                return ld.getDistance((String) input, (String) criteriaValue) > 0.5f;
            }
            return false;
        }
    },
    PREFIX {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            return ((String) input).startsWith((String) criteriaValue);
        }
    },
    SUFFIX {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            return ((String) input).endsWith((String) criteriaValue);
        }
    },
    CONTAINS {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            return ((String) input).contains((String) criteriaValue);
        }
    },
    LT {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            return parseDouble(input) < parseDouble(criteriaValue);
        }
    },
    LTE {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            return parseDouble(input) <= parseDouble(criteriaValue);
        }
    },
    GT {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            return parseDouble(input) > parseDouble(criteriaValue);
        }
    },
    GTE {
        @Override
        public boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties) {
            validateInput(input);
            return parseDouble(input) >= parseDouble(criteriaValue);
        }
    };

    public boolean validateInput(Object input, boolean throwOnInvalidInput) {
        boolean isValid = isValidForInput(input);
        if (isValid == false && throwOnInvalidInput) {
            throw new IllegalArgumentException("Input [" + input + "] is not valid for CriteriaType [" + this + "]");
        }
        return isValid;
    }

    public void validateInput(Object input) {
        validateInput(input, true);
    }

    public abstract boolean isMatch(Object input, Object criteriaValue, Map<String, Object> criteriaProperties);

    public boolean isMatch(
        QueryRulesAnalysisService analysisService,
        Object input,
        Object criteriaValue,
        Map<String, Object> criteriaProperties
    ) {
        return isMatch(input, criteriaValue, criteriaProperties);
    }

    public static QueryRuleCriteriaType type(String criteriaType) {
        for (QueryRuleCriteriaType type : values()) {
            if (type.name().equalsIgnoreCase(criteriaType)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown QueryRuleCriteriaType: " + criteriaType);
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }

    private boolean isValidForInput(Object input) {
        if (this == EXACT) {
            return input instanceof String || input instanceof Number;
        } else if (List.of(FUZZY, PREFIX, SUFFIX, CONTAINS).contains(this)) {
            return input instanceof String;
        } else if (List.of(LT, LTE, GT, GTE).contains(this)) {
            try {
                if (input instanceof Number == false) {
                    parseDouble(input.toString());
                }
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static double parseDouble(Object input) {
        return (input instanceof Number) ? ((Number) input).doubleValue() : Double.parseDouble(input.toString());
    }
}
