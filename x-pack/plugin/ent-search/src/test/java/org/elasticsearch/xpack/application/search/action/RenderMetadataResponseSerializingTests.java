/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.search.action;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.AbstractWireSerializingTestCase;

import java.io.IOException;
import java.util.Collections;

import static org.elasticsearch.search.RandomSearchRequestGenerator.randomSearchSourceBuilder;

public class RenderMetadataResponseSerializingTests extends AbstractWireSerializingTestCase<RenderMetadataAction.Response> {

    @Override
    protected Writeable.Reader<RenderMetadataAction.Response> instanceReader() {
        return RenderMetadataAction.Response::new;
    }

    @Override
    protected RenderMetadataAction.Response createTestInstance() {
        return new RenderMetadataAction.Response(
            randomSearchSourceBuilder(() -> null, () -> null, () -> null, Collections::emptyList, () -> null, () -> null)
        );
    }

    @Override
    protected RenderMetadataAction.Response mutateInstance(RenderMetadataAction.Response instance) throws IOException {
        return randomValueOtherThan(instance, this::createTestInstance);
    }
}
