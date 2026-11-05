/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory;

import org.elasticsearch.inference.DataFormat;
import org.elasticsearch.inference.DataType;
import org.elasticsearch.inference.DocumentExtractionRequest;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InferenceString;
import org.elasticsearch.xpack.core.inference.action.DocumentExtractionAction;
import org.elasticsearch.xpack.core.inference.results.DocumentExtractionResults;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the text extraction inference request for a session memory's media and reads its results.
 */
public final class SessionMemoryInference {

    private SessionMemoryInference() {}

    /**
     * The media objects whose type the {@code document_extraction} task type accepts, as inference inputs.
     */
    public static List<InferenceString> extractableInputs(List<Map<String, Object>> media) {
        List<InferenceString> inputs = new ArrayList<>();
        for (Map<String, Object> item : media) {
            DataType type = DataType.fromString((String) item.get("type"));
            if (DocumentExtractionRequest.SUPPORTED_DOCUMENT_EXTRACTION_DATA_TYPES.contains(type)) {
                Object format = item.get("format");
                DataFormat dataFormat = format == null ? null : DataFormat.fromString((String) format);
                inputs.add(new InferenceString(type, dataFormat, (String) item.get("value")));
            }
        }
        return inputs;
    }

    public static DocumentExtractionAction.Request extractionRequest(String inferenceId, List<InferenceString> inputs) {
        return new DocumentExtractionAction.Request(inferenceId, new DocumentExtractionRequest(inputs, Map.of()), null);
    }

    /**
     * The extracted text of every input, one block per input separated by blank lines.
     */
    public static String extractedContent(InferenceServiceResults results) {
        if (results instanceof DocumentExtractionResults extraction && extraction.results().isEmpty() == false) {
            return extraction.results().stream().map(DocumentExtractionResults.Result::content).collect(Collectors.joining("\n\n"));
        }
        throw new IllegalStateException("extraction endpoint returned no content");
    }
}
