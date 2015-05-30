/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.config;

import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.channel.SSLEngineFactory;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.handler.AsyncHandler;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyServerSelector;
import org.asynchttpclient.util.ProxyUtils;

/**
 * Configuration class to use with a {@link AsyncHttpClient}. System property
 * can be also used to configure this object default behavior by doing:
 * <p/>
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.nameOfTheProperty
 */
public class AsyncHttpClientConfig {

    public final static String AHC_VERSION;

    static {
        InputStream is = null;
        Properties prop = new Properties();
        try {
            is = AsyncHttpClientConfig.class.getResourceAsStream("/ahc-version.properties");
            prop.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {

                }
            }
        }
        AHC_VERSION = prop.getProperty("ahc.version", "UNKNOWN");
    }

    protected int connectTimeout;

    protected int maxConnections;
    protected int maxConnectionsPerHost;

    protected int requestTimeout;
    protected int readTimeout;
    protected int webSocketTimeout;

    protected boolean allowPoolingConnections;
    protected boolean allowPoolingSslConnections;
    protected int pooledConnectionIdleTimeout;
    protected int connectionTTL;

    protected SSLContext sslContext;
    protected boolean acceptAnyCertificate;

    protected boolean followRedirect;
    protected int maxRedirects;
    protected boolean strict302Handling;

    protected ProxyServerSelector proxyServerSelector;

    protected boolean compressionEnforced;
    protected String userAgent;
    protected ExecutorService applicationThreadPool;
    protected Realm realm;
    protected List<RequestFilter> requestFilters;
    protected List<ResponseFilter> responseFilters;
    protected List<IOExceptionFilter> ioExceptionFilters;
    protected int maxRequestRetry;
    protected boolean disableUrlEncodingForBoundRequests;
    protected int ioThreadMultiplier;
    protected String[] enabledProtocols;
    protected String[] enabledCipherSuites;
    protected Integer sslSessionCacheSize;
    protected Integer sslSessionTimeout;
    protected int httpClientCodecMaxInitialLineLength = 4096;
    protected int httpClientCodecMaxHeaderSize = 8192;
    protected int httpClientCodecMaxChunkSize = 8192;
    protected boolean disableZeroCopy;
    protected long handshakeTimeout = 10000L;
    protected SSLEngineFactory sslEngineFactory;
    protected int chunkedFileChunkSize = 8192;
    protected int webSocketMaxBufferSize = 128000000;
    protected int webSocketMaxFrameSize = 10 * 1024;
    protected boolean keepEncodingHeader = false;
    protected AsyncHttpProviderConfig<?, ?> providerConfig;

    protected AsyncHttpClientConfig() {
    }

    private AsyncHttpClientConfig(int connectTimeout,//
            int maxConnections,//
            int maxConnectionsPerHost,//
            int requestTimeout,//
            int readTimeout,//
            int webSocketTimeout,//
            boolean allowPoolingConnection,//
            boolean allowSslConnectionPool,//
            int idleConnectionInPoolTimeout,//
            int maxConnectionLifeTime,//
            SSLContext sslContext, //
            boolean acceptAnyCertificate, //
            boolean followRedirect, //
            int maxRedirects, //
            boolean strict302Handling, //
            ExecutorService applicationThreadPool,//
            ProxyServerSelector proxyServerSelector, //
            boolean compressionEnforced, //
            String userAgent,//
            Realm realm,//
            List<RequestFilter> requestFilters,//
            List<ResponseFilter> responseFilters,//
            List<IOExceptionFilter> ioExceptionFilters,//
            int maxRequestRetry, //
            boolean disableUrlEncodingForBoundRequests, //
            int ioThreadMultiplier, //
            String[] enabledProtocols,//
            String[] enabledCipherSuites,//
            Integer sslSessionCacheSize,//
            Integer sslSessionTimeout,//
            int httpClientCodecMaxInitialLineLength,//
            int httpClientCodecMaxHeaderSize,//
            int httpClientCodecMaxChunkSize,//
            boolean disableZeroCopy,//
            long handshakeTimeout,//
            SSLEngineFactory sslEngineFactory,//
            int chunkedFileChunkSize,//
            int webSocketMaxBufferSize,//
            int webSocketMaxFrameSize,//
            boolean keepEncodingHeader,//
            AsyncHttpProviderConfig<?, ?> providerConfig) {

        this.connectTimeout = connectTimeout;
        this.maxConnections = maxConnections;
        this.maxConnectionsPerHost = maxConnectionsPerHost;
        this.requestTimeout = requestTimeout;
        this.readTimeout = readTimeout;
        this.webSocketTimeout = webSocketTimeout;
        this.allowPoolingConnections = allowPoolingConnection;
        this.allowPoolingSslConnections = allowSslConnectionPool;
        this.pooledConnectionIdleTimeout = idleConnectionInPoolTimeout;
        this.connectionTTL = maxConnectionLifeTime;
        this.sslContext = sslContext;
        this.acceptAnyCertificate = acceptAnyCertificate;
        this.followRedirect = followRedirect;
        this.maxRedirects = maxRedirects;
        this.strict302Handling = strict302Handling;
        this.proxyServerSelector = proxyServerSelector;
        this.compressionEnforced = compressionEnforced;
        this.userAgent = userAgent;
        this.applicationThreadPool = applicationThreadPool == null ? Executors.newCachedThreadPool() : applicationThreadPool;
        this.realm = realm;
        this.requestFilters = requestFilters;
        this.responseFilters = responseFilters;
        this.ioExceptionFilters = ioExceptionFilters;
        this.maxRequestRetry = maxRequestRetry;
        this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
        this.ioThreadMultiplier = ioThreadMultiplier;
        this.enabledProtocols = enabledProtocols;
        this.enabledCipherSuites = enabledCipherSuites;
        this.sslSessionCacheSize = sslSessionCacheSize;
        this.sslSessionTimeout = sslSessionTimeout;
        this.providerConfig = providerConfig;
        this.httpClientCodecMaxInitialLineLength = httpClientCodecMaxInitialLineLength;
        this.httpClientCodecMaxHeaderSize = httpClientCodecMaxHeaderSize;
        this.httpClientCodecMaxChunkSize = httpClientCodecMaxChunkSize;
        this.disableZeroCopy = disableZeroCopy;
        this.handshakeTimeout = handshakeTimeout;
        this.sslEngineFactory = sslEngineFactory;
        this.chunkedFileChunkSize = chunkedFileChunkSize;
        this.webSocketMaxBufferSize = webSocketMaxBufferSize;
        this.webSocketMaxFrameSize = webSocketMaxFrameSize;
        this.keepEncodingHeader = keepEncodingHeader;
    }

    /**
     * Return the maximum number of connections an {@link AsyncHttpClient} can
     * handle.
     *
     * @return the maximum number of connections an {@link AsyncHttpClient} can
     *         handle.
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Return the maximum number of connections per hosts an
     * {@link AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections per host an
     *         {@link AsyncHttpClient} can handle.
     */
    public int getMaxConnectionsPerHost() {
        return maxConnectionsPerHost;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} can
     * wait when connecting to a remote host
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} can
     *         wait when connecting to a remote host
     */
    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Return the maximum time, in milliseconds, a
     * {@link org.asynchttpclient.ws.WebSocket} may be idle before being timed
     * out.
     * 
     * @return the maximum time, in milliseconds, a
     *         {@link org.asynchttpclient.ws.WebSocket} may be idle before being
     *         timed out.
     */
    public int getWebSocketTimeout() {
        return webSocketTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} can
     * stay idle.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} can
     *         stay idle.
     */
    public int getReadTimeout() {
        return readTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} will
     * keep connection in pool.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} will
     *         keep connection in pool.
     */
    public int getPooledConnectionIdleTimeout() {
        return pooledConnectionIdleTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} waits
     * until the response is completed.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} waits
     *         until the response is completed.
     */
    public int getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Is HTTP redirect enabled
     *
     * @return true if enabled.
     */
    public boolean isFollowRedirect() {
        return followRedirect;
    }

    /**
     * Get the maximum number of HTTP redirect
     *
     * @return the maximum number of HTTP redirect
     */
    public int getMaxRedirects() {
        return maxRedirects;
    }

    /**
     * Is the {@link ConnectionsPool} support enabled.
     *
     * @return true if keep-alive is enabled
     */
    public boolean isAllowPoolingConnections() {
        return allowPoolingConnections;
    }

    /**
     * Return the USER_AGENT header value
     *
     * @return the USER_AGENT header value
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * Is HTTP compression enforced.
     *
     * @return true if compression is enforced
     */
    public boolean isCompressionEnforced() {
        return compressionEnforced;
    }

    /**
     * Return the {@link java.util.concurrent.ExecutorService} an
     * {@link AsyncHttpClient} use for handling asynchronous response.
     *
     * @return the {@link java.util.concurrent.ExecutorService} an
     *         {@link AsyncHttpClient} use for handling asynchronous response.
     *         If no {@link ExecutorService} has been explicitly provided, this
     *         method will return <code>null</code>
     */
    public ExecutorService executorService() {
        return applicationThreadPool;
    }

    /**
     * An instance of {@link ProxyServer} used by an {@link AsyncHttpClient}
     *
     * @return instance of {@link ProxyServer}
     */
    public ProxyServerSelector getProxyServerSelector() {
        return proxyServerSelector;
    }

    /**
     * Return an instance of {@link SSLContext} used for SSL connection.
     *
     * @return an instance of {@link SSLContext} used for SSL connection.
     */
    public SSLContext getSSLContext() {
        return sslContext;
    }

    /**
     * Return the {@link AsyncHttpProviderConfig}
     *
     * @return the {@link AsyncHttpProviderConfig}
     */
    public AsyncHttpProviderConfig<?, ?> getAsyncHttpProviderConfig() {
        return providerConfig;
    }

    /**
     * Return the current {@link Realm}
     *
     * @return the current {@link Realm}
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * @return <code>true</code> if {@link RequestFilter}s have been defined.
     *
     * @since 2.0.0
     */
    public boolean hasRequestFilters() {
        return !requestFilters.isEmpty();
    }

    /**
     * Return the list of {@link RequestFilter}
     *
     * @return Unmodifiable list of {@link ResponseFilter}
     */
    public List<RequestFilter> getRequestFilters() {
        return Collections.unmodifiableList(requestFilters);
    }

    /**
     * @return <code>true</code> if {@link ResponseFilter}s have been defined.
     * @since 2.0.0
     */
    public boolean hasResponseFilters() {
        return !responseFilters.isEmpty();
    }

    /**
     * Return the list of {@link ResponseFilter}
     *
     * @return Unmodifiable list of {@link ResponseFilter}
     */
    public List<ResponseFilter> getResponseFilters() {
        return Collections.unmodifiableList(responseFilters);
    }

    /**
     * Return the list of {@link java.io.IOException}
     *
     * @return Unmodifiable list of {@link java.io.IOException}
     */
    public List<IOExceptionFilter> getIOExceptionFilters() {
        return Collections.unmodifiableList(ioExceptionFilters);
    }

    /**
     * Return the number of time the library will retry when an
     * {@link java.io.IOException} is throw by the remote server
     *
     * @return the number of time the library will retry when an
     *         {@link java.io.IOException} is throw by the remote server
     */
    public int getMaxRequestRetry() {
        return maxRequestRetry;
    }

    /**
     * Return true is SSL connection polling is enabled. Default is true.
     *
     * @return true is enabled.
     */
    public boolean isAllowPoolingSslConnections() {
        return allowPoolingSslConnections;
    }

    /**
     * @return the disableUrlEncodingForBoundRequests
     */
    public boolean isDisableUrlEncodingForBoundRequests() {
        return disableUrlEncodingForBoundRequests;
    }

    /**
     * @return <code>true</code> if both the application and reaper thread pools
     *         haven't yet been shutdown.
     * @since 1.7.21
     */
    public boolean isValid() {
        boolean atpRunning = true;
        try {
            atpRunning = applicationThreadPool.isShutdown();
        } catch (Exception ignore) {
            // isShutdown() will thrown an exception in an EE7 environment
            // when using a ManagedExecutorService.
            // When this is the case, we assume it's running.
        }
        return atpRunning;
    }

    /**
     * @return number to multiply by availableProcessors() that will determine #
     *         of NioWorkers to use
     */
    public int getIoThreadMultiplier() {
        return ioThreadMultiplier;
    }

    /**
     * <p>
     * In the case of a POST/Redirect/Get scenario where the server uses a 302
     * for the redirect, should AHC respond to the redirect with a GET or
     * whatever the original method was. Unless configured otherwise, for a 302,
     * AHC, will use a GET for this case.
     * </p>
     *
     * @return <code>true</code> if string 302 handling is to be used, otherwise
     *         <code>false</code>.
     *
     * @since 1.7.2
     */
    public boolean isStrict302Handling() {
        return strict302Handling;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} will
     * keep connection in the pool, or -1 to keep connection while possible.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} will
     *         keep connection in the pool, or -1 to keep connection while
     *         possible.
     */
    public int getConnectionTTL() {
        return connectionTTL;
    }

    public boolean isAcceptAnyCertificate() {
        return acceptAnyCertificate;
    }

    /**
     * since 1.9.0
     */
    public String[] getEnabledProtocols() {
        return enabledProtocols;
    }

    /**
     * since 1.9.0
     */
    public String[] getEnabledCipherSuites() {
        return enabledCipherSuites;
    }

    /**
     * since 1.9.13
     */
    public Integer getSslSessionCacheSize() {
        return sslSessionCacheSize;
    }

    /**
     * since 1.9.13
     */
    public Integer getSslSessionTimeout() {
        return sslSessionTimeout;
    }

    public int getHttpClientCodecMaxInitialLineLength() {
        return httpClientCodecMaxInitialLineLength;
    }

    public int getHttpClientCodecMaxHeaderSize() {
        return httpClientCodecMaxHeaderSize;
    }

    public int getHttpClientCodecMaxChunkSize() {
        return httpClientCodecMaxChunkSize;
    }

    public boolean isDisableZeroCopy() {
        return disableZeroCopy;
    }

    public long getHandshakeTimeout() {
        return handshakeTimeout;
    }

    public SSLEngineFactory getSslEngineFactory() {
        return sslEngineFactory;
    }

    public int getChunkedFileChunkSize() {
        return chunkedFileChunkSize;
    }

    public int getWebSocketMaxBufferSize() {
        return webSocketMaxBufferSize;
    }

    public int getWebSocketMaxFrameSize() {
        return webSocketMaxFrameSize;
    }

    public boolean isKeepEncodingHeader() {
        return keepEncodingHeader;
    }

    /**
     * Builder for an {@link AsyncHttpClient}
     */
    public static class Builder {
        private int connectTimeout = defaultConnectTimeout();
        private int maxConnections = defaultMaxConnections();
        private int maxConnectionsPerHost = defaultMaxConnectionsPerHost();
        private int requestTimeout = defaultRequestTimeout();
        private int readTimeout = defaultReadTimeout();
        private int webSocketTimeout = defaultWebSocketTimeout();
        private boolean allowPoolingConnections = defaultAllowPoolingConnections();
        private boolean allowPoolingSslConnections = defaultAllowPoolingSslConnections();
        private int pooledConnectionIdleTimeout = defaultPooledConnectionIdleTimeout();
        private int connectionTTL = defaultConnectionTTL();
        private SSLContext sslContext;
        private boolean acceptAnyCertificate = defaultAcceptAnyCertificate();
        private boolean followRedirect = defaultFollowRedirect();
        private int maxRedirects = defaultMaxRedirects();
        private boolean strict302Handling = defaultStrict302Handling();
        private ProxyServerSelector proxyServerSelector = null;
        private boolean useProxySelector = defaultUseProxySelector();
        private boolean useProxyProperties = defaultUseProxyProperties();
        private boolean compressionEnforced = defaultCompressionEnforced();
        private String userAgent = defaultUserAgent();
        private ExecutorService applicationThreadPool;
        private Realm realm;
        private final List<RequestFilter> requestFilters = new LinkedList<>();
        private final List<ResponseFilter> responseFilters = new LinkedList<>();
        private final List<IOExceptionFilter> ioExceptionFilters = new LinkedList<>();
        private int maxRequestRetry = defaultMaxRequestRetry();
        private boolean disableUrlEncodingForBoundRequests = defaultDisableUrlEncodingForBoundRequests();
        private int ioThreadMultiplier = defaultIoThreadMultiplier();
        private String[] enabledProtocols;
        private String[] enabledCipherSuites;
        private Integer sslSessionCacheSize = defaultSslSessionCacheSize();
        private Integer sslSessionTimeout = defaultSslSessionTimeout();
        private int httpClientCodecMaxInitialLineLength = defaultHttpClientCodecMaxInitialLineLength();
        private int httpClientCodecMaxHeaderSize = defaultHttpClientCodecMaxHeaderSize();
        private int httpClientCodecMaxChunkSize = defaultHttpClientCodecMaxChunkSize();
        private boolean disableZeroCopy = defaultDisableZeroCopy();
        private long handshakeTimeout = defaultHandshakeTimeout();
        private SSLEngineFactory sslEngineFactory;
        private int chunkedFileChunkSize = defaultChunkedFileChunkSize();
        private int webSocketMaxBufferSize = defaultWebSocketMaxBufferSize();
        private int webSocketMaxFrameSize = defaultWebSocketMaxFrameSize();
        private boolean keepEncodingHeader = defaultKeepEncodingHeader();
        private AsyncHttpProviderConfig<?, ?> providerConfig;

        public Builder() {
        }

        /**
         * Set the maximum number of connections an {@link AsyncHttpClient} can
         * handle.
         *
         * @param maxConnections the maximum number of connections an
         *            {@link AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        /**
         * Set the maximum number of connections per (scheme, host, port) an
         * {@link AsyncHttpClient} can handle.
         *
         * @param maxConnectionsPerHost the maximum number of connections per
         *            (scheme, host, port) an {@link AsyncHttpClient} can
         *            handle.
         * @return a {@link Builder}
         */
        public Builder setMaxConnectionsPerHost(int maxConnectionsPerHost) {
            this.maxConnectionsPerHost = maxConnectionsPerHost;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} can
         * wait when connecting to a remote host
         *
         * @param connectTimeout the maximum time in millisecond an
         *            {@link AsyncHttpClient} can wait when connecting to a
         *            remote host
         * @return a {@link Builder}
         */
        public Builder setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an
         * {@link org.asynchttpclient.ws.WebSocket} can stay idle.
         *
         * @param webSocketTimeout the maximum time in millisecond an
         *            {@link org.asynchttpclient.ws.WebSocket} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setWebSocketTimeout(int webSocketTimeout) {
            this.webSocketTimeout = webSocketTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} can
         * stay idle.
         *
         * @param readTimeout the maximum time in millisecond an
         *            {@link AsyncHttpClient} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} will
         * keep connection idle in pool.
         *
         * @param pooledConnectionIdleTimeout the maximum time in millisecond an
         *            {@link AsyncHttpClient} will keep connection idle in pool.
         * @return a {@link Builder}
         */
        public Builder setPooledConnectionIdleTimeout(int pooledConnectionIdleTimeout) {
            this.pooledConnectionIdleTimeout = pooledConnectionIdleTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} waits
         * until the response is completed.
         *
         * @param requestTimeout the maximum time in millisecond an
         *            {@link AsyncHttpClient} waits until the response is
         *            completed.
         * @return a {@link Builder}
         */
        public Builder setRequestTimeout(int requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * Set to true to enable HTTP redirect
         *
         * @param redirectEnabled true if enabled.
         * @return a {@link Builder}
         */
        public Builder setFollowRedirect(boolean followRedirect) {
            this.followRedirect = followRedirect;
            return this;
        }

        /**
         * Set the maximum number of HTTP redirect
         *
         * @param maxRedirects the maximum number of HTTP redirect
         * @return a {@link Builder}
         */
        public Builder setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
            return this;
        }

        /**
         * Enforce HTTP compression.
         *
         * @param compressionEnabled true if compression is enforced
         * @return a {@link Builder}
         */
        public Builder setCompressionEnforced(boolean compressionEnforced) {
            this.compressionEnforced = compressionEnforced;
            return this;
        }

        /**
         * Set the USER_AGENT header value
         *
         * @param userAgent the USER_AGENT header value
         * @return a {@link Builder}
         */
        public Builder setUserAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /**
         * Set true if connection can be pooled by a {@link ConnectionsPool}.
         * Default is true.
         *
         * @param allowPoolingConnections true if connection can be pooled by a
         *            {@link ConnectionsPool}
         * @return a {@link Builder}
         */
        public Builder setAllowPoolingConnections(boolean allowPoolingConnections) {
            this.allowPoolingConnections = allowPoolingConnections;
            return this;
        }

        /**
         * Set the {@link java.util.concurrent.ExecutorService} an
         * {@link AsyncHttpClient} use for handling asynchronous response.
         *
         * @param applicationThreadPool the
         *            {@link java.util.concurrent.ExecutorService} an
         *            {@link AsyncHttpClient} use for handling asynchronous
         *            response.
         * @return a {@link Builder}
         */
        public Builder setExecutorService(ExecutorService applicationThreadPool) {
            this.applicationThreadPool = applicationThreadPool;
            return this;
        }

        /**
         * Set an instance of {@link ProxyServerSelector} used by an
         * {@link AsyncHttpClient}
         *
         * @param proxyServerSelector instance of {@link ProxyServerSelector}
         * @return a {@link Builder}
         */
        public Builder setProxyServerSelector(ProxyServerSelector proxyServerSelector) {
            this.proxyServerSelector = proxyServerSelector;
            return this;
        }

        /**
         * Set an instance of {@link ProxyServer} used by an
         * {@link AsyncHttpClient}
         *
         * @param proxyServer instance of {@link ProxyServer}
         * @return a {@link Builder}
         */
        public Builder setProxyServer(ProxyServer proxyServer) {
            this.proxyServerSelector = ProxyUtils.createProxyServerSelector(proxyServer);
            return this;
        }

        /**
         * Set the {@link SSLContext} for secure connection.
         *
         * @param sslContext the {@link SSLContext} for secure connection
         * @return a {@link Builder}
         */
        public Builder setSSLContext(final SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Set the {@link AsyncHttpProviderConfig}
         *
         * @param providerConfig the {@link AsyncHttpProviderConfig}
         * @return a {@link Builder}
         */
        public Builder setAsyncHttpClientProviderConfig(AsyncHttpProviderConfig<?, ?> providerConfig) {
            this.providerConfig = providerConfig;
            return this;
        }

        /**
         * Set the {@link Realm} that will be used for all requests.
         *
         * @param realm the {@link Realm}
         * @return a {@link Builder}
         */
        public Builder setRealm(Realm realm) {
            this.realm = realm;
            return this;
        }

        /**
         * Add an {@link org.asynchttpclient.filter.RequestFilter} that will be
         * invoked before {@link AsyncHttpClient#executeRequest(Request)}
         *
         * @param requestFilter {@link org.asynchttpclient.filter.RequestFilter}
         * @return this
         */
        public Builder addRequestFilter(RequestFilter requestFilter) {
            requestFilters.add(requestFilter);
            return this;
        }

        /**
         * Remove an {@link org.asynchttpclient.filter.RequestFilter} that will
         * be invoked before {@link AsyncHttpClient#executeRequest(Request)}
         *
         * @param requestFilter {@link org.asynchttpclient.filter.RequestFilter}
         * @return this
         */
        public Builder removeRequestFilter(RequestFilter requestFilter) {
            requestFilters.remove(requestFilter);
            return this;
        }

        /**
         * Add an {@link org.asynchttpclient.filter.ResponseFilter} that will be
         * invoked as soon as the response is received, and before
         * {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}.
         *
         * @param responseFilter an
         *            {@link org.asynchttpclient.filter.ResponseFilter}
         * @return this
         */
        public Builder addResponseFilter(ResponseFilter responseFilter) {
            responseFilters.add(responseFilter);
            return this;
        }

        /**
         * Remove an {@link org.asynchttpclient.filter.ResponseFilter} that will
         * be invoked as soon as the response is received, and before
         * {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}.
         *
         * @param responseFilter an
         *            {@link org.asynchttpclient.filter.ResponseFilter}
         * @return this
         */
        public Builder removeResponseFilter(ResponseFilter responseFilter) {
            responseFilters.remove(responseFilter);
            return this;
        }

        /**
         * Add an {@link org.asynchttpclient.filter.IOExceptionFilter} that will
         * be invoked when an {@link java.io.IOException} occurs during the
         * download/upload operations.
         *
         * @param ioExceptionFilter an
         *            {@link org.asynchttpclient.filter.ResponseFilter}
         * @return this
         */
        public Builder addIOExceptionFilter(IOExceptionFilter ioExceptionFilter) {
            ioExceptionFilters.add(ioExceptionFilter);
            return this;
        }

        /**
         * Remove an {@link org.asynchttpclient.filter.IOExceptionFilter} tthat
         * will be invoked when an {@link java.io.IOException} occurs during the
         * download/upload operations.
         *
         * @param ioExceptionFilter an
         *            {@link org.asynchttpclient.filter.ResponseFilter}
         * @return this
         */
        public Builder removeIOExceptionFilter(IOExceptionFilter ioExceptionFilter) {
            ioExceptionFilters.remove(ioExceptionFilter);
            return this;
        }

        /**
         * Set the number of times a request will be retried when an
         * {@link java.io.IOException} occurs because of a Network exception.
         *
         * @param maxRequestRetry the number of times a request will be retried
         * @return this
         */
        public Builder setMaxRequestRetry(int maxRequestRetry) {
            this.maxRequestRetry = maxRequestRetry;
            return this;
        }

        /**
         * Return true is if connections pooling is enabled.
         *
         * @param pooledConnectionIdleTimeout true if enabled
         * @return this
         */
        public Builder setAllowPoolingSslConnections(boolean allowPoolingSslConnections) {
            this.allowPoolingSslConnections = allowPoolingSslConnections;
            return this;
        }

        /**
         * Allows use unescaped URLs in requests useful for retrieving data from
         * broken sites
         *
         * @param disableUrlEncodingForBoundRequests
         * @return this
         */
        public Builder setDisableUrlEncodingForBoundRequests(boolean disableUrlEncodingForBoundRequests) {
            this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
            return this;
        }

        /**
         * Sets whether AHC should use the default JDK ProxySelector to select a
         * proxy server.
         * <p/>
         * If useProxySelector is set to <code>true</code> but
         * {@link #setProxyServer(ProxyServer)} was used to explicitly set a
         * proxy server, the latter is preferred.
         * <p/>
         * See http://docs.oracle.com/javase/7/docs/api/java/net/ProxySelector.
         * html
         */
        public Builder setUseProxySelector(boolean useProxySelector) {
            this.useProxySelector = useProxySelector;
            return this;
        }

        /**
         * Sets whether AHC should use the default http.proxy* system properties
         * to obtain proxy information. This differs from
         * {@link #setUseProxySelector(boolean)} in that AsyncHttpClient will
         * use its own logic to handle the system properties, potentially
         * supporting other protocols that the the JDK ProxySelector doesn't.
         * <p/>
         * If useProxyProperties is set to <code>true</code> but
         * {@link #setUseProxySelector(boolean)} was also set to true, the
         * latter is preferred.
         * <p/>
         * See
         * http://download.oracle.com/javase/1.4.2/docs/guide/net/properties.
         * html
         */
        public Builder setUseProxyProperties(boolean useProxyProperties) {
            this.useProxyProperties = useProxyProperties;
            return this;
        }

        public Builder setIOThreadMultiplier(int multiplier) {
            this.ioThreadMultiplier = multiplier;
            return this;
        }

        /**
         * Configures this AHC instance to be strict in it's handling of 302
         * redirects in a POST/Redirect/GET situation.
         *
         * @param strict302Handling strict handling
         *
         * @return this
         *
         * @since 1.7.2
         */
        public Builder setStrict302Handling(final boolean strict302Handling) {
            this.strict302Handling = strict302Handling;
            return this;
        }

        /**
         * Set the maximum time in millisecond connection can be added to the
         * pool for further reuse
         *
         * @param connectionTTL the maximum time in millisecond connection can
         *            be added to the pool for further reuse
         * @return a {@link Builder}
         */
        public Builder setConnectionTTL(int connectionTTL) {
            this.connectionTTL = connectionTTL;
            return this;
        }

        public Builder setAcceptAnyCertificate(boolean acceptAnyCertificate) {
            this.acceptAnyCertificate = acceptAnyCertificate;
            return this;
        }

        public Builder setEnabledProtocols(String[] enabledProtocols) {
            this.enabledProtocols = enabledProtocols;
            return this;
        }

        public Builder setEnabledCipherSuites(String[] enabledCipherSuites) {
            this.enabledCipherSuites = enabledCipherSuites;
            return this;
        }

        public Builder setSslSessionCacheSize(Integer sslSessionCacheSize) {
            this.sslSessionCacheSize = sslSessionCacheSize;
            return this;
        }

        public Builder setSslSessionTimeout(Integer sslSessionTimeout) {
            this.sslSessionTimeout = sslSessionTimeout;
            return this;
        }

        public Builder setHttpClientCodecMaxInitialLineLength(int httpClientCodecMaxInitialLineLength) {
            this.httpClientCodecMaxInitialLineLength = httpClientCodecMaxInitialLineLength;
            return this;
        }

        public Builder setHttpClientCodecMaxHeaderSize(int httpClientCodecMaxHeaderSize) {
            this.httpClientCodecMaxHeaderSize = httpClientCodecMaxHeaderSize;
            return this;
        }

        public Builder setHttpClientCodecMaxChunkSize(int httpClientCodecMaxChunkSize) {
            this.httpClientCodecMaxChunkSize = httpClientCodecMaxChunkSize;
            return this;
        }

        public Builder setDisableZeroCopy(boolean disableZeroCopy) {
            this.disableZeroCopy = disableZeroCopy;
            return this;
        }

        public Builder setHandshakeTimeout(long handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
            return this;
        }

        public Builder setSslEngineFactory(SSLEngineFactory sslEngineFactory) {
            this.sslEngineFactory = sslEngineFactory;
            return this;
        }

        public Builder setChunkedFileChunkSize(int chunkedFileChunkSize) {
            this.chunkedFileChunkSize = chunkedFileChunkSize;
            return this;
        }

        public Builder setWebSocketMaxBufferSize(int webSocketMaxBufferSize) {
            this.webSocketMaxBufferSize = webSocketMaxBufferSize;
            return this;
        }

        public Builder setWebSocketMaxFrameSize(int webSocketMaxFrameSize) {
            this.webSocketMaxFrameSize = webSocketMaxFrameSize;
            return this;
        }

        public Builder setKeepEncodingHeader(boolean keepEncodingHeader) {
            this.keepEncodingHeader = keepEncodingHeader;
            return this;
        }

        /**
         * Create a config builder with values taken from the given prototype
         * configuration.
         *
         * @param prototype the configuration to use as a prototype.
         */
        public Builder(AsyncHttpClientConfig prototype) {
            allowPoolingConnections = prototype.isAllowPoolingConnections();
            connectTimeout = prototype.getConnectTimeout();
            pooledConnectionIdleTimeout = prototype.getPooledConnectionIdleTimeout();
            readTimeout = prototype.getReadTimeout();
            maxConnectionsPerHost = prototype.getMaxConnectionsPerHost();
            connectionTTL = prototype.getConnectionTTL();
            maxRedirects = prototype.getMaxRedirects();
            maxConnections = prototype.getMaxConnections();
            proxyServerSelector = prototype.getProxyServerSelector();
            realm = prototype.getRealm();
            requestTimeout = prototype.getRequestTimeout();
            sslContext = prototype.getSSLContext();
            userAgent = prototype.getUserAgent();
            followRedirect = prototype.isFollowRedirect();
            compressionEnforced = prototype.isCompressionEnforced();
            applicationThreadPool = prototype.executorService();

            requestFilters.clear();
            responseFilters.clear();
            ioExceptionFilters.clear();

            requestFilters.addAll(prototype.getRequestFilters());
            responseFilters.addAll(prototype.getResponseFilters());
            ioExceptionFilters.addAll(prototype.getIOExceptionFilters());

            disableUrlEncodingForBoundRequests = prototype.isDisableUrlEncodingForBoundRequests();
            ioThreadMultiplier = prototype.getIoThreadMultiplier();
            maxRequestRetry = prototype.getMaxRequestRetry();
            allowPoolingSslConnections = prototype.isAllowPoolingConnections();
            strict302Handling = prototype.isStrict302Handling();
            acceptAnyCertificate = prototype.acceptAnyCertificate;
            enabledProtocols = prototype.enabledProtocols;
            enabledCipherSuites = prototype.enabledCipherSuites;
            sslSessionCacheSize = prototype.sslSessionCacheSize;
            sslSessionTimeout = prototype.sslSessionTimeout;
            httpClientCodecMaxInitialLineLength = prototype.httpClientCodecMaxInitialLineLength;
            httpClientCodecMaxHeaderSize = prototype.httpClientCodecMaxHeaderSize;
            httpClientCodecMaxChunkSize = prototype.httpClientCodecMaxChunkSize;
            disableZeroCopy = prototype.disableZeroCopy;
            handshakeTimeout = prototype.handshakeTimeout;
            sslEngineFactory = prototype.sslEngineFactory;
            chunkedFileChunkSize = prototype.chunkedFileChunkSize;
            webSocketMaxBufferSize = prototype.webSocketMaxBufferSize;
            webSocketMaxFrameSize = prototype.webSocketMaxFrameSize;
            keepEncodingHeader = prototype.keepEncodingHeader;

            providerConfig = prototype.getAsyncHttpProviderConfig();
        }

        /**
         * Build an {@link AsyncHttpClientConfig}
         *
         * @return an {@link AsyncHttpClientConfig}
         */
        public AsyncHttpClientConfig build() {

            if (proxyServerSelector == null && useProxySelector)
                proxyServerSelector = ProxyUtils.getJdkDefaultProxyServerSelector();

            if (proxyServerSelector == null && useProxyProperties)
                proxyServerSelector = ProxyUtils.createProxyServerSelector(System.getProperties());

            if (proxyServerSelector == null)
                proxyServerSelector = ProxyServerSelector.NO_PROXY_SELECTOR;

            return new AsyncHttpClientConfig(connectTimeout,//
                    maxConnections,//
                    maxConnectionsPerHost,//
                    requestTimeout,//
                    readTimeout,//
                    webSocketTimeout,//
                    allowPoolingConnections,//
                    allowPoolingSslConnections,//
                    pooledConnectionIdleTimeout,//
                    connectionTTL,//
                    sslContext, //
                    acceptAnyCertificate, //
                    followRedirect, //
                    maxRedirects, //
                    strict302Handling, //
                    applicationThreadPool, //
                    proxyServerSelector, //
                    compressionEnforced, //
                    userAgent,//
                    realm,//
                    requestFilters, //
                    responseFilters,//
                    ioExceptionFilters,//
                    maxRequestRetry, //
                    disableUrlEncodingForBoundRequests, //
                    ioThreadMultiplier, //
                    enabledProtocols, //
                    enabledCipherSuites, //
                    sslSessionCacheSize, //
                    sslSessionTimeout, //
                    httpClientCodecMaxInitialLineLength, //
                    httpClientCodecMaxHeaderSize, //
                    httpClientCodecMaxChunkSize, //
                    disableZeroCopy, //
                    handshakeTimeout, //
                    sslEngineFactory, //
                    chunkedFileChunkSize, //
                    webSocketMaxBufferSize, //
                    webSocketMaxFrameSize, //
                    keepEncodingHeader, //
                    providerConfig);
        }
    }
}
