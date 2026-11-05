/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory.ingest;

import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.features.FeatureService;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.client.NoOpClient;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.contextengine.ContextEnginePlugin;
import org.elasticsearch.xpack.core.template.IngestPipelineConfig;
import org.junit.After;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class SessionMemoryPipelineRegistryTests extends ESTestCase {

    private ThreadPool threadPool;

    @After
    public void stopPool() {
        if (threadPool != null) {
            terminate(threadPool);
        }
    }

    public void testRegistryInstallsPipeline() {
        threadPool = new TestThreadPool(getTestName());
        ClusterService clusterService = ClusterServiceUtils.createClusterService(threadPool);
        SessionMemoryPipelineRegistry registry = new SessionMemoryPipelineRegistry(
            Settings.EMPTY,
            clusterService,
            threadPool,
            new NoOpClient(threadPool),
            NamedXContentRegistry.EMPTY,
            new FeatureService(List.of())
        );

        assertThat(registry.getIngestPipelines(), hasSize(1));
        IngestPipelineConfig config = registry.getIngestPipelines().get(0);
        assertThat(config.getId(), equalTo(SessionMemoryPipelineRegistry.PIPELINE_NAME));
        assertThat(config.getVersion(), equalTo(SessionMemoryPipelineRegistry.PIPELINE_VERSION));
        assertThat(config.getXContentType(), equalTo(XContentType.JSON));
        assertThat(config.loadConfig(), equalTo(SessionMemoryPipelineRegistry.pipeline(Settings.EMPTY)));
        clusterService.close();
    }

    public void testPipelineWithDefaults() throws IOException {
        Map<String, Object> pipeline = parse(SessionMemoryPipelineRegistry.pipeline(Settings.EMPTY));

        assertThat(pipeline.get("version"), equalTo(SessionMemoryPipelineRegistry.PIPELINE_VERSION));
        List<?> processors = (List<?>) pipeline.get("processors");
        assertThat(types(processors), contains(ExtractProcessor.TYPE, "redact", "redact", SessionMemoryInputsProcessor.TYPE));
        Map<?, ?> extract = (Map<?, ?>) ((Map<?, ?>) processors.get(0)).get(ExtractProcessor.TYPE);
        assertThat(extract.get("inference_id"), equalTo(".jina-reader"));
        Map<?, ?> redactContent = (Map<?, ?>) ((Map<?, ?>) processors.get(1)).get("redact");
        assertThat(redactContent.get("field"), equalTo("content"));
        assertThat(redactContent.get("patterns"), equalTo(SessionMemoryPipelineRegistry.REDACT_PATTERNS));
        assertThat(redactContent.get("pattern_definitions"), equalTo(SessionMemoryPipelineRegistry.REDACT_PATTERN_DEFINITIONS));
        assertThat(redactContent.get("ignore_missing"), equalTo(true));
        Map<?, ?> redactExtracted = (Map<?, ?>) ((Map<?, ?>) processors.get(2)).get("redact");
        assertThat(redactExtracted.get("field"), equalTo("extracted_content"));
    }

    public void testPipelineUsesConfiguredExtractionInferenceId() throws IOException {
        Settings settings = Settings.builder()
            .put(ContextEnginePlugin.SESSION_MEMORY_EXTRACTION_INFERENCE_ID.getKey(), "my-extractor")
            .build();
        List<?> processors = (List<?>) parse(SessionMemoryPipelineRegistry.pipeline(settings)).get("processors");

        assertThat(((Map<?, ?>) ((Map<?, ?>) processors.get(0)).get(ExtractProcessor.TYPE)).get("inference_id"), equalTo("my-extractor"));
    }

    public void testPipelineOmitsDisabledExtraction() throws IOException {
        Settings settings = Settings.builder().put(ContextEnginePlugin.SESSION_MEMORY_EXTRACTION_ENABLED.getKey(), false).build();
        List<?> processors = (List<?>) parse(SessionMemoryPipelineRegistry.pipeline(settings)).get("processors");

        assertThat(types(processors), contains("redact", "redact", SessionMemoryInputsProcessor.TYPE));
    }

    public void testPipelineOmitsDisabledRedaction() throws IOException {
        Settings settings = Settings.builder().put(ContextEnginePlugin.SESSION_MEMORY_REDACTION_ENABLED.getKey(), false).build();
        List<?> processors = (List<?>) parse(SessionMemoryPipelineRegistry.pipeline(settings)).get("processors");

        assertThat(types(processors), contains(ExtractProcessor.TYPE, SessionMemoryInputsProcessor.TYPE));
    }

    public void testPipelineWithBothDisabled() throws IOException {
        Settings settings = Settings.builder()
            .put(ContextEnginePlugin.SESSION_MEMORY_EXTRACTION_ENABLED.getKey(), false)
            .put(ContextEnginePlugin.SESSION_MEMORY_REDACTION_ENABLED.getKey(), false)
            .build();
        List<?> processors = (List<?>) parse(SessionMemoryPipelineRegistry.pipeline(settings)).get("processors");

        assertThat(types(processors), contains(SessionMemoryInputsProcessor.TYPE));
    }

    private static List<String> types(List<?> processors) {
        return processors.stream().map(p -> ((Map<?, ?>) p).keySet().iterator().next().toString()).toList();
    }

    private static Map<String, Object> parse(BytesReference json) throws IOException {
        try (XContentParser parser = XContentType.JSON.xContent().createParser(XContentParserConfiguration.EMPTY, json.streamInput())) {
            return parser.map();
        }
    }
}
