/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.repositories.gcs;

import com.google.api.client.googleapis.GoogleUtils;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.DefaultConnectionFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.LazyInitializable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;

public class GoogleCloudStorageService extends AbstractComponent {

    /**
     * Dictionary of client instances. Client instances are built lazily from the
     * latest settings.
     */
    private final AtomicReference<Map<String, LazyInitializable<Storage, IOException>>> clientsCache = new AtomicReference<>(emptyMap());

    /**
     * Overrides application name for client creation.
     */
    @Deprecated
    private String applicationName;
    /**
     * Overrides connect timeout for client creation.
     */
    @Deprecated
    private TimeValue connectTimeout;
    /**
     * Overrides read timeout for client creation.
     */
    @Deprecated
    private TimeValue readTimeout;

    public GoogleCloudStorageService(final Settings settings) {
        super(settings);
    }

    /**
     * Refreshes the client settings and clears the client cache. Subsequent calls to
     * {@code GoogleCloudStorageService#client} will return new clients constructed
     * using the parameter settings.
     *
     * @param clientsSettings the new settings used for building clients for subsequent requests
     */
    public synchronized void refreshAndClearCache(Map<String, GoogleCloudStorageClientSettings> clientsSettings) {
        // build the new lazy clients
        final MapBuilder<String, LazyInitializable<Storage, IOException>> newClientsCache = MapBuilder.newMapBuilder();
        for (final Map.Entry<String, GoogleCloudStorageClientSettings> entry : clientsSettings.entrySet()) {
            newClientsCache.put(entry.getKey(),
                    new LazyInitializable<Storage, IOException>(() -> createClient(entry.getKey(), entry.getValue())));
        }
        // make the new clients available
        final Map<String, LazyInitializable<Storage, IOException>> oldClientCache = clientsCache.getAndSet(newClientsCache.immutableMap());
        // release old clients
        oldClientCache.values().forEach(LazyInitializable::reset);
    }

    /**
     * Attempts to retrieve a client from the cache. If the client does not exist it
     * will be created from the latest settings and will populate the cache. The
     * returned instance should not be cached by the calling code. Instead, for each
     * use, the (possibly updated) instance should be requested by calling this
     * method.
     *
     * @param clientName name of the client settings used to create the client
     * @return a cached client storage instance that can be used to manage objects
     *         (blobs)
     */
    public Storage client(final String clientName) throws IOException {
        final LazyInitializable<Storage, IOException> lazyClient = clientsCache.get().get(clientName);
        if (lazyClient == null) {
            throw new IllegalArgumentException("Unknown client name [" + clientName + "]. Existing client configs: "
                    + Strings.collectionToDelimitedString(clientsCache.get().keySet(), ","));
        }
        return lazyClient.getOrCompute();
    }

    /**
     * Sets the value that overrides the application name while creating the client.
     * Normally the application name is the picked up from the GCS client settings in the
     * elasticsearch conf file. Set to <code>null</code> to not override.
     */
    @Deprecated
    public void setOverrideApplicationName(String applicationName) {
        this.applicationName = applicationName;
        // release any existing client
        clientsCache.get().values().forEach(LazyInitializable::reset);
    }

    /**
     * Sets the value that overrides the connect timeout while creating the client.
     * Normally the connect timeout is picket up from the GCS client settings in
     * the elasticsearch conf file. Set to <code>null</code> to not override.
     */
    @Deprecated
    public void setOverrideConnectTimeout(TimeValue connectTimeout) {
        this.connectTimeout = connectTimeout;
        // release any existing client
        clientsCache.get().values().forEach(LazyInitializable::reset);
    }

    /**
     * Sets the value that overrides the read timeout while creating the client.
     * Normally the read timeout is picked up from the GCS client settings in the
     * elasticsearch conf file. Set to <code>null</code> to not override.
     */
    @Deprecated
    public void setOverrideReadTimeout(TimeValue readTimeout) {
        this.readTimeout = readTimeout;
        // release any existing client
        clientsCache.get().values().forEach(LazyInitializable::reset);
    }

