/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.contextengine.memory.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.contextengine.ContextEnginePlugin;
import org.elasticsearch.xpack.contextengine.memory.SessionMemoryIndexService;

public class TransportRememberAction extends HandledTransportAction<RememberAction.Request, RememberAction.Response> {

    private final SessionMemoryIndexService sessionMemoryIndexService;
    private final String redactionInferenceId;

    @Inject
    public TransportRememberAction(Settings settings, TransportService transportService, ActionFilters actionFilters, Client client) {
        super(RememberAction.NAME, transportService, actionFilters, RememberAction.Request::new, EsExecutors.DIRECT_EXECUTOR_SERVICE);
        this.sessionMemoryIndexService = new SessionMemoryIndexService(client);
        this.redactionInferenceId = ContextEnginePlugin.SESSION_MEMORY_REDACTION_INFERENCE_ID.get(settings);
    }

    @Override
    protected void doExecute(Task task, RememberAction.Request request, ActionListener<RememberAction.Response> listener) {
        sessionMemoryIndexService.remember(request.memory(), redactionInferenceId, listener.map(RememberAction.Response::new));
    }
}
