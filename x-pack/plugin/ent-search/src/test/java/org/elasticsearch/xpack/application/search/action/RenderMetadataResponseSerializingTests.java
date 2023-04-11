/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.search.action;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.test.AbstractWireSerializingTestCase;

import java.io.IOException;

public class RenderMetadataResponseSerializingTests extends AbstractWireSerializingTestCase<RenderMetadataAction.Response> {

    @Override
    protected Writeable.Reader<RenderMetadataAction.Response> instanceReader() {
        return RenderMetadataAction.Response::new;
    }

    @Override
    protected RenderMetadataAction.Response createTestInstance() {
        return new RenderMetadataAction.Response(
            randomMap(0, 7, () -> new Tuple<>(randomAlphaOfLengthBetween(1, 10), randomAlphaOfLengthBetween(10, 25)))
        );
    }

    @Override
    protected RenderMetadataAction.Response mutateInstance(RenderMetadataAction.Response instance) throws IOException {
        return randomValueOtherThan(instance, this::createTestInstance);
    }
}
