/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory.ingest;

import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.features.FeatureService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.contextengine.ContextEnginePlugin;
import org.elasticsearch.xpack.core.ClientHelper;
import org.elasticsearch.xpack.core.template.IndexTemplateRegistry;
import org.elasticsearch.xpack.core.template.IngestPipelineConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;

/**
 * Installs the ingest pipeline that the session memory data stream names as its {@code index.default_pipeline}. The
 * pipeline's processors are generated from the node settings: extraction and redaction are each left out when disabled,
 * and the pipeline always ends by rebuilding {@code inputs}.
 */
public class SessionMemoryPipelineRegistry extends IndexTemplateRegistry {

    public static final String PIPELINE_NAME = "context-engine-session-memory";
    static final int PIPELINE_VERSION = 1;

    /**
     * The Grok patterns the {@code redact} processor replaces, each labelled with its placeholder. Grok has no built-in phone
     * pattern, so {@code PHONE} matches North American numbers with separators; a bare run of digits is left alone.
     */
    static final List<String> REDACT_PATTERNS = List.of("%{EMAILADDRESS:EMAIL}", "%{IP:IP}", "%{PHONE:PHONE}");
    static final Map<String, String> REDACT_PATTERN_DEFINITIONS = Map.of(
        "PHONE",
        "(?:\\+?\\d{1,2}[-. ])?\\(?\\d{3}\\)?[-. ]\\d{3}[-. ]\\d{4}"
    );
    static final List<String> REDACTED_FIELDS = List.of(SessionMemoryDocument.CONTENT, SessionMemoryDocument.EXTRACTED_CONTENT);
    private static final String VERSION_VARIABLE = "xpack.contextengine.version";

    private final List<IngestPipelineConfig> ingestPipelines;

    public SessionMemoryPipelineRegistry(
        Settings nodeSettings,
        ClusterService clusterService,
        ThreadPool threadPool,
        Client client,
        NamedXContentRegistry xContentRegistry,
        FeatureService featureService
    ) {
        super(nodeSettings, clusterService, threadPool, client, xContentRegistry, featureService);
        this.ingestPipelines = List.of(new GeneratedPipelineConfig(pipeline(nodeSettings)));
    }

    @Override
    protected String getOrigin() {
        return ClientHelper.CONTEXT_ENGINE_ORIGIN;
    }

    @Override
    protected List<IngestPipelineConfig> getIngestPipelines() {
        return ingestPipelines;
    }

    /**
     * The pipeline body for the given node settings.
     */
    public static BytesReference pipeline(Settings settings) {
        try {
            XContentBuilder builder = jsonBuilder();
            builder.startObject();
            {
                builder.field("description", "Extracts text from session memory media and redacts PII before the memory is embedded");
                builder.field("version", PIPELINE_VERSION);
                builder.startArray("processors");
                if (ContextEnginePlugin.SESSION_MEMORY_EXTRACTION_ENABLED.get(settings)) {
                    builder.startObject();
                    builder.startObject(ExtractProcessor.TYPE);
                    builder.field(ExtractProcessor.INFERENCE_ID, ContextEnginePlugin.SESSION_MEMORY_EXTRACTION_INFERENCE_ID.get(settings));
                    builder.endObject();
                    builder.endObject();
                }
                if (ContextEnginePlugin.SESSION_MEMORY_REDACTION_ENABLED.get(settings)) {
                    for (String field : REDACTED_FIELDS) {
                        builder.startObject();
                        builder.startObject("redact");
                        builder.field("field", field);
                        builder.stringListField("patterns", REDACT_PATTERNS);
                        builder.field("pattern_definitions", REDACT_PATTERN_DEFINITIONS);
                        builder.field("ignore_missing", true);
                        builder.endObject();
                        builder.endObject();
                    }
                }
                builder.startObject();
                builder.startObject(SessionMemoryInputsProcessor.TYPE);
                builder.endObject();
                builder.endObject();
                builder.endArray();
            }
            builder.endObject();
            return new BytesArray(Strings.toString(builder));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class GeneratedPipelineConfig extends IngestPipelineConfig {

        private final BytesReference body;

        GeneratedPipelineConfig(BytesReference body) {
            super(PIPELINE_NAME, PIPELINE_NAME, PIPELINE_VERSION, VERSION_VARIABLE);
            this.body = body;
        }

        @Override
        public XContentType getXContentType() {
            return XContentType.JSON;
        }

        @Override
        public BytesReference loadConfig() {
            return body;
        }
    }
}
