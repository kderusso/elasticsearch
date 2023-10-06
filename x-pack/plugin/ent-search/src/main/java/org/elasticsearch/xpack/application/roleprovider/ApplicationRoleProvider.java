/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.roleprovider;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.core.security.authz.store.RoleRetrievalResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

import static org.elasticsearch.xpack.core.ClientHelper.ENT_SEARCH_ORIGIN;

public class ApplicationRoleProvider implements BiConsumer<Set<String>, ActionListener<RoleRetrievalResult>> {
    public static final String APPLICATION_ROLE_PROVIDER_CONFIG_INDEX = ".application-role-provider-config";

    private final Client client;

    public ApplicationRoleProvider(Client client) {
        this.client = new OriginSettingClient(client, ENT_SEARCH_ORIGIN);
    }

    @Override
    public void accept(Set<String> roles, ActionListener<RoleRetrievalResult> listener) {
        Set<RoleDescriptor> roleDescriptors = new HashSet<>();

        for (String role : roles) {
            if (role.startsWith("managed")) {
                ApplicationRoleConfiguration roleConfig = ApplicationRoleConfiguration.getConfiguration(client, role);
                if (roleConfig == null) {
                    continue;
                }

                List<String> queries = ApplicationRoleConfiguration.getRoleDescriptorQuery(roleConfig, role, client);
                if (queries == null) {
                    continue;
                }

                for (String query : queries) {
                    if (query == null) {
                        continue;
                    }

                    roleDescriptors.add(
                        new RoleDescriptor(
                            role,
                            new String[] { "all" },
                            new RoleDescriptor.IndicesPrivileges[] {
                                RoleDescriptor.IndicesPrivileges.builder()
                                    .privileges("read")
                                    .indices(roleConfig.targetIndex)
                                    .grantedFields("*")
                                    .query(query)
                                    .build() },
                            null
                        )
                    );
                }
            }
        }
        listener.onResponse(RoleRetrievalResult.success(roleDescriptors));
    }

    public static class ApplicationRoleConfiguration {
        private final String targetIndex;
        private final String rolesIndex;
        private final String lookupField;

        public ApplicationRoleConfiguration(String targetIndex, String userIndex, String lookupField) {
            this.targetIndex = targetIndex;
            this.rolesIndex = userIndex;
            this.lookupField = lookupField;
        }

        public static @Nullable ApplicationRoleConfiguration getConfiguration(Client client, String role) {
            String id = role.split(":")[1];

            GetRequest getRequest = new GetRequest(APPLICATION_ROLE_PROVIDER_CONFIG_INDEX).id(id);
            GetResponse response;
            try {
                response = client.get(getRequest).get();
            } catch (InterruptedException e) {
                return null;
            } catch (ExecutionException e) {
                return null;
            }
            Map<String, Object> source = response.getSource();
            String targetIndex = source.get("target_index").toString();
            String rolesIndex = source.get("roles_index").toString();
            String lookupField = source.get("lookup_field").toString();

            return new ApplicationRoleConfiguration(targetIndex, rolesIndex, lookupField);
        }

        @SuppressWarnings({ "unchecked" })
        public static List<String> getRoleDescriptorQuery(ApplicationRoleConfiguration config, String role, Client client) {
            String lookupValue = role.split(":")[2];
            TermQueryBuilder termQueryBuilder = new TermQueryBuilder(config.lookupField, lookupValue);
            SearchSourceBuilder source = new SearchSourceBuilder().query(termQueryBuilder);

            SearchRequest searchRequest = new SearchRequest(config.rolesIndex).source(source);
            SearchResponse searchResponse;
            try {
                searchResponse = client.search(searchRequest).get();
            } catch (InterruptedException e) {
                return null;
            } catch (ExecutionException e) {
                return null;
            }

            List<String> roleDescriptorQueries = new ArrayList<>();
            for (SearchHit hit : searchResponse.getHits()) {
                roleDescriptorQueries.add(hit.getSourceAsMap().get("query").toString());
            }
            return roleDescriptorQueries;
        }
    }
}
