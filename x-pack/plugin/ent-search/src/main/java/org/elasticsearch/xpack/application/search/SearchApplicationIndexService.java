/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.search;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequestBuilder;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.io.stream.InputStreamStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.OutputStreamStreamOutput;
import org.elasticsearch.common.io.stream.ReleasableBytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParserUtils;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.core.Streams;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.indices.ExecutorNames;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.rest.action.admin.indices.AliasesNotFoundException;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.core.ClientHelper.ENT_SEARCH_ORIGIN;

/**
 * A service that manages the persistent {@link SearchApplication} configurations.
 *
 * TODO: Revise the internal format (mappings). Should we use rest or transport versioning for BWC?
 */
public class SearchApplicationIndexService {
    private static final Logger logger = LogManager.getLogger(SearchApplicationIndexService.class);
    public static final String SEARCH_APPLICATION_ALIAS_NAME = ".search-app";
    public static final String SEARCH_APPLICATION_CONCRETE_INDEX_NAME = ".search-app-1";
    public static final String SEARCH_APPLICATION_INDEX_NAME_PATTERN = ".search-app-*";

    private final Client clientWithOrigin;
    private final ClusterService clusterService;
    public final NamedWriteableRegistry namedWriteableRegistry;
    private final BigArrays bigArrays;

    public SearchApplicationIndexService(
        Client client,
        ClusterService clusterService,
        NamedWriteableRegistry namedWriteableRegistry,
        BigArrays bigArrays
    ) {
        this.clientWithOrigin = new OriginSettingClient(client, ENT_SEARCH_ORIGIN);
        this.clusterService = clusterService;
        this.namedWriteableRegistry = namedWriteableRegistry;
        this.bigArrays = bigArrays;
    }

    /**
     * Returns the {@link SystemIndexDescriptor} for the {@link SearchApplication} system index.
     *
     * @return The {@link SystemIndexDescriptor} for the {@link SearchApplication} system index.
     */
    public static SystemIndexDescriptor getSystemIndexDescriptor() {
        return SystemIndexDescriptor.builder()
            .setIndexPattern(SEARCH_APPLICATION_INDEX_NAME_PATTERN)
            .setPrimaryIndex(SEARCH_APPLICATION_CONCRETE_INDEX_NAME)
            .setDescription("Contains Search Application configuration")
            .setMappings(getIndexMappings())
            .setSettings(getIndexSettings())
            .setAliasName(SEARCH_APPLICATION_ALIAS_NAME)
            .setVersionMetaKey("version")
            .setOrigin(ENT_SEARCH_ORIGIN)
            .setThreadPools(ExecutorNames.DEFAULT_SYSTEM_INDEX_THREAD_POOLS)
            .build();
    }

