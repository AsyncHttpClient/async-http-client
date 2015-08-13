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
package com.ning.http.client;

import static com.ning.http.client.AsyncHttpClientConfigDefaults.*;

import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.RequestFilter;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.util.DefaultHostnameVerifier;
import com.ning.http.util.ProxyUtils;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Configuration class to use with a {@link AsyncHttpClient}. System property can be also used to configure this
 * object default behavior by doing:
 * <p/>
 * -Dcom.ning.http.client.AsyncHttpClientConfig.nameOfTheProperty
 */
public class AsyncHttpClientConfig {

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
    protected HostnameVerifier hostnameVerifier;
    protected boolean acceptAnyCertificate;

    protected boolean followRedirect;
    protected int maxRedirects;
    protected boolean strict302Handling;

    protected ProxyServerSelector proxyServerSelector;
    protected boolean useRelativeURIsWithConnectProxies;

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
    protected AsyncHttpProviderConfig<?, ?> providerConfig;

    protected AsyncHttpClientConfig() {
    }

    private AsyncHttpClientConfig(int connectTimeout,//
            int maxConnections,//
            int maxConnectionsPerHost,//
            int requestTimeout,//
            int readTimeout,//
            int webSocketIdleTimeout,//
            boolean allowPoolingConnection,//
            boolean allowSslConnectionPool,//
            int idleConnectionInPoolTimeout,//
            int maxConnectionLifeTime,//
            SSLContext sslContext, //
            HostnameVerifier hostnameVerifier,//
            boolean acceptAnyCertificate, //
            boolean followRedirect, //
            int maxRedirects, //
            boolean strict302Handling, //
            ExecutorService applicationThreadPool,//
            ProxyServerSelector proxyServerSelector, //
            boolean useRelativeURIsWithConnectProxies, //
            boolean compressionEnforced, //
            String userAgent,//
            Realm realm,//
            List<RequestFilter> requestFilters,//
            List<ResponseFilter> responseFilters,//
            List<IOExceptionFilter> ioExceptionFilters,//
            int maxRequestRetry, //
            boolean disableUrlEncodingForBoundedRequests, //
            int ioThreadMultiplier, //
            String[] enabledProtocols,//
            String[] enabledCipherSuites,//
            Integer sslSessionCacheSize,//
            Integer sslSessionTimeout,//
            AsyncHttpProviderConfig<?, ?> providerConfig) {

        this.connectTimeout = connectTimeout;
        this.maxConnections = maxConnections;
        this.maxConnectionsPerHost = maxConnectionsPerHost;
        this.requestTimeout = requestTimeout;
        this.readTimeout = readTimeout;
        this.webSocketTimeout = webSocketIdleTimeout;
        this.allowPoolingConnections = allowPoolingConnection;
        this.allowPoolingSslConnections = allowSslConnectionPool;
        this.pooledConnectionIdleTimeout = idleConnectionInPoolTimeout;
        this.connectionTTL = maxConnectionLifeTime;
        this.sslContext = sslContext;
        this.hostnameVerifier = hostnameVerifier;
        this.acceptAnyCertificate = acceptAnyCertificate;
        this.followRedirect = followRedirect;
        this.maxRedirects = maxRedirects;
        this.strict302Handling = strict302Handling;
        this.proxyServerSelector = proxyServerSelector;
        this.useRelativeURIsWithConnectProxies = useRelativeURIsWithConnectProxies;
        this.compressionEnforced = compressionEnforced;
        this.userAgent = userAgent;
        this.applicationThreadPool = applicationThreadPool == null ? Executors.newCachedThreadPool() : applicationThreadPool;
        this.realm = realm;
        this.requestFilters = requestFilters;
        this.responseFilters = responseFilters;
        this.ioExceptionFilters = ioExceptionFilters;
        this.maxRequestRetry = maxRequestRetry;
        this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundedRequests;
        this.ioThreadMultiplier = ioThreadMultiplier;
        this.enabledProtocols = enabledProtocols;
        this.enabledCipherSuites = enabledCipherSuites;
        this.sslSessionCacheSize = sslSessionCacheSize;
        this.sslSessionTimeout = sslSessionTimeout;
        this.providerConfig = providerConfig;
    }

    /**
     * Return the maximum number of connections an {@link com.ning.http.client.AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections an {@link com.ning.http.client.AsyncHttpClient} can handle.
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Return the maximum number of connections per hosts an {@link com.ning.http.client.AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections per host an {@link com.ning.http.client.AsyncHttpClient} can handle.
     */
    public int getMaxConnectionsPerHost() {
        return maxConnectionsPerHost;
    }

