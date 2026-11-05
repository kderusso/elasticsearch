/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory.ingest;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.TestIngestDocument;
import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.contextengine.memory.SessionMemoryTestUtils.randomMedia;
import static org.hamcrest.Matchers.equalTo;

public class SessionMemoryInputsProcessorTests extends ESTestCase {

    private final SessionMemoryInputsProcessor processor = new SessionMemoryInputsProcessor.Factory().create(
        Map.of(),
        "tag",
        null,
        new HashMap<>(),
        ProjectId.DEFAULT
    );

    public void testFactory() {
        assertThat(processor.getType(), equalTo(SessionMemoryInputsProcessor.TYPE));
        assertThat(processor.isAsync(), equalTo(false));
    }

    public void testRebuildsInputsFromRedactedTextAndMedia() {
        List<Map<String, Object>> media = randomMedia();
        List<Object> inputs = new ArrayList<>();
        inputs.add("Mail kathleen@example.com");
        inputs.addAll(media);
        IngestDocument document = document(Map.of("content", "Mail <EMAIL>", "extracted_content", "Call <PHONE>", "inputs", inputs));

        processor.execute(document);

        List<Object> rebuilt = new ArrayList<>();
        rebuilt.add("Mail <EMAIL>");
        rebuilt.add("Call <PHONE>");
        rebuilt.addAll(media);
        assertThat(document.getFieldValue("inputs", List.class), equalTo(rebuilt));
    }

    public void testMediaOnly() {
        List<Map<String, Object>> media = randomMedia();
        IngestDocument document = document(Map.of("inputs", media));

        processor.execute(document);

        assertThat(document.getFieldValue("inputs", List.class), equalTo(media));
    }

    public void testRemovesEmptyInputs() {
        IngestDocument document = document(Map.of("session_id", "s-1", "inputs", List.of()));

        processor.execute(document);

        assertThat(document.hasField("inputs"), equalTo(false));
    }

    private static IngestDocument document(Map<String, Object> source) {
        return TestIngestDocument.withDefaultVersion(new HashMap<>(source));
    }
}
