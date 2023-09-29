/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.roleprovider;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.xpack.core.security.SecurityExtension;
import org.elasticsearch.xpack.core.security.authz.store.RoleRetrievalResult;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public class ApplicationSecurityExtension implements SecurityExtension {
    // @Override
    // public AuthorizationEngine getAuthorizationEngine(Settings settings) {
    // return new ApplicationAuthorizationEngine();
    // }

    @Override
    public List<BiConsumer<Set<String>, ActionListener<RoleRetrievalResult>>> getRolesProviders(SecurityComponents components) {
        return List.of(new ApplicationRoleProvider(components.client()));
    }
}
