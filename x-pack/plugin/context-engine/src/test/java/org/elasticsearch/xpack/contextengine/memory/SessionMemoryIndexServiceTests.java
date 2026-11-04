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
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.WrapperQueryBuilder;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.inference.completion.ContentString;
import org.elasticsearch.inference.completion.Message;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.client.NoOpClient;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.contextengine.ContextEnginePlugin;
import org.elasticsearch.xpack.core.inference.action.InferenceAction;
import org.elasticsearch.xpack.core.inference.action.UnifiedCompletionAction;
import org.elasticsearch.xpack.core.inference.results.completion.ChatCompletionChoiceResponse;
import org.elasticsearch.xpack.core.inference.results.completion.ChatCompletionChunkResponse;
import org.elasticsearch.xpack.core.inference.results.completion.ChatCompletionMessageResponse;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xpack.contextengine.memory.SessionMemoryTestUtils.randomMedia;
import static org.elasticsearch.xpack.contextengine.memory.SessionMemoryTestUtils.randomSessionMemory;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class SessionMemoryIndexServiceTests extends ESTestCase {

    private static final String REDACTED = "Call me at <PHONE>";

    private ThreadPool threadPool;
    private AtomicReference<IndexRequest> indexed;
    private List<UnifiedCompletionAction.Request> redactionRequests;
    private SessionMemoryIndexService service;

    @Before
    public void setUpService() {
        threadPool = new TestThreadPool(getTestName());
        indexed = new AtomicReference<>();
        redactionRequests = new ArrayList<>();
        // answers redaction requests with a fixed completion and captures the index request instead of writing
        service = new SessionMemoryIndexService(new NoOpClient(threadPool) {
            @Override
            @SuppressWarnings("unchecked")
            protected <Request extends ActionRequest, Response extends ActionResponse> void doExecute(
                ActionType<Response> action,
                Request request,
                ActionListener<Response> listener
            ) {
                if (request instanceof UnifiedCompletionAction.Request redaction) {
                    redactionRequests.add(redaction);
                    listener.onResponse((Response) new InferenceAction.Response(completion(REDACTED)));
                } else {
                    indexed.set((IndexRequest) request);
                    listener.onResponse(null);
                }
            }
        });
    }

    @After
    public void tearDownService() {
        terminate(threadPool);
    }

    public void testRememberRedactsContent() {
        SessionMemory memory = randomSessionMemory();
        PlainActionFuture<SessionMemory> future = new PlainActionFuture<>();
        service.remember(memory, "redact-llm", future);

        assertThat(future.actionGet().content(), equalTo(REDACTED));
        assertThat(redactionRequests, hasSize(1));
        UnifiedCompletionAction.Request request = redactionRequests.get(0);
        assertThat(request.getInferenceEntityId(), equalTo("redact-llm"));
        assertThat(request.getTaskType(), equalTo(TaskType.CHAT_COMPLETION));
        assertThat(request.getUnifiedCompletionRequest().stream(), is(false));
        Message message = request.getUnifiedCompletionRequest().body().messages().get(0);
        assertThat(message.role(), equalTo("user"));
        assertThat(((ContentString) message.content()).content(), containsString(memory.content()));
        Map<String, Object> source = indexed.get().sourceAsMap();
        assertThat(source.get("content"), equalTo(REDACTED));
        assertThat(((List<?>) source.get("inputs")).get(0), equalTo(REDACTED));
    }

    public void testRememberSkipsRedactionWithoutContent() {
        SessionMemory memory = new SessionMemory(null, null, null, null, "session-1", "user-1", null, randomMedia(), null);
        PlainActionFuture<SessionMemory> future = new PlainActionFuture<>();
        service.remember(memory, "redact-llm", future);

        assertThat(future.actionGet().content(), nullValue());
        assertThat(redactionRequests, empty());
        assertThat(indexed.get(), notNullValue());
    }

    public void testRedactedContentWithoutChoices() {
        ChatCompletionChunkResponse completion = new ChatCompletionChunkResponse("id", List.of(), "model", "chat.completion", null);
        expectThrows(IllegalStateException.class, () -> SessionMemoryIndexService.redactedContent(completion));
    }

    public void testRememberWithoutRedaction() {
        SessionMemory memory = randomSessionMemory();
        PlainActionFuture<SessionMemory> future = new PlainActionFuture<>();
        service.remember(memory, randomBoolean() ? null : "", future);

        assertThat(future.actionGet().content(), equalTo(memory.content()));
        assertThat(redactionRequests, empty());
        assertThat(indexed.get().index(), equalTo(ContextEnginePlugin.SESSION_MEMORY_DATA_STREAM_NAME));
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

    private static ChatCompletionChunkResponse completion(String content) {
        ChatCompletionMessageResponse message = new ChatCompletionMessageResponse(content, null, "assistant", null, null, null);
        return new ChatCompletionChunkResponse(
            "id",
            List.of(new ChatCompletionChoiceResponse(message, "stop", 0)),
            "model",
            "chat.completion",
            null
        );
    }

    private static Map<String, Object> parse(byte[] json) throws IOException {
        try (XContentParser parser = XContentType.JSON.xContent().createParser(XContentParserConfiguration.EMPTY, json)) {
            return parser.map();
        }
    }
}
