/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory.ingest;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.inference.InferenceString;
import org.elasticsearch.ingest.AbstractProcessor;
import org.elasticsearch.ingest.ConfigurationUtils;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.xpack.contextengine.memory.SessionMemoryInference;
import org.elasticsearch.xpack.core.inference.action.DocumentExtractionAction;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.elasticsearch.xpack.core.ClientHelper.CONTEXT_ENGINE_ORIGIN;

/**
 * Turns the PDF, image and HTML media of a session memory into text with a {@code document_extraction} endpoint and stores
 * it as {@code extracted_content}. The {@link SessionMemoryInputsProcessor} later adds the text to {@code inputs}.
 */
public class ExtractProcessor extends AbstractProcessor {

    public static final String TYPE = "context_engine_extract";
    public static final String INFERENCE_ID = "inference_id";

    private final Client client;
    private final String inferenceId;

    ExtractProcessor(String tag, String description, Client client, String inferenceId) {
        super(tag, description);
        this.client = client;
        this.inferenceId = inferenceId;
    }

    @Override
    public boolean isAsync() {
        return true;
    }

    @Override
    public void execute(IngestDocument document, BiConsumer<IngestDocument, Exception> handler) {
        List<InferenceString> inputs = SessionMemoryInference.extractableInputs(SessionMemoryDocument.media(document));
        if (inputs.isEmpty()) {
            handler.accept(document, null);
            return;
        }
        client.execute(
            DocumentExtractionAction.INSTANCE,
            SessionMemoryInference.extractionRequest(inferenceId, inputs),
            ActionListener.wrap(response -> {
                document.setFieldValue(
                    SessionMemoryDocument.EXTRACTED_CONTENT,
                    SessionMemoryInference.extractedContent(response.getResults())
                );
                handler.accept(document, null);
            }, e -> handler.accept(document, e))
        );
    }

    @Override
    public String getType() {
        return TYPE;
    }

    String inferenceId() {
        return inferenceId;
    }

    public static final class Factory implements Processor.Factory {

        private final Client client;

        public Factory(Client client) {
            this.client = new OriginSettingClient(client, CONTEXT_ENGINE_ORIGIN);
        }

        @Override
        public ExtractProcessor create(
            Map<String, Processor.Factory> processorFactories,
            String tag,
            String description,
            Map<String, Object> config,
            ProjectId projectId
        ) {
            String inferenceId = ConfigurationUtils.readStringProperty(TYPE, tag, config, INFERENCE_ID);
            return new ExtractProcessor(tag, description, client, inferenceId);
        }
    }
}
