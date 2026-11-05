/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory.ingest;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.inference.DataType;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.TestIngestDocument;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.client.NoOpClient;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.contextengine.memory.SessionMemoryInference;
import org.elasticsearch.xpack.core.inference.action.DocumentExtractionAction;
import org.elasticsearch.xpack.core.inference.action.InferenceAction;
import org.elasticsearch.xpack.core.inference.results.DocumentExtractionResults;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xpack.contextengine.memory.SessionMemoryTestUtils.randomMedia;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;

public class ExtractProcessorTests extends ESTestCase {

    private static final String EXTRACTED = "# Page one";

    private ThreadPool threadPool;
    private List<DocumentExtractionAction.Request> extractionRequests;
    private boolean fail;
    private ExtractProcessor processor;

    @Before
    public void setUpProcessor() throws Exception {
        threadPool = new TestThreadPool(getTestName());
        extractionRequests = new ArrayList<>();
        // answers extraction requests with one fixed page per input, or fails them when the test asks for it
        NoOpClient client = new NoOpClient(threadPool) {
            @Override
            @SuppressWarnings("unchecked")
            protected <Request extends ActionRequest, Response extends ActionResponse> void doExecute(
                ActionType<Response> action,
                Request request,
                ActionListener<Response> listener
            ) {
                DocumentExtractionAction.Request extraction = (DocumentExtractionAction.Request) request;
                extractionRequests.add(extraction);
                if (fail) {
                    listener.onFailure(new ElasticsearchException("extraction failed"));
                    return;
                }
                int inputs = extraction.getDocumentExtractionRequest().inputs().size();
                listener.onResponse((Response) new InferenceAction.Response(extractionResults(inputs)));
            }
        };
        Map<String, Object> config = new HashMap<>(Map.of(ExtractProcessor.INFERENCE_ID, "extract-docs"));
        processor = new ExtractProcessor.Factory(client).create(Map.of(), "tag", null, config, ProjectId.DEFAULT);
    }

    @After
    public void tearDownProcessor() {
        terminate(threadPool);
    }

    public void testFactory() {
        assertThat(processor.getType(), equalTo(ExtractProcessor.TYPE));
        assertThat(processor.inferenceId(), equalTo("extract-docs"));
        assertThat(processor.isAsync(), equalTo(true));
    }

    public void testFactoryRequiresInferenceId() {
        expectThrows(
            ElasticsearchException.class,
            () -> new ExtractProcessor.Factory(new NoOpClient(threadPool)).create(Map.of(), "tag", null, new HashMap<>(), ProjectId.DEFAULT)
        );
    }

    public void testExtractsMedia() {
        List<Map<String, Object>> media = randomMedia();
        List<Object> inputs = new ArrayList<>();
        inputs.add("a note");
        inputs.addAll(media);
        IngestDocument document = document(Map.of("content", "a note", "inputs", inputs));

        IngestDocument result = execute(document);

        String expected = String.join("\n\n", Collections.nCopies(media.size(), EXTRACTED));
        assertThat(result.getFieldValue("extracted_content", String.class), equalTo(expected));
        assertThat(result.getFieldValue("inputs", List.class), equalTo(inputs));
        assertThat(extractionRequests, hasSize(1));
        DocumentExtractionAction.Request request = extractionRequests.get(0);
        assertThat(request.getInferenceEntityId(), equalTo("extract-docs"));
        assertThat(request.getDocumentExtractionRequest().inputs(), hasSize(media.size()));
        assertThat(
            request.getDocumentExtractionRequest().inputs().get(0).dataType(),
            equalTo(DataType.fromString((String) media.get(0).get("type")))
        );
    }

    public void testSkipsDocumentWithoutMedia() {
        IngestDocument document = document(Map.of("content", "a note", "inputs", List.of("a note")));

        IngestDocument result = execute(document);

        assertThat(result.hasField("extracted_content"), equalTo(false));
        assertThat(result.getFieldValue("inputs", List.class), equalTo(List.of("a note")));
        assertThat(extractionRequests, empty());
    }

    public void testSkipsUnsupportedMedia() {
        Map<String, Object> audio = Map.of("type", "audio", "value", "data:audio/mp3;base64,AAAA");
        IngestDocument document = document(Map.of("inputs", List.of(audio)));

        IngestDocument result = execute(document);

        assertThat(result.hasField("extracted_content"), equalTo(false));
        assertThat(extractionRequests, empty());
    }

    public void testFailureIsHandedToHandler() {
        fail = true;
        IngestDocument document = document(Map.of("inputs", randomMedia()));
        AtomicReference<Exception> failure = new AtomicReference<>();
        processor.execute(document, (doc, e) -> failure.set(e));

        assertThat(failure.get(), instanceOf(ElasticsearchException.class));
        assertThat(document.hasField("extracted_content"), equalTo(false));
    }

    public void testExtractedContentWithoutResults() {
        expectThrows(IllegalStateException.class, () -> SessionMemoryInference.extractedContent(new DocumentExtractionResults(List.of())));
    }

    private IngestDocument execute(IngestDocument document) {
        AtomicReference<IngestDocument> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        processor.execute(document, (doc, e) -> {
            result.set(doc);
            failure.set(e);
        });
        assertThat(failure.get(), nullValue());
        return result.get();
    }

    private static IngestDocument document(Map<String, Object> source) {
        return TestIngestDocument.withDefaultVersion(new HashMap<>(source));
    }

    private static DocumentExtractionResults extractionResults(int inputs) {
        return new DocumentExtractionResults(
            Collections.nCopies(inputs, new DocumentExtractionResults.Result(EXTRACTED, "markdown", Map.of()))
        );
    }
}
