/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.DataStreamTestHelper;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodeUtils;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.downsample.DownsampleShardTask;
import org.junit.Before;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.elasticsearch.cluster.node.DiscoveryNode.STATELESS_ENABLED_SETTING_NAME;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;
import static org.elasticsearch.cluster.routing.TestShardRouting.shardRoutingBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;

public class DownsampleShardPersistentTaskExecutorTests extends ESTestCase {

    private ProjectId projectId;
    private ClusterState initialClusterState;
    private DownsampleShardPersistentTaskExecutor executor;

    @Before
    public void setup() {
        projectId = randomProjectIdOrDefault();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant start = now.minus(2, ChronoUnit.HOURS);
        Instant end = now.plus(40, ChronoUnit.MINUTES);
        initialClusterState = DataStreamTestHelper.getClusterStateWithDataStream(
            projectId,
            "metrics-app1",
            List.of(new Tuple<>(start, end))
        );
        executor = new DownsampleShardPersistentTaskExecutor(
            mock(Client.class),
            DownsampleShardTask.TASK_NAME,
            Settings.EMPTY,
            mock(Executor.class)
        );
    }

    public void testGetAssignment() {
        var backingIndex = initialClusterState.metadata().getProject(projectId).dataStreams().get("metrics-app1").getWriteIndex();
        var node = newNode();
        var shardId = new ShardId(backingIndex, 0);
        var clusterState = ClusterState.builder(initialClusterState)
            .nodes(new DiscoveryNodes.Builder().add(node).build())
            .putRoutingTable(
                projectId,
                RoutingTable.builder()
                    .add(
                        IndexRoutingTable.builder(backingIndex)
                            .addShard(shardRoutingBuilder(shardId, node.getId(), true, STARTED).withRecoverySource(null).build())
                    )
                    .build()
            )
            .build();

        var params = new DownsampleShardTaskParams(
            new DownsampleConfig(new DateHistogramInterval("1h")),
            shardId.getIndexName(),
            1,
            1,
            shardId,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY
        );
        var result = executor.getAssignment(params, Set.of(node), clusterState, projectId);
        assertThat(result.getExecutorNode(), equalTo(node.getId()));
    }

    public void testGetAssignmentMissingIndex() {
        var backingIndex = initialClusterState.metadata().getProject(projectId).dataStreams().get("metrics-app1").getWriteIndex();
        var node = newNode();
        var shardId = new ShardId(backingIndex, 0);
        var clusterState = ClusterState.builder(initialClusterState)
            .nodes(new DiscoveryNodes.Builder().add(node).build())
            .putRoutingTable(
                projectId,
                RoutingTable.builder()
                    .add(
                        IndexRoutingTable.builder(backingIndex)
                            .addShard(shardRoutingBuilder(shardId, node.getId(), true, STARTED).withRecoverySource(null).build())
                    )
                    .build()
            )
            .build();

        var missingShardId = new ShardId(new Index("another_index", "uid"), 0);
        var params = new DownsampleShardTaskParams(
            new DownsampleConfig(new DateHistogramInterval("1h")),
            missingShardId.getIndexName(),
            1,
            1,
            missingShardId,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY
        );
        var result = executor.getAssignment(params, Set.of(node), clusterState, projectId);
        assertThat(result.getExecutorNode(), equalTo(node.getId()));
        assertThat(result.getExplanation(), equalTo("a node to fail and stop this persistent task"));
    }

    public void testGetStatelessAssignment() {
        executor = new DownsampleShardPersistentTaskExecutor(
            mock(Client.class),
            DownsampleShardTask.TASK_NAME,
            Settings.builder().put(STATELESS_ENABLED_SETTING_NAME, "true").build(),
            mock(Executor.class)
        );
        var backingIndex = initialClusterState.metadata().getProject(projectId).dataStreams().get("metrics-app1").getWriteIndex();
        var searchNode = newNode(Set.of(DiscoveryNodeRole.SEARCH_ROLE));
        var indexNode = newNode(Set.of(DiscoveryNodeRole.INDEX_ROLE));
        var shardId = new ShardId(backingIndex, 0);
        ShardRouting indexOnlyShard = shardRoutingBuilder(shardId, indexNode.getId(), true, STARTED).withRecoverySource(null)
            .withRole(ShardRouting.Role.INDEX_ONLY)
            .build();
        var clusterState = ClusterState.builder(initialClusterState)
            .nodes(new DiscoveryNodes.Builder().add(indexNode).add(searchNode).build())
            .putRoutingTable(
                projectId,
                RoutingTable.builder().add(IndexRoutingTable.builder(backingIndex).addShard(indexOnlyShard)).build()
            )
            .build();

        var params = new DownsampleShardTaskParams(
            new DownsampleConfig(new DateHistogramInterval("1h")),
            shardId.getIndexName(),
            1,
            1,
            shardId,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY
        );
        var result = executor.getAssignment(params, Set.of(indexNode, searchNode), clusterState, projectId);
        assertThat(result.getExecutorNode(), nullValue());

        // Assign a copy of the shard to a search node
        clusterState = ClusterState.builder(clusterState)
            .putRoutingTable(
                projectId,
                RoutingTable.builder()
                    .add(
                        IndexRoutingTable.builder(backingIndex)
                            .addShard(indexOnlyShard)
                            .addShard(
                                shardRoutingBuilder(shardId, searchNode.getId(), false, STARTED).withRecoverySource(null)
                                    .withRole(ShardRouting.Role.SEARCH_ONLY)
                                    .build()
                            )
                    )
                    .build()
            )
            .build();
        result = executor.getAssignment(params, Set.of(indexNode, searchNode), clusterState, projectId);
        assertThat(result.getExecutorNode(), equalTo(searchNode.getId()));
    }

    private static DiscoveryNode newNode() {
        return newNode(DiscoveryNodeRole.roles());
    }

    private static DiscoveryNode newNode(Set<DiscoveryNodeRole> nodes) {
        return DiscoveryNodeUtils.create(
            "node_" + UUIDs.randomBase64UUID(random()),
            buildNewFakeTransportAddress(),
            Map.of(),
            DiscoveryNodeRole.roles()
        );
    }

}
