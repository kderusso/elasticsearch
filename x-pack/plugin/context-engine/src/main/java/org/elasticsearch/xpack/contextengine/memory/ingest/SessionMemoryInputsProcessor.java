/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory.ingest;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.ingest.AbstractProcessor;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.Processor;

import java.util.Map;

/**
 * Rebuilds the {@code inputs} semantic field of a session memory from its current {@code content}, {@code extracted_content}
 * and media objects, so that the text the earlier processors extracted and redacted is what gets embedded.
 */
public class SessionMemoryInputsProcessor extends AbstractProcessor {

    public static final String TYPE = "context_engine_inputs";

    SessionMemoryInputsProcessor(String tag, String description) {
        super(tag, description);
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        SessionMemoryDocument.rebuildInputs(document);
        return document;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public static final class Factory implements Processor.Factory {

        @Override
        public SessionMemoryInputsProcessor create(
            Map<String, Processor.Factory> processorFactories,
            String tag,
            String description,
            Map<String, Object> config,
            ProjectId projectId
        ) {
            return new SessionMemoryInputsProcessor(tag, description);
        }
    }
}
