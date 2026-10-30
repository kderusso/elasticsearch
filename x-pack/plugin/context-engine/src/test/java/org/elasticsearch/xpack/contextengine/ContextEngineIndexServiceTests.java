/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.test.ESTestCase;

import java.util.List;

import static org.elasticsearch.xpack.core.ClientHelper.CONTEXT_ENGINE_ORIGIN;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ContextEngineIndexServiceTests extends ESTestCase {

    public void testSystemIndexDescriptor() {
        SystemIndexDescriptor descriptor = ContextEngineIndexService.getSystemIndexDescriptor();
        assertThat(descriptor.getIndexPattern(), equalTo(ContextEngineIndexService.CONTEXT_ENGINE_INDEX_NAME_PATTERN));
        assertThat(descriptor.getPrimaryIndex(), equalTo(ContextEngineIndexService.CONTEXT_ENGINE_CONCRETE_INDEX_NAME));
        assertThat(descriptor.getAliasName(), equalTo(ContextEngineIndexService.CONTEXT_ENGINE_ALIAS_NAME));
        assertThat(descriptor.getOrigin(), equalTo(CONTEXT_ENGINE_ORIGIN));
        assertThat(descriptor.isExternal(), is(false));
        assertThat(descriptor.isAutomaticallyManaged(), is(true));
        assertThat(descriptor.getMappingsVersion().version(), equalTo(ContextEngineIndexService.CONTEXT_ENGINE_INDEX_VERSION));
    }

    public void testFeatureRegistersWithoutOverlap() {
        ContextEnginePlugin plugin = new ContextEnginePlugin();
        SystemIndices systemIndices = new SystemIndices(List.of(SystemIndices.Feature.fromSystemIndexPlugin(plugin, Settings.EMPTY)));
        assertThat(systemIndices.getFeatureNames().contains(ContextEnginePlugin.FEATURE_NAME), is(true));
        assertThat(systemIndices.isSystemIndex(ContextEngineIndexService.CONTEXT_ENGINE_CONCRETE_INDEX_NAME), is(true));
    }
}