    private static Settings getIndexSettings() {
        return Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_AUTO_EXPAND_REPLICAS, "0-1")
            .put(IndexMetadata.SETTING_PRIORITY, 100)
            .put("index.refresh_interval", "1s")
            .build();
    }

    private static XContentBuilder getIndexMappings() {
        try {
            final XContentBuilder builder = jsonBuilder();
            builder.startObject();
            {
                builder.startObject("_meta");
                builder.field("version", Version.CURRENT.toString());
                builder.endObject();

                builder.field("dynamic", "strict");
                builder.startObject("properties");
                {
                    builder.startObject(SearchApplication.NAME_FIELD.getPreferredName());
                    builder.field("type", "keyword");
                    builder.endObject();

                    builder.startObject(SearchApplication.INDICES_FIELD.getPreferredName());
                    builder.field("type", "keyword");
                    builder.endObject();

                    builder.startObject(SearchApplication.ANALYTICS_COLLECTION_NAME_FIELD.getPreferredName());
                    builder.field("type", "keyword");
                    builder.endObject();

                    builder.startObject(SearchApplication.BINARY_CONTENT_FIELD.getPreferredName());
                    builder.field("type", "object");
                    builder.field("enabled", "false");
                    builder.endObject();
                }
                builder.endObject();
            }
            builder.endObject();
            return builder;
        } catch (IOException e) {
            logger.fatal("Failed to build " + SEARCH_APPLICATION_CONCRETE_INDEX_NAME + " index mappings", e);
            throw new UncheckedIOException("Failed to build " + SEARCH_APPLICATION_CONCRETE_INDEX_NAME + " index mappings", e);
        }
    }

    /**
     * Gets the {@link SearchApplication} from the index if present, or delegate a {@link ResourceNotFoundException} failure to the provided
     * listener if not.
     *
     * @param resourceName The resource name.
     * @param listener The action listener to invoke on response/failure.
     */
    public void getSearchApplication(String resourceName, ActionListener<SearchApplication> listener) {
        final GetRequest getRequest = new GetRequest(SEARCH_APPLICATION_ALIAS_NAME).id(resourceName).realtime(true);
        clientWithOrigin.get(getRequest, new DelegatingIndexNotFoundActionListener<>(resourceName, listener, getResponse -> {
            if (getResponse.isExists()) {
                return parseSearchApplicationBinaryFromSource(getResponse.getSourceInternal());
            } else {
                throw new ResourceNotFoundException(resourceName);
            }
        }));
    }

    private static String getSearchAliasName(SearchApplication app) {
        return app.searchAlias();
    }

    /**
     * Creates or updates the {@link SearchApplication} in the underlying index.
     *
     * @param app The search application object.
     * @param create If true, the search application must not already exist
     * @param listener The action listener to invoke on response/failure.
     */
    public void putSearchApplication(SearchApplication app, boolean create, ActionListener<IndexResponse> listener) {
        createOrUpdateAlias(app, new ActionListener<>() {
            @Override
            public void onResponse(AcknowledgedResponse acknowledgedResponse) {
                updateSearchApplication(app, create, listener);
            }

            @Override
            public void onFailure(Exception e) {
                // Convert index not found failure from the alias API into an illegal argument
                Exception failException = e;
                if (e instanceof IndexNotFoundException) {
                    failException = new IllegalArgumentException(e.getMessage(), e);
                }
                listener.onFailure(failException);
            }
        });
    }

    private void createOrUpdateAlias(SearchApplication app, ActionListener<AcknowledgedResponse> listener) {

        final Metadata metadata = clusterService.state().metadata();
        final String searchAliasName = getSearchAliasName(app);

        IndicesAliasesRequestBuilder requestBuilder = null;
        if (metadata.hasAlias(searchAliasName)) {
            Set<String> currentAliases = metadata.aliasedIndices(searchAliasName).stream().map(Index::getName).collect(Collectors.toSet());
            Set<String> targetAliases = Set.of(app.indices());

            requestBuilder = updateAliasIndices(currentAliases, targetAliases, searchAliasName);

        } else {
            requestBuilder = clientWithOrigin.admin().indices().prepareAliases().addAlias(app.indices(), searchAliasName);
        }

        requestBuilder.execute(listener);
    }

    private IndicesAliasesRequestBuilder updateAliasIndices(Set<String> currentAliases, Set<String> targetAliases, String searchAliasName) {

        Set<String> deleteIndices = new HashSet<>(currentAliases);
        deleteIndices.removeAll(targetAliases);

        IndicesAliasesRequestBuilder aliasesRequestBuilder = clientWithOrigin.admin().indices().prepareAliases();

        // Always re-add aliases, as an index could have been removed manually and it must be restored
        for (String newIndex : targetAliases) {
            aliasesRequestBuilder.addAliasAction(IndicesAliasesRequest.AliasActions.add().index(newIndex).alias(searchAliasName));
        }
        for (String deleteIndex : deleteIndices) {
            aliasesRequestBuilder.addAliasAction(IndicesAliasesRequest.AliasActions.remove().index(deleteIndex).alias(searchAliasName));
        }

        return aliasesRequestBuilder;
    }

    private void updateSearchApplication(SearchApplication app, boolean create, ActionListener<IndexResponse> listener) {
        try (ReleasableBytesStreamOutput buffer = new ReleasableBytesStreamOutput(0, bigArrays.withCircuitBreaking())) {
            try (XContentBuilder source = XContentFactory.jsonBuilder(buffer)) {
                source.startObject()
                    .field(SearchApplication.NAME_FIELD.getPreferredName(), app.name())
                    .field(SearchApplication.INDICES_FIELD.getPreferredName(), app.indices())
                    .field(SearchApplication.ANALYTICS_COLLECTION_NAME_FIELD.getPreferredName(), app.analyticsCollectionName())
                    .directFieldAsBase64(
                        SearchApplication.BINARY_CONTENT_FIELD.getPreferredName(),
                        os -> writeSearchApplicationBinaryWithVersion(app, os, clusterService.state().nodes().getMinNodeVersion())
                    )
                    .endObject();
            }
            DocWriteRequest.OpType opType = (create ? DocWriteRequest.OpType.CREATE : DocWriteRequest.OpType.INDEX);
            final IndexRequest indexRequest = new IndexRequest(SEARCH_APPLICATION_ALIAS_NAME).opType(DocWriteRequest.OpType.INDEX)
                .id(app.name())
                .opType(opType)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .source(buffer.bytes(), XContentType.JSON);
            clientWithOrigin.index(indexRequest, listener);
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private void deleteSearchApplication(String resourceName, ActionListener<DeleteResponse> listener) {

        try {
            final DeleteRequest deleteRequest = new DeleteRequest(SEARCH_APPLICATION_ALIAS_NAME).id(resourceName)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
            clientWithOrigin.delete(deleteRequest, new DelegatingIndexNotFoundActionListener<>(resourceName, listener, deleteResponse -> {
                if (deleteResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                    throw new ResourceNotFoundException(resourceName);
                } else {
                    return deleteResponse;
                }
            }));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    GetAliasesResponse getAlias(String searchAliasName) {
        return clientWithOrigin.admin().indices().getAliases(new GetAliasesRequest(searchAliasName)).actionGet();
    }

    private void removeAlias(String searchAliasName, ActionListener<AcknowledgedResponse> listener) {
        IndicesAliasesRequest aliasesRequest = new IndicesAliasesRequest().addAliasAction(
            IndicesAliasesRequest.AliasActions.remove().aliases(searchAliasName).indices("*")
        );

        clientWithOrigin.admin()
            .indices()
            .aliases(
                aliasesRequest,
                new DelegatingIndexNotFoundActionListener<>(searchAliasName, listener, ignored -> AcknowledgedResponse.TRUE)
            );
    }

    /**
     * Deletes both the provided {@param resourceName} in the underlying index as well as the associated alias,
     * or delegate a failure to the provided listener if the resource does not exist or failed to delete.
     *
     * @param resourceName The name of the {@link SearchApplication} to delete.
     * @param listener The action listener to invoke on response/failure.
     *
     */
    public void deleteSearchApplicationAndAlias(String resourceName, ActionListener<DeleteResponse> listener) {
        removeAlias(SearchApplication.getSearchAliasName(resourceName), new ActionListener<AcknowledgedResponse>() {
            @Override
            public void onResponse(AcknowledgedResponse acknowledgedResponse) {
                deleteSearchApplication(resourceName, listener);
            }

            @Override
            public void onFailure(Exception e) {
                if (e instanceof AliasesNotFoundException) {
                    listener.onFailure(new ResourceNotFoundException(resourceName));
                }
                listener.onFailure(e);
            }
        });
    }

    /**
     * List the {@link SearchApplication} in ascending order of their names.
     *
     * @param queryString The query string to filter the results.
     * @param from From index to start the search from.
     * @param size The maximum number of {@link SearchApplication} to return.
     * @param listener The action listener to invoke on response/failure.
     */
    public void listSearchApplication(String queryString, int from, int size, ActionListener<SearchApplicationResult> listener) {
        try {
            final SearchSourceBuilder source = new SearchSourceBuilder().from(from)
                .size(size)
                .query(new QueryStringQueryBuilder(queryString))
                .docValueField(SearchApplication.NAME_FIELD.getPreferredName())
                .docValueField(SearchApplication.INDICES_FIELD.getPreferredName())
                .docValueField(SearchApplication.ANALYTICS_COLLECTION_NAME_FIELD.getPreferredName())
                .storedFields(Collections.singletonList("_none_"))
                .sort(SearchApplication.NAME_FIELD.getPreferredName(), SortOrder.ASC);
            final SearchRequest req = new SearchRequest(SEARCH_APPLICATION_ALIAS_NAME).source(source);
            clientWithOrigin.search(req, new ActionListener<>() {
                @Override
                public void onResponse(SearchResponse searchResponse) {
                    listener.onResponse(mapSearchResponse(searchResponse));
                }

                @Override
                public void onFailure(Exception e) {
                    if (e instanceof IndexNotFoundException) {
                        listener.onResponse(new SearchApplicationResult(Collections.emptyList(), 0L));
                        return;
                    }
                    listener.onFailure(e);
                }
            });
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private static SearchApplicationResult mapSearchResponse(SearchResponse response) {
        final List<SearchApplicationListItem> apps = Arrays.stream(response.getHits().getHits())
            .map(SearchApplicationIndexService::hitToSearchApplicationListItem)
            .toList();
        return new SearchApplicationResult(apps, (int) response.getHits().getTotalHits().value);
    }

    private static SearchApplicationListItem hitToSearchApplicationListItem(SearchHit searchHit) {
        final Map<String, DocumentField> documentFields = searchHit.getDocumentFields();
        final String resourceName = documentFields.get(SearchApplication.NAME_FIELD.getPreferredName()).getValue();
        return new SearchApplicationListItem(
            resourceName,
            documentFields.get(SearchApplication.INDICES_FIELD.getPreferredName()).getValues().toArray(String[]::new),
            SearchApplication.getSearchAliasName(resourceName),
            documentFields.get(SearchApplication.ANALYTICS_COLLECTION_NAME_FIELD.getPreferredName()).getValue()
        );
    }

    private SearchApplication parseSearchApplicationBinaryFromSource(BytesReference source) {
        try (XContentParser parser = XContentHelper.createParser(XContentParserConfiguration.EMPTY, source, XContentType.JSON)) {
            ensureExpectedToken(parser.nextToken(), XContentParser.Token.START_OBJECT, parser);
            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                ensureExpectedToken(XContentParser.Token.FIELD_NAME, parser.currentToken(), parser);
                parser.nextToken();
                if (SearchApplication.BINARY_CONTENT_FIELD.getPreferredName().equals(parser.currentName())) {
                    final CharBuffer encodedBuffer = parser.charBuffer();
                    InputStream encodedIn = Base64.getDecoder().wrap(new InputStream() {
                        @Override
                        public int read() {
                            if (encodedBuffer.hasRemaining()) {
                                return encodedBuffer.get();
                            } else {
                                return -1; // end of stream
                            }
                        }
                    });
                    try (
                        StreamInput in = new NamedWriteableAwareStreamInput(new InputStreamStreamInput(encodedIn), namedWriteableRegistry)
                    ) {
                        return parseSearchApplicationBinaryWithVersion(in);
                    }
                } else {
                    XContentParserUtils.parseFieldsValue(parser); // consume and discard unknown fields
                }
            }
            throw new ElasticsearchParseException("[" + SearchApplication.BINARY_CONTENT_FIELD.getPreferredName() + "] field is missing");
        } catch (IOException e) {
            throw new ElasticsearchParseException("Failed to parse: " + source.utf8ToString(), e);
        }
    }

    static SearchApplication parseSearchApplicationBinaryWithVersion(StreamInput in) throws IOException {
        TransportVersion version = TransportVersion.readVersion(in);
        assert version.onOrBefore(TransportVersion.CURRENT) : version + " >= " + TransportVersion.CURRENT;
        in.setTransportVersion(version);
        return new SearchApplication(in);
    }

    static void writeSearchApplicationBinaryWithVersion(SearchApplication app, OutputStream os, Version minNodeVersion) throws IOException {
        // do not close the output
        os = Streams.noCloseStream(os);
        TransportVersion.writeVersion(minNodeVersion.transportVersion, new OutputStreamStreamOutput(os));
        try (OutputStreamStreamOutput out = new OutputStreamStreamOutput(os)) {
            out.setTransportVersion(minNodeVersion.transportVersion);
            app.writeTo(out);
        }
    }

    static class DelegatingIndexNotFoundActionListener<T, R> extends ActionListener.Delegating<T, R> {

        private final CheckedFunction<T, R, Exception> fn;
        private final String resourceName;

        DelegatingIndexNotFoundActionListener(String resourceName, ActionListener<R> delegate, CheckedFunction<T, R, Exception> fn) {
            super(delegate);
            this.fn = fn;
            this.resourceName = resourceName;
        }

        @Override
        public void onResponse(T t) {
            ActionListener.completeWith(delegate, () -> fn.apply(t));
        }

        @Override
        public void onFailure(Exception e) {
            if (e instanceof IndexNotFoundException) {
                delegate.onFailure(new ResourceNotFoundException(resourceName, e));
                return;
            }
            delegate.onFailure(e);
        }
    }

    public record SearchApplicationResult(List<SearchApplicationListItem> items, long totalResults) {}
}
