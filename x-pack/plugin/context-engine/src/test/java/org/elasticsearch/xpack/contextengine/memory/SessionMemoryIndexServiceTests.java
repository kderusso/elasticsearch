/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.WrapperQueryBuilder;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.client.NoOpClient;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.contextengine.ContextEnginePlugin;
import org.elasticsearch.xpack.contextengine.memory.ingest.SessionMemoryPipelineRegistry;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.contextengine.memory.SessionMemoryTestUtils.randomMedia;
import static org.elasticsearch.xpack.contextengine.memory.SessionMemoryTestUtils.randomSessionMemory;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class SessionMemoryIndexServiceTests extends ESTestCase {

    private static final String BACKING_INDEX = ".ds-context-engine-session-memory-000001";
    private static final String REDACTED = "Call me at <PHONE>";
    private static final String EXTRACTED = "# Page one";

    private ThreadPool threadPool;
    private AtomicReference<IndexRequest> indexed;
    private AtomicReference<GetRequest> fetched;
    private SessionMemoryIndexService service;

    @Before
    public void setUpService() {
        threadPool = new TestThreadPool(getTestName());
        indexed = new AtomicReference<>();
        fetched = new AtomicReference<>();
        // captures the index request instead of writing, and answers the get with the captured source rewritten the way the
        // session memory pipeline would: content and extracted content redacted, extracted content added when there is media
        service = new SessionMemoryIndexService(new NoOpClient(threadPool) {
            @Override
            @SuppressWarnings("unchecked")
            protected <Request extends ActionRequest, Response extends ActionResponse> void doExecute(
                ActionType<Response> action,
                Request request,
                ActionListener<Response> listener
            ) {
                if (request instanceof IndexRequest indexRequest) {
                    indexed.set(indexRequest);
                    listener.onResponse(
                        (Response) new IndexResponse(new ShardId(BACKING_INDEX, "uuid", 0), randomAlphaOfLength(12), 0, 1, 1, true)
                    );
                } else if (request instanceof GetRequest getRequest) {
                    fetched.set(getRequest);
                    listener.onResponse((Response) new GetResponse(stored(getRequest)));
                } else {
                    listener.onFailure(new UnsupportedOperationException(action.name()));
                }
            }
        });
    }

    @After
    public void tearDownService() {
        terminate(threadPool);
    }

    public void testRememberIndexesStoredShape() {
        SessionMemory memory = randomSessionMemory().withExtractedContent(null);
        PlainActionFuture<SessionMemory> future = new PlainActionFuture<>();
        service.remember(memory, future);
        future.actionGet();

        IndexRequest request = indexed.get();
        assertThat(request.index(), equalTo(ContextEnginePlugin.SESSION_MEMORY_DATA_STREAM_NAME));
        assertThat(request.opType(), equalTo(DocWriteRequest.OpType.CREATE));
        assertThat(request.getRefreshPolicy(), equalTo(WriteRequest.RefreshPolicy.WAIT_UNTIL));
        assertThat(request.getPipeline(), equalTo(SessionMemoryPipelineRegistry.PIPELINE_NAME));
        Map<String, Object> source = request.sourceAsMap();
        assertThat(source.get("content"), equalTo(memory.content()));
        assertThat(source.get("media"), nullValue());
        List<?> inputs = (List<?>) source.get("inputs");
        assertThat(inputs, hasSize(1 + memory.media().size()));
        assertThat(inputs.get(0), equalTo(memory.content()));
        assertThat(source.get("@timestamp"), notNullValue());
    }

    public void testRememberAssignsIdWhenMissing() {
        SessionMemory memory = randomSessionMemory().withIdAndTimestamp(null, null);
        PlainActionFuture<SessionMemory> future = new PlainActionFuture<>();
        service.remember(memory, future);

        assertThat(future.actionGet().id(), notNullValue());
        assertThat(indexed.get().sourceAsMap().get("id"), equalTo(future.actionGet().id()));
    }

    public void testRememberKeepsCallerId() {
        SessionMemory memory = randomSessionMemory().withIdAndTimestamp("memory-1", null);
        PlainActionFuture<SessionMemory> future = new PlainActionFuture<>();
        service.remember(memory, future);

        assertThat(future.actionGet().id(), equalTo("memory-1"));
    }

    public void testRememberReturnsPipelineOutput() {
        SessionMemory memory = new SessionMemory(null, null, null, null, "session-1", "user-1", "Call me at 555-1234", randomMedia(), null);
        PlainActionFuture<SessionMemory> future = new PlainActionFuture<>();
        service.remember(memory, future);

        SessionMemory stored = future.actionGet();
        assertThat(stored.content(), equalTo(REDACTED));
        assertThat(stored.extractedContent(), equalTo(REDACTED));
        assertThat(stored.media(), equalTo(memory.media()));
        assertThat(stored.sessionId(), equalTo("session-1"));
        assertThat(fetched.get().index(), equalTo(BACKING_INDEX));
        assertThat(indexed.get().sourceAsMap().get("content"), equalTo("Call me at 555-1234"));
        assertThat(indexed.get().sourceAsMap().get("extracted_content"), nullValue());
    }

    public void testRememberWithoutMediaHasNoExtractedContent() {
        SessionMemory memory = new SessionMemory(null, null, null, null, "session-1", "user-1", "a note", null, null);
        PlainActionFuture<SessionMemory> future = new PlainActionFuture<>();
        service.remember(memory, future);

        assertThat(future.actionGet().extractedContent(), nullValue());
        assertThat(future.actionGet().media(), hasSize(0));
    }

    public void testKnnQuery() throws IOException {
        WrapperQueryBuilder query = (WrapperQueryBuilder) SessionMemoryIndexService.knnQuery(
            "dark mode",
            "test-embedding",
            20,
            List.of(QueryBuilders.termQuery("user_id", "user-1"))
        );

        Map<?, ?> knn = (Map<?, ?>) parse(query.source()).get("knn");
        assertThat(knn.get("field"), equalTo("inputs"));
        assertThat(knn.get("k"), equalTo(20));
        assertThat(knn.get("num_candidates"), equalTo(200));
        assertThat(((Map<?, ?>) ((Map<?, ?>) knn.get("query_vector_builder")).get("embedding")).get("input"), equalTo("dark mode"));
        assertThat((List<?>) knn.get("filter"), hasSize(1));
        Map<?, ?> term = (Map<?, ?>) ((Map<?, ?>) ((List<?>) knn.get("filter")).get(0)).get("term");
        assertThat(((Map<?, ?>) term.get("user_id")).get("value"), equalTo("user-1"));
    }

    public void testKnnQueryImageInputWithoutFilters() throws IOException {
        Map<String, Object> input = Map.of("type", "image", "value", "data:image/png;base64,iVBORw0KGgo=");
        WrapperQueryBuilder query = (WrapperQueryBuilder) SessionMemoryIndexService.knnQuery(input, "test-embedding", 5, List.of());

        Map<?, ?> knn = (Map<?, ?>) parse(query.source()).get("knn");
        assertThat(knn.get("num_candidates"), equalTo(100));
        assertThat(((Map<?, ?>) ((Map<?, ?>) knn.get("query_vector_builder")).get("embedding")).get("input"), equalTo(input));
        assertThat(knn.get("filter"), nullValue());
    }

    private GetResult stored(GetRequest request) {
        Map<String, Object> source = new HashMap<>(indexed.get().sourceAsMap());
        List<Object> inputs = new ArrayList<>();
        if (source.containsKey("content")) {
            source.put("content", REDACTED);
            inputs.add(REDACTED);
        }
        List<Object> media = ((List<?>) source.get("inputs")).stream().filter(input -> input instanceof Map).map(o -> (Object) o).toList();
        if (media.isEmpty() == false) {
            source.put("extracted_content", REDACTED);
            inputs.add(REDACTED);
        }
        inputs.addAll(media);
        source.put("inputs", inputs);
        try {
            XContentBuilder builder = jsonBuilder().map(source);
            return new GetResult(request.index(), request.id(), 0, 1, 1, true, BytesReference.bytes(builder), Map.of(), Map.of());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, Object> parse(byte[] json) throws IOException {
        try (XContentParser parser = XContentType.JSON.xContent().createParser(XContentParserConfiguration.EMPTY, json)) {
            return parser.map();
        }
    }
}
