/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cloud.gce;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;

import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;

public class GceMetadataService extends AbstractLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(GceMetadataService.class);

    // Forcing Google Token API URL as set in GCE SDK to
    // http://metadata/computeMetadata/v1/instance/service-accounts/default/token
    // See https://developers.google.com/compute/docs/metadata#metadataserver
    // all settings just used for testing - not registered by default
    public static final Setting<String> GCE_HOST = Setting.simpleString(
        "cloud.gce.host",
        "http://metadata.google.internal",
        Setting.Property.NodeScope
    );

    private final Settings settings;

    /** Global instance of the HTTP transport. */
    private HttpTransport gceHttpTransport;

    public GceMetadataService(Settings settings) {
        this.settings = settings;
    }

    protected synchronized HttpTransport getGceHttpTransport() throws GeneralSecurityException, IOException {
        if (gceHttpTransport == null) {
            gceHttpTransport = GoogleNetHttpTransport.newTrustedTransport();
        }
        return gceHttpTransport;
    }

    public String metadata(String metadataPath) throws IOException, URISyntaxException {
        // Forcing Google Token API URL as set in GCE SDK to
        // http://metadata/computeMetadata/v1/instance/service-accounts/default/token
        // See https://developers.google.com/compute/docs/metadata#metadataserver
        final URI urlMetadataNetwork = new URI(GCE_HOST.get(settings)).resolve("/computeMetadata/v1/instance/").resolve(metadataPath);
        logger.debug("get metadata from [{}]", urlMetadataNetwork);
        HttpHeaders headers;
        try {
            // hack around code messiness in GCE code
            // TODO: get this fixed
            headers = new HttpHeaders();
            GenericUrl genericUrl = new GenericUrl(urlMetadataNetwork);

            // This is needed to query meta data: https://cloud.google.com/compute/docs/metadata
            headers.put("Metadata-Flavor", "Google");
            HttpResponse response = getGceHttpTransport().createRequestFactory().buildGetRequest(genericUrl).setHeaders(headers).execute();
            String metadata = response.parseAsString();
            logger.debug("metadata found [{}]", metadata);
            return metadata;
        } catch (Exception e) {
            throw new IOException("failed to fetch metadata from [" + urlMetadataNetwork + "]", e);
        }
    }

    @Override
    protected void doStart() {

    }

    @Override
    protected void doStop() {
        if (gceHttpTransport != null) {
            try {
                gceHttpTransport.shutdown();
            } catch (IOException e) {
                logger.warn("unable to shutdown GCE Http Transport", e);
            }
            gceHttpTransport = null;
        }
    }

    @Override
    protected void doClose() {

    }
}
