/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.ilm;

import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.internal.AdminClient;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.IndicesAdminClient;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.EqualsHashCodeTestUtils;
import org.elasticsearch.xpack.core.ilm.Step.StepKey;
import org.junit.Before;
import org.mockito.Mockito;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public abstract class AbstractStepTestCase<T extends Step> extends ESTestCase {

    protected Client client;
    protected AdminClient adminClient;
    protected IndicesAdminClient indicesClient;

    public static ClusterState emptyClusterState() {
        return ClusterState.builder(ClusterName.DEFAULT).build();
    }

    @Before
    public void setupClient() {
        client = Mockito.mock(Client.class);
        adminClient = Mockito.mock(AdminClient.class);
        indicesClient = Mockito.mock(IndicesAdminClient.class);

        Mockito.when(client.projectClient(Mockito.any())).thenReturn(client);
        Mockito.when(client.admin()).thenReturn(adminClient);
        Mockito.when(adminClient.indices()).thenReturn(indicesClient);
    }

    protected static final int NUMBER_OF_TEST_RUNS = 20;
    protected static final TimeValue MASTER_TIMEOUT = TimeValue.timeValueSeconds(30);

    protected abstract T createRandomInstance();

    protected abstract T mutateInstance(T instance);

    protected abstract T copyInstance(T instance);

    public void testHashcodeAndEquals() {
        for (int runs = 0; runs < NUMBER_OF_TEST_RUNS; runs++) {
            EqualsHashCodeTestUtils.checkEqualsAndHashCode(createRandomInstance(), this::copyInstance, this::mutateInstance);
        }
    }

    public static StepKey randomStepKey() {
        String randomPhase = randomAlphaOfLength(10);
        String randomAction = randomAlphaOfLength(10);
        String randomStepName = randomAlphaOfLength(10);
        return new StepKey(randomPhase, randomAction, randomStepName);
    }

    public void testStepNameNotError() {
        T instance = createRandomInstance();
        StepKey stepKey = instance.getKey();
        assertFalse(ErrorStep.NAME.equals(stepKey.name()));
        StepKey nextStepKey = instance.getKey();
        assertFalse(ErrorStep.NAME.equals(nextStepKey.name()));
    }

    protected void performActionAndWait(
        AsyncActionStep step,
        IndexMetadata indexMetadata,
        ClusterState currentClusterState,
        ClusterStateObserver observer
    ) throws Exception {
        final var future = new PlainActionFuture<Void>();
        step.performAction(indexMetadata, currentClusterState, observer, future);
        try {
            future.get(SAFE_AWAIT_TIMEOUT.millis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            } else {
                fail(e, "unexpected");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e, "unexpected");
        } catch (Exception e) {
            fail(e, "unexpected");
        }
    }
}
