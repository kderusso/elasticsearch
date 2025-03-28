/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

/**
 * This package is responsible for managing reserved cluster state and handlers.
 * <p>
 * The purpose of reserved state is to update and persist various changes to cluster state,
 * generated from an information source (eg a file), and ensure that those changes
 * cannot then be overridden by anything other than that which owns those changes.
 * <p>
 * The cluster state changes themselves can be any modification to cluster state,
 * and classes performing those changes are pluggable.
 * <p>
 * There are several main classes in this package and sub-packages:
 * <ul>
 *     <li>
 *         {@link org.elasticsearch.reservedstate.service.FileSettingsService} reads information from a settings file, deserializes it,
 *         and passes it to {@code ReservedClusterStateService} to process
 *     </li>
 *     <li>
 *         {@link org.elasticsearch.reservedstate.service.ReservedClusterStateService} takes deserialized information
 *         from {@code FileSettingsService} and calls various registered handlers to update cluster state with the information.
 *     </li>
 *     <li>
 *         Implementations of {@link org.elasticsearch.reservedstate.ReservedClusterStateHandler} take specific parts
 *         of the deserialized information and updates cluster state accordingly.
 *     </li>
 *     <li>
 *         {@link org.elasticsearch.cluster.metadata.ReservedStateMetadata} contains information on reserved state applicability,
 *         and is used to filter and prevent changes to cluster state that would override reserved state.
 *     </li>
 *     <li>
 *         {@link org.elasticsearch.reservedstate.ActionWithReservedState} helps REST handlers to detect operations that would
 *         override reserved state updates, and deny the request.
 *     </li>
 * </ul>
 * <h2>Operation overview</h2>
 * There are several steps to managing reserved state. The basic sequence of operations is:
 * <ol>
 *     <li>
 *         One or more changes to settings files are made. This is detected by {@code FileSettingsService}, the changes are deserialized,
 *         and the deserialized XContent is passed to {@code ReservedClusterStateService}.
 *     </li>
 *     <li>
 *         {@code ReservedClusterStateService} checks the overall metadata of the update to determine if it needs to be applied at all.
 *         If it does, it determines which {@code ReservedClusterStateHandler} implementations need to be called, based on which
 *         keys exist in the update state, and passes them the relevant information to generate a new cluster state
 *         (first doing a trial run to see if the update is actually valid).
 *     </li>
 *     <li>
 *         Metadata on the update is stored in cluster state for each handler,
 *         alongside the arbitrary changes done to cluster state by the applicable handlers.
 *     </li>
 *     <li>
 *         If there is a REST counterpart to a reserved state handler, the REST implementation calls
 *         {@link org.elasticsearch.reservedstate.ActionWithReservedState#validateForReservedState} to determine if the REST call
 *         will modify any information generated by the corresponding reserved state handler. If it does, the REST handler
 *         denies the request.
 *     </li>
 * </ol>
 *
 * Importantly, each update to cluster state by a call to {@code ReservedStateService.process} is done <em>atomically</em> -
 * either all updates from all registered and applicable handlers are applied, or none are.
 *
 * <h2>Reserved state metadata keys</h2>
 * An important concept to understand is that <em>reserved state is only reserved through the cooperation of REST handlers</em>
 * (or any other part of the system that could modify cluster state). A reserved state handler implementation can modify <em>any</em>
 * aspect of cluster state - it is not up to the reserved state service to monitor that. It is therefore the responsibility of
 * <em>all other aspects of the system</em> that could potentially modify that same state to cooperate with the handler implementation
 * to block conflicting updates before they happen.
 * <p>
 * To help with this, a handler returns a set of arbitrary string keys alongside the updated cluster state, and these keys are stored
 * in the reserved state metadata for that handler. No meaning is ascribed to those keys by the reserved state infrastructure,
 * but it is expected that they represent or tag the cluster state changes in some meaningful way to that handler.
 * Any REST handlers that could modify the same state needs to check if it is going to modify state corresponding
 * to reserved metadata keys. If the key corresponding to the change it is going to make is present in the reserved state metadata,
 * the request should be denied.
 * <p>
 * For example, if there is a reserved state handler to set index templates, a file setting could create index templates {@code IT_1}
 * and {@code IT_2}. As well as adding those templates to the set of templates already present in the cluster, the reserved state handler
 * will set {@code [IT_1, IT_2]} as its reserved state metadata keys.
 * <p>
 * Later, if there is a REST request to modify {@code IT_1} or {@code IT_2}, the REST handler should check those strings against
 * the reserved metadata keys for the index template handler. As those keys are reserved, all requests to modify them via REST
 * should be denied.
 *
 * <h2>Project metadata handlers</h2>
 * There are two types of reserved state handlers - those that modify {@link org.elasticsearch.cluster.ClusterState} as a whole,
 * and those that modify {@link org.elasticsearch.cluster.metadata.ProjectMetadata}, denoted by the {@code S} type parameter.
 * Data for project-specific handlers can be specified in project-specific settings files,
 * data for cluster state handlers can only be specified in the cluster settings file.
 * <p>
 * If a project-specific handler is specified in the cluster settings file, then that handler is used to modify
 * the <em>default project</em> (which in most Elasticsearch clusters, is the <em>only</em> project in the system).
 * Reserved state metadata is stored in the context of the <em>source</em> of the information (so, cluster-wide if it's from
 * the cluster settings file, or in the {@code ProjectMetadata} if it's from a project settings file,
 * regardless of the handler type used to process it).
 *
 * <h2>Reserved state update details</h2>
 *
 * <h3>Reserved state namespace</h3>
 * Every reserved state handler has a <em>namespace</em> that it operates under. This is used to scope all handlers and metadata
 * stored in cluster state (although every namespace is checked for conflicts
 * by {@link org.elasticsearch.reservedstate.ActionWithReservedState}).
 * <p>
 * There is currently only one namespace defined by Elasticsearch itself, {@code file_settings}
 * defined by {@link org.elasticsearch.reservedstate.service.FileSettingsService#NAMESPACE}. Other namespaces may be defined
 * by plugins and modules.
 *
 * <h3>Reserved state version and compatibility</h3>
 * Every reserved state namespace also has a <em>version</em> associated with it. This is a simple integer,
 * that should be incremented whenever a new change should be applied (eg a new version of the settings file is written).
 * This is used to de-duplicate multiple calls to {@code process}, and to handle races that could occur between updates;
 * to determine if the changes should actually result in modifications to cluster state, or if the cluster state already has those changes
 * if the stored metadata version is greater than the version of the update.
 *
 * <h3>Handler ordering</h3>
 * There may be a  dependency between the execution of multiple handlers, for example if one handler requires structures to exist
 * that are only created by another handler. This relationship can be represented by overriding the
 * {@link org.elasticsearch.reservedstate.ReservedClusterStateHandler#dependencies} and
 * {@link org.elasticsearch.reservedstate.ReservedClusterStateHandler#optionalDependencies} methods, to specify other handlers
 * that must be registered and be run before this one, and ones that should be run before this one
 * only if they are registered with the reserved state service.
 *
 * <h3>Trial runs and errors</h3>
 * If invalid data is given to a REST endpoint, the HTTP response can indicate the problem and that the request was denied.
 * No such response mechanism exists for information written to files. Furthermore, there is no opportunity to test changes;
 * if a settings file causes invalid updates to cluster state, or a handler to throw exceptions, then there is also no way
 * to roll back. To solve this, there is a space in the metadata for each reserved state namespace to store error information,
 * which can be seen in a dump of cluster state.
 * <p>
 * Before reserved state handlers update the 'real' cluster state, a trial run is performed on whatever the current cluster state
 * is at the time. If an exception is thrown at any point, or while deserializing update information, then the reserved state
 * update is not applied. Instead the error metadata for that namespace is set in cluster state, and the cluster state
 * is left as-is. If a subsequent update succeeds (ie the file data is corrected), then the error metadata is cleared.
 * <p>
 * There is always a small risk that the trial run will succeed, but applying the updates to the real cluster state fails,
 * due to cluster state changing in the meantime, or a transient error in a handler.
 * In that case, the error will be logged and reported just like any other asynchronous cluster update - but reserved state
 * error metadata won't be written.
 */
package org.elasticsearch.reservedstate;
