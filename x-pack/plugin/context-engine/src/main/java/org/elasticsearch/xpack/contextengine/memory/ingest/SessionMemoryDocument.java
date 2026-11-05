/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory.ingest;

import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.xpack.contextengine.memory.SessionMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and rewrites the stored shape of a session memory inside an ingest document, where the content, the extracted
 * content and the media objects are held together in the {@code inputs} semantic field.
 */
final class SessionMemoryDocument {

    static final String CONTENT = SessionMemory.CONTENT_FIELD.getPreferredName();
    static final String EXTRACTED_CONTENT = SessionMemory.EXTRACTED_CONTENT_FIELD.getPreferredName();
    static final String INPUTS = SessionMemory.INPUTS_FIELD.getPreferredName();

    private SessionMemoryDocument() {}

    /**
     * The media objects in {@code inputs}, which are its non-string items.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> media(IngestDocument document) {
        List<Map<String, Object>> media = new ArrayList<>();
        List<?> inputs = document.getFieldValue(INPUTS, List.class, true);
        if (inputs != null) {
            for (Object input : inputs) {
                if (input instanceof Map<?, ?> item) {
                    media.add((Map<String, Object>) item);
                }
            }
        }
        return media;
    }

    /**
     * Rewrites {@code inputs} as the current content, the current extracted content and the untouched media objects, in
     * that order, so the embedding covers the rewritten text.
     */
    static void rebuildInputs(IngestDocument document) {
        List<Object> inputs = new ArrayList<>();
        String content = document.getFieldValue(CONTENT, String.class, true);
        if (content != null) {
            inputs.add(content);
        }
        String extractedContent = document.getFieldValue(EXTRACTED_CONTENT, String.class, true);
        if (extractedContent != null) {
            inputs.add(extractedContent);
        }
        inputs.addAll(media(document));
        if (inputs.isEmpty()) {
            if (document.hasField(INPUTS)) {
                document.removeField(INPUTS);
            }
        } else {
            document.setFieldValue(INPUTS, inputs);
        }
    }
}