    /**
     * Creates a client that can be used to manage Google Cloud Storage objects. The client is thread-safe.
     *
     * @param clientName name of client settings to use, including secure settings
     * @param clientSettings name of client settings to use, including secure settings
     * @return a new client storage instance that can be used to manage objects
     *         (blobs)
     */
    private Storage createClient(final String clientName, final GoogleCloudStorageClientSettings clientSettings) throws IOException {
        logger.debug(() -> new ParameterizedMessage("creating GCS client with client_name [{}], endpoint [{}]", clientName,
                clientSettings.getHost()));
        final HttpTransport httpTransport = SocketAccess.doPrivilegedIOException(() -> createHttpTransport(clientSettings.getHost()));
        TimeValue connectTimeout = this.connectTimeout;
        if (connectTimeout == null) {
            connectTimeout = clientSettings.getConnectTimeout();
        }
        TimeValue readTimeout = this.readTimeout;
        if (readTimeout == null) {
            readTimeout = clientSettings.getReadTimeout();
        }
        final HttpTransportOptions httpTransportOptions = HttpTransportOptions.newBuilder()
                .setConnectTimeout(toTimeout(connectTimeout))
                .setReadTimeout(toTimeout(readTimeout))
                .setHttpTransportFactory(() -> httpTransport)
                .build();
        final StorageOptions.Builder storageOptionsBuilder = StorageOptions.newBuilder()
                .setTransportOptions(httpTransportOptions)
                .setHeaderProvider(() -> {
                    final MapBuilder<String, String> mapBuilder = MapBuilder.newMapBuilder();
                    String applicationName = this.applicationName;
                    if (false == Strings.hasLength(applicationName)) {
                        applicationName = clientSettings.getApplicationName();
                    }
                    if (Strings.hasLength(applicationName)) {
                        mapBuilder.put("user-agent", applicationName);
                    }
                    return mapBuilder.immutableMap();
                });
        if (Strings.hasLength(clientSettings.getHost())) {
            storageOptionsBuilder.setHost(clientSettings.getHost());
        }
        if (Strings.hasLength(clientSettings.getProjectId())) {
            storageOptionsBuilder.setProjectId(clientSettings.getProjectId());
        }
        if (clientSettings.getCredential() == null) {
            logger.warn("\"Application Default Credentials\" are not supported out of the box."
                    + " Additional file system permissions have to be granted to the plugin.");
        } else {
            ServiceAccountCredentials serviceAccountCredentials = clientSettings.getCredential();
            // override token server URI
            final URI tokenServerUri = clientSettings.getTokenUri();
            if (Strings.hasLength(tokenServerUri.toString())) {
                // Rebuild the service account credentials in order to use a custom Token url.
                // This is mostly used for testing purpose.
                serviceAccountCredentials = serviceAccountCredentials.toBuilder().setTokenServerUri(tokenServerUri).build();
            }
            storageOptionsBuilder.setCredentials(serviceAccountCredentials);
        }
        return storageOptionsBuilder.build().getService();
    }

    /**
     * Pins the TLS trust certificates and, more importantly, overrides connection
     * URLs in the case of a custom endpoint setting because some connections don't
     * fully honor this setting (bugs in the SDK). The default connection factory
     * opens a new connection for each request. This is required for the storage
     * instance to be thread-safe.
     **/
    private static HttpTransport createHttpTransport(final String endpoint) throws Exception {
        final NetHttpTransport.Builder builder = new NetHttpTransport.Builder();
        // requires java.lang.RuntimePermission "setFactory"
        builder.trustCertificates(GoogleUtils.getCertificateTrustStore());
        if (Strings.hasLength(endpoint)) {
            final URL endpointUrl = URI.create(endpoint).toURL();
            // it is crucial to open a connection for each URL (see {@code
            // DefaultConnectionFactory#openConnection}) instead of reusing connections,
            // because the storage instance has to be thread-safe as it is cached.
            builder.setConnectionFactory(new DefaultConnectionFactory() {
                @Override
                public HttpURLConnection openConnection(final URL originalUrl) throws IOException {
                    // test if the URL is built correctly, ie following the `host` setting
                    if (originalUrl.getHost().equals(endpointUrl.getHost()) && originalUrl.getPort() == endpointUrl.getPort()
                            && originalUrl.getProtocol().equals(endpointUrl.getProtocol())) {
                        return super.openConnection(originalUrl);
                    }
                    // override connection URLs because some don't follow the config. See
                    // https://github.com/GoogleCloudPlatform/google-cloud-java/issues/3254 and
                    // https://github.com/GoogleCloudPlatform/google-cloud-java/issues/3255
                    URI originalUri;
                    try {
                        originalUri = originalUrl.toURI();
                    } catch (final URISyntaxException e) {
                        throw new RuntimeException(e);
                    }
                    String overridePath = "/";
                    if (originalUri.getRawPath() != null) {
                        overridePath = originalUri.getRawPath();
                    }
                    if (originalUri.getRawQuery() != null) {
                        overridePath += "?" + originalUri.getRawQuery();
                    }
                    return super.openConnection(
                            new URL(endpointUrl.getProtocol(), endpointUrl.getHost(), endpointUrl.getPort(), overridePath));
                }
            });
        }
        return builder.build();
    }

    /**
     * Converts timeout values from the settings to a timeout value for the Google
     * Cloud SDK
     **/
    static Integer toTimeout(final TimeValue timeout) {
        // Null or zero in settings means the default timeout
        if (timeout == null || TimeValue.ZERO.equals(timeout)) {
            // negative value means using the default value
            return -1;
        }
        // -1 means infinite timeout
        if (TimeValue.MINUS_ONE.equals(timeout)) {
            // 0 is the infinite timeout expected by Google Cloud SDK
            return 0;
        }
        return Math.toIntExact(timeout.getMillis());
    }

}