    /**
     * Return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can wait when connecting to a remote host
     *
     * @return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can wait when connecting to a remote host
     */
    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Return the maximum time, in milliseconds, a {@link com.ning.http.client.ws.WebSocket} may be idle before being timed out.
     * @return the maximum time, in milliseconds, a {@link com.ning.http.client.ws.WebSocket} may be idle before being timed out.
     */
    public int getWebSocketTimeout() {
        return webSocketTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can stay idle.
     *
     * @return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can stay idle.
     */
    public int getReadTimeout() {
        return readTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} will keep connection
     * in pool.
     *
     * @return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} will keep connection
     *         in pool.
     */
    public int getPooledConnectionIdleTimeout() {
        return pooledConnectionIdleTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} waits until the response is completed.
     *
     * @return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} waits until the response is completed.
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
     * Is pooling connections enabled.
     *
     * @return if polling connections is enabled
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
     * Return the {@link java.util.concurrent.ExecutorService} an {@link AsyncHttpClient} use for handling
     * asynchronous response.
     *
     * @return the {@link java.util.concurrent.ExecutorService} an {@link AsyncHttpClient} use for handling
     *         asynchronous response.
     */
    public ExecutorService executorService() {
        return applicationThreadPool;
    }

    /**
     * An instance of {@link com.ning.http.client.ProxyServer} used by an {@link AsyncHttpClient}
     *
     * @return instance of {@link com.ning.http.client.ProxyServer}
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
     * Return the {@link com.ning.http.client.AsyncHttpProviderConfig}
     *
     * @return the {@link com.ning.http.client.AsyncHttpProviderConfig}
     */
    public AsyncHttpProviderConfig<?, ?> getAsyncHttpProviderConfig() {
        return providerConfig;
    }

    /**
     * Return the current {@link Realm}}
     *
     * @return the current {@link Realm}}
     */
    public Realm getRealm() {
        return realm;
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
     * Return the number of time the library will retry when an {@link java.io.IOException} is throw by the remote server
     *
     * @return the number of time the library will retry when an {@link java.io.IOException} is throw by the remote server
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
     * @return the disableUrlEncodingForBoundedRequests
     */
    public boolean isDisableUrlEncodingForBoundedRequests() {
        return disableUrlEncodingForBoundRequests;
    }

    /**
     * @return <code>true</code> if both the application and reaper thread pools
     *  haven't yet been shutdown.
     *
     * @since 1.7.21
     */
    public boolean isValid() {
        try {
            return applicationThreadPool.isShutdown();
        } catch (Exception ignore) {
            // isShutdown() will thrown an exception in an EE7 environment
            // when using a ManagedExecutorService.
            // When this is the case, we assume it's running.
            return true;
        }
    }

    /**
     * Return the {@link HostnameVerifier}
     *
     * @return the {@link HostnameVerifier}
     */
    public HostnameVerifier getHostnameVerifier() {
        if (hostnameVerifier == null && !acceptAnyCertificate) {
            synchronized(this) {
                if (hostnameVerifier == null)
                    hostnameVerifier = new DefaultHostnameVerifier();
            }
        }
        return hostnameVerifier;
    }

    /**
     * @return number to multiply by availableProcessors() that will determine # of NioWorkers to use
     */
    public int getIoThreadMultiplier() {
        return ioThreadMultiplier;
    }

    /**
     * <p>
     * In the case of a POST/Redirect/Get scenario where the server uses a 302
     * for the redirect, should AHC respond to the redirect with a GET or
     * whatever the original method was.  Unless configured otherwise,
     * for a 302, AHC, will use a GET for this case.
     * </p>
     *
     * @return <code>true</code> if string 302 handling is to be used,
     *  otherwise <code>false</code>.
     *
     * @since 1.7.2
     */
    public boolean isStrict302Handling() {
        return strict302Handling;
    }

    /**
     * @return<code>true</code> if AHC should use relative URIs instead of absolute ones when talking with a SSL proxy
     * or WebSocket proxy, otherwise <code>false</code>.
     *  
     *  @since 1.8.13
     */
    public boolean isUseRelativeURIsWithConnectProxies() {
        return useRelativeURIsWithConnectProxies;
    }

    /**
     * Return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} will keep connection in the pool, or -1 to keep connection while possible.
     *
     * @return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} will keep connection in the pool, or -1 to keep connection while possible.
     */
    public int getConnectionTTL() {
        return connectionTTL;
    }

    /**
     * since 1.9.0
     */
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
        private HostnameVerifier hostnameVerifier;
        private boolean acceptAnyCertificate = defaultAcceptAnyCertificate();
        private boolean followRedirect = defaultFollowRedirect();
        private int maxRedirects = defaultMaxRedirects();
        private boolean strict302Handling = defaultStrict302Handling();
        private ProxyServerSelector proxyServerSelector = null;
        private boolean useProxySelector = defaultUseProxySelector();
        private boolean useProxyProperties = defaultUseProxyProperties();
        private boolean useRelativeURIsWithConnectProxies = defaultUseRelativeURIsWithConnectProxies();
        private boolean compressionEnforced = defaultCompressionEnforced();
        private String userAgent = defaultUserAgent();
        private ExecutorService applicationThreadPool;
        private Realm realm;
        private final List<RequestFilter> requestFilters = new LinkedList<>();
        private final List<ResponseFilter> responseFilters = new LinkedList<>();
        private final List<IOExceptionFilter> ioExceptionFilters = new LinkedList<>();
        private int maxRequestRetry = defaultMaxRequestRetry();
        private boolean disableUrlEncodingForBoundedRequests = defaultDisableUrlEncodingForBoundRequests();
        private int ioThreadMultiplier = defaultIoThreadMultiplier();
        private String[] enabledProtocols = defaultEnabledProtocols();
        private String[] enabledCipherSuites;
        private Integer sslSessionCacheSize = defaultSslSessionCacheSize();
        private Integer sslSessionTimeout = defaultSslSessionTimeout();
        private AsyncHttpProviderConfig<?, ?> providerConfig;

        public Builder() {
        }

        /**
         * Set the maximum number of connections an {@link com.ning.http.client.AsyncHttpClient} can handle.
         *
         * @param maxConnections the maximum number of connections an {@link com.ning.http.client.AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        /**
         * Set the maximum number of connections per (scheme, host, port) an {@link com.ning.http.client.AsyncHttpClient} can handle.
         *
         * @param maxConnectionsPerHost the maximum number of connections per (scheme, host, port) an {@link com.ning.http.client.AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaxConnectionsPerHost(int maxConnectionsPerHost) {
            this.maxConnectionsPerHost = maxConnectionsPerHost;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can wait when connecting to a remote host
         *
         * @param connectTimeOut the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can wait when connecting to a remote host
         * @return a {@link Builder}
         */
        public Builder setConnectTimeout(int connectTimeOut) {
            this.connectTimeout = connectTimeOut;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link com.ning.http.client.ws.WebSocket} can stay idle.
         *
         * @param webSocketTimeout
         *         the maximum time in millisecond an {@link com.ning.http.client.ws.WebSocket} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setWebSocketTimeout(int webSocketTimeout) {
            this.webSocketTimeout = webSocketTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can stay idle.
         *
         * @param readTimeout
         *         the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} will keep connection
         * idle in pool.
         *
         * @param idleConnectionInPoolTimeout
         *         the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} will keep connection
         *         idle in pool.
         * @return a {@link Builder}
         */
        public Builder setPooledConnectionIdleTimeout(int pooledConnectionIdleTimeout) {
            this.pooledConnectionIdleTimeout = pooledConnectionIdleTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} waits until the response is completed.
         *
         * @param requestTimeout the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} waits until the response is completed.
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
         * @param compressionEnforced true if compression is enforced
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
         * Set true if connection can be pooled by a {@link ChannelPool}. Default is true.
         *
         * @param allowPoolingConnections true if connection can be pooled by a {@link ChannelPool}
         * @return a {@link Builder}
         */
        public Builder setAllowPoolingConnections(boolean allowPoolingConnections) {
            this.allowPoolingConnections = allowPoolingConnections;
            return this;
        }

        /**
         * Set the {@link java.util.concurrent.ExecutorService} an {@link AsyncHttpClient} use for handling
         * asynchronous response.
         *
         * @param applicationThreadPool the {@link java.util.concurrent.ExecutorService} an {@link AsyncHttpClient} use for handling
         *                              asynchronous response.
         * @return a {@link Builder}
         */
        public Builder setExecutorService(ExecutorService applicationThreadPool) {
            this.applicationThreadPool = applicationThreadPool;
            return this;
        }

        /**
         * Set an instance of {@link ProxyServerSelector} used by an {@link AsyncHttpClient}
         *
         * @param proxyServerSelector instance of {@link ProxyServerSelector}
         * @return a {@link Builder}
         */
        public Builder setProxyServerSelector(ProxyServerSelector proxyServerSelector) {
            this.proxyServerSelector = proxyServerSelector;
            return this;
        }

        /**
         * Set an instance of {@link ProxyServer} used by an {@link AsyncHttpClient}
         *
         * @param proxyServer instance of {@link com.ning.http.client.ProxyServer}
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
         * Set the {@link com.ning.http.client.AsyncHttpProviderConfig}
         *
         * @param providerConfig the {@link com.ning.http.client.AsyncHttpProviderConfig}
         * @return a {@link Builder}
         */
        public Builder setAsyncHttpClientProviderConfig(AsyncHttpProviderConfig<?, ?> providerConfig) {
            this.providerConfig = providerConfig;
            return this;
        }

        /**
         * Set the {@link Realm}  that will be used for all requests.
         *
         * @param realm the {@link Realm}
         * @return a {@link Builder}
         */
        public Builder setRealm(Realm realm) {
            this.realm = realm;
            return this;
        }

        /**
         * Add an {@link com.ning.http.client.filter.RequestFilter} that will be invoked before {@link com.ning.http.client.AsyncHttpClient#executeRequest(Request)}
         *
         * @param requestFilter {@link com.ning.http.client.filter.RequestFilter}
         * @return this
         */
        public Builder addRequestFilter(RequestFilter requestFilter) {
            requestFilters.add(requestFilter);
            return this;
        }

        /**
         * Remove an {@link com.ning.http.client.filter.RequestFilter} that will be invoked before {@link com.ning.http.client.AsyncHttpClient#executeRequest(Request)}
         *
         * @param requestFilter {@link com.ning.http.client.filter.RequestFilter}
         * @return this
         */
        public Builder removeRequestFilter(RequestFilter requestFilter) {
            requestFilters.remove(requestFilter);
            return this;
        }

        /**
         * Add an {@link com.ning.http.client.filter.ResponseFilter} that will be invoked as soon as the response is
         * received, and before {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}.
         *
         * @param responseFilter an {@link com.ning.http.client.filter.ResponseFilter}
         * @return this
         */
        public Builder addResponseFilter(ResponseFilter responseFilter) {
            responseFilters.add(responseFilter);
            return this;
        }

        /**
         * Remove an {@link com.ning.http.client.filter.ResponseFilter} that will be invoked as soon as the response is
         * received, and before {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}.
         *
         * @param responseFilter an {@link com.ning.http.client.filter.ResponseFilter}
         * @return this
         */
        public Builder removeResponseFilter(ResponseFilter responseFilter) {
            responseFilters.remove(responseFilter);
            return this;
        }

        /**
         * Add an {@link com.ning.http.client.filter.IOExceptionFilter} that will be invoked when an {@link java.io.IOException}
         * occurs during the download/upload operations.
         *
         * @param ioExceptionFilter an {@link com.ning.http.client.filter.ResponseFilter}
         * @return this
         */
        public Builder addIOExceptionFilter(IOExceptionFilter ioExceptionFilter) {
            ioExceptionFilters.add(ioExceptionFilter);
            return this;
        }

        /**
         * Remove an {@link com.ning.http.client.filter.IOExceptionFilter} tthat will be invoked when an {@link java.io.IOException}
         * occurs during the download/upload operations.
         *
         * @param ioExceptionFilter an {@link com.ning.http.client.filter.ResponseFilter}
         * @return this
         */
        public Builder removeIOExceptionFilter(IOExceptionFilter ioExceptionFilter) {
            ioExceptionFilters.remove(ioExceptionFilter);
            return this;
        }

        /**
         * Set the number of time a request will be retried when an {@link java.io.IOException} occurs because of a Network exception.
         *
         * @param maxRequestRetry the number of time a request will be retried
         * @return this
         */
        public Builder setMaxRequestRetry(int maxRequestRetry) {
            this.maxRequestRetry = maxRequestRetry;
            return this;
        }

        /**
         * Return true is if connections pooling is enabled.
         *
         * @param allowPoolingSslConnections true if enabled
         * @return this
         */
        public Builder setAllowPoolingSslConnections(boolean allowPoolingSslConnections) {
            this.allowPoolingSslConnections = allowPoolingSslConnections;
            return this;
        }

        /**
         * Disable automatic url escaping
         *
         * @param disableUrlEncodingForBoundedRequests
         * @return this
         */
        public Builder setDisableUrlEncodingForBoundedRequests(boolean disableUrlEncodingForBoundedRequests) {
            this.disableUrlEncodingForBoundedRequests = disableUrlEncodingForBoundedRequests;
            return this;
        }

        /**
         * Sets whether AHC should use the default JDK ProxySelector to select a proxy server.
         * <p/>
         * If useProxySelector is set to <code>true</code> but {@link #setProxyServer(ProxyServer)}
         * was used to explicitly set a proxy server, the latter is preferred.
         * <p/>
         * See http://docs.oracle.com/javase/7/docs/api/java/net/ProxySelector.html
         */
        public Builder setUseProxySelector(boolean useProxySelector) {
            this.useProxySelector = useProxySelector;
            return this;
        }

        /**
         * Sets whether AHC should use the default http.proxy* system properties
         * to obtain proxy information.  This differs from {@link #setUseProxySelector(boolean)}
         * in that AsyncHttpClient will use its own logic to handle the system properties,
         * potentially supporting other protocols that the the JDK ProxySelector doesn't.
         * <p/>
         * If useProxyProperties is set to <code>true</code> but {@link #setUseProxySelector(boolean)}
         * was also set to true, the latter is preferred.
         * <p/>
         * See http://download.oracle.com/javase/1.4.2/docs/guide/net/properties.html
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
         * Set the {@link HostnameVerifier}
         *
         * @param hostnameVerifier {@link HostnameVerifier}
         * @return this
         */
        public Builder setHostnameVerifier(HostnameVerifier hostnameVerifier) {
            this.hostnameVerifier = hostnameVerifier;
            return this;
        }

        /**
         * Configures this AHC instance to be strict in it's handling of 302 redirects
         * in a POST/Redirect/GET situation.
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
         * Configures this AHC instance to use relative URIs instead of absolute ones when talking with a SSL proxy or WebSocket proxy.
         *
         * @param useRelativeURIsWithConnectProxies
         * @return this
         *
         * @since 1.8.13
         */
        public Builder setUseRelativeURIsWithConnectProxies(boolean useRelativeURIsWithConnectProxies) {
            this.useRelativeURIsWithConnectProxies = useRelativeURIsWithConnectProxies;
            return this;
        }

        /**
         * Set the maximum time in millisecond connection can be added to the pool for further reuse
         *
         * @param connectionTTL the maximum time in millisecond connection can be added to the pool for further reuse
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
        
        /**
         * Create a config builder with values taken from the given prototype configuration.
         *
         * @param prototype the configuration to use as a prototype.
         */
        public Builder(AsyncHttpClientConfig prototype) {
            allowPoolingConnections = prototype.isAllowPoolingConnections();
            providerConfig = prototype.getAsyncHttpProviderConfig();
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

            disableUrlEncodingForBoundedRequests = prototype.isDisableUrlEncodingForBoundedRequests();
            ioThreadMultiplier = prototype.getIoThreadMultiplier();
            maxRequestRetry = prototype.getMaxRequestRetry();
            allowPoolingSslConnections = prototype.isAllowPoolingConnections();
            hostnameVerifier = prototype.getHostnameVerifier();
            strict302Handling = prototype.isStrict302Handling();
            enabledProtocols = prototype.enabledProtocols;
            enabledCipherSuites = prototype.enabledCipherSuites;
            sslSessionCacheSize = prototype.sslSessionCacheSize;
            sslSessionTimeout = prototype.sslSessionTimeout;
            acceptAnyCertificate = prototype.acceptAnyCertificate;
        }

        /**
         * Build an {@link AsyncHttpClientConfig}
         *
         * @return an {@link AsyncHttpClientConfig}
         */
        public AsyncHttpClientConfig build() {
            if (applicationThreadPool == null) {
                applicationThreadPool = Executors.newCachedThreadPool(new ThreadFactory() {
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "AsyncHttpClient-Callback");
                        t.setDaemon(true);
                        return t;
                    }
                });
            }

            if (proxyServerSelector == null && useProxySelector)
                proxyServerSelector = ProxyUtils.getJdkDefaultProxyServerSelector();

            if (proxyServerSelector == null && useProxyProperties)
                proxyServerSelector = ProxyUtils.createProxyServerSelector(System.getProperties());

            if (proxyServerSelector == null)
                proxyServerSelector = ProxyServerSelector.NO_PROXY_SELECTOR;

            if (acceptAnyCertificate)
                hostnameVerifier = null;

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
                    hostnameVerifier,//
                    acceptAnyCertificate, //
                    followRedirect, //
                    maxRedirects, //
                    strict302Handling, //
                    applicationThreadPool, //
                    proxyServerSelector, //
                    useRelativeURIsWithConnectProxies, //
                    compressionEnforced, //
                    userAgent,//
                    realm,//
                    requestFilters, //
                    responseFilters,//
                    ioExceptionFilters,//
                    maxRequestRetry, //
                    disableUrlEncodingForBoundedRequests, //
                    ioThreadMultiplier, //
                    enabledProtocols, //
                    enabledCipherSuites, //
                    sslSessionCacheSize, //
                    sslSessionTimeout, //
                    providerConfig);
        }
    }
}
