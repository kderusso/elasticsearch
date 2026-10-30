/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine;

import org.elasticsearch.Build;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.ExecutorNames;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.core.ClientHelper.CONTEXT_ENGINE_ORIGIN;

/**
 * Defines the Context Engine system index.
 */
public class ContextEngineIndexService {
    private static final Logger logger = LogManager.getLogger(ContextEngineIndexService.class);
    public static final String CONTEXT_ENGINE_ALIAS_NAME = ".context-engine";
    public static final String CONTEXT_ENGINE_INDEX_PREFIX = ".context-engine-";
    public static final String CONTEXT_ENGINE_INDEX_NAME_PATTERN = ".context-engine-*";
    public static final int CONTEXT_ENGINE_INDEX_VERSION = 1;
    public static final String CONTEXT_ENGINE_CONCRETE_INDEX_NAME = CONTEXT_ENGINE_INDEX_PREFIX + CONTEXT_ENGINE_INDEX_VERSION;

    public static SystemIndexDescriptor getSystemIndexDescriptor() {
        return SystemIndexDescriptor.builder()
            .setIndexPattern(CONTEXT_ENGINE_INDEX_NAME_PATTERN)
            .setPrimaryIndex(CONTEXT_ENGINE_CONCRETE_INDEX_NAME)
            .setDescription("Contains Context Engine data")
            .setMappings(getIndexMappings())
            .setSettings(getIndexSettings())
            .setAliasName(CONTEXT_ENGINE_ALIAS_NAME)
            .setIndexFormat(CONTEXT_ENGINE_INDEX_VERSION)
            .setOrigin(CONTEXT_ENGINE_ORIGIN)
            .setThreadPools(ExecutorNames.DEFAULT_SYSTEM_INDEX_THREAD_POOLS)
            .build();
    }

    private static XContentBuilder getIndexMappings() {
        try {
            final XContentBuilder builder = jsonBuilder();
            builder.startObject();
            {
                builder.startObject("_meta");
                builder.field("version", Build.current().version());
                builder.field(SystemIndexDescriptor.VERSION_META_KEY, CONTEXT_ENGINE_INDEX_VERSION);
                builder.endObject();

                builder.field("dynamic", "strict");
                builder.startObject("properties");
                builder.endObject();
            }
            builder.endObject();
            return builder;
        } catch (IOException e) {
            logger.fatal("Failed to build " + CONTEXT_ENGINE_CONCRETE_INDEX_NAME + " index mappings", e);
            throw new UncheckedIOException("Failed to build " + CONTEXT_ENGINE_CONCRETE_INDEX_NAME + " index mappings", e);
        }
    }

    private static Settings getIndexSettings() {
        return Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_AUTO_EXPAND_REPLICAS, "0-1")
            .put(IndexMetadata.SETTING_PRIORITY, 100)
            .put(IndexMetadata.INDEX_FORMAT_SETTING.getKey(), CONTEXT_ENGINE_INDEX_VERSION)
            .put("index.refresh_interval", "1s")
            .build();
    }
}
