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
package org.asynchttpclient;

import static org.asynchttpclient.AsyncHttpClientConfigDefaults.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import org.asynchttpclient.date.TimeConverter;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.util.DefaultHostnameVerifier;
import org.asynchttpclient.util.ProxyUtils;

/**
 * Configuration class to use with a {@link AsyncHttpClient}. System property can be also used to configure this
 * object default behavior by doing:
 * <p/>
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.nameOfTheProperty
 */
public class AsyncHttpClientConfig {

    protected final static String ASYNC_CLIENT = AsyncHttpClientConfig.class.getName() + ".";
    public final static String AHC_VERSION;

    static {
        InputStream is = null;
        Properties prop = new Properties();
        try {
            is = AsyncHttpClientConfig.class.getResourceAsStream("version.properties");
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

    protected int connectionTimeout;

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
    protected boolean removeQueryParamOnRedirect;
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
    protected TimeConverter timeConverter;
    protected AsyncHttpProviderConfig<?, ?> providerConfig;
    
    // AHC 2 specific
    protected boolean spdyEnabled;
    protected int spdyInitialWindowSize;
    protected int spdyMaxConcurrentStreams;

    protected AsyncHttpClientConfig() {
    }

    private AsyncHttpClientConfig(int connectionTimeout,//
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
            HostnameVerifier hostnameVerifier,//
            boolean acceptAnyCertificate, //
            boolean followRedirect, //
            int maxRedirects, //
            boolean removeQueryParamOnRedirect,//
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
            boolean disableUrlEncodingForBoundRequests, //
            int ioThreadMultiplier, //
            TimeConverter timeConverter,//
            AsyncHttpProviderConfig<?, ?> providerConfig,//
            boolean spdyEnabled, //
            int spdyInitialWindowSize, //
            int spdyMaxConcurrentStreams) {

        this.connectionTimeout = connectionTimeout;
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
        this.hostnameVerifier = hostnameVerifier;
        this.acceptAnyCertificate = acceptAnyCertificate;
        this.followRedirect = followRedirect;
        this.maxRedirects = maxRedirects;
        this.removeQueryParamOnRedirect = removeQueryParamOnRedirect;
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
        this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
        this.ioThreadMultiplier = ioThreadMultiplier;
        this.timeConverter = timeConverter;
        this.providerConfig = providerConfig;
        this.spdyEnabled = spdyEnabled;
        this.spdyInitialWindowSize = spdyInitialWindowSize;
        this.spdyMaxConcurrentStreams = spdyMaxConcurrentStreams;
        
    }

    /**
     * Return the maximum number of connections an {@link AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections an {@link AsyncHttpClient} can handle.
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Return the maximum number of connections per hosts an {@link AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections per host an {@link AsyncHttpClient} can handle.
     */
    public int getMaxConnectionsPerHost() {
        return maxConnectionsPerHost;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
     */
    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * Return the maximum time, in milliseconds, a {@link org.asynchttpclient.websocket.WebSocket} may be idle before being timed out.
     * @return the maximum time, in milliseconds, a {@link org.asynchttpclient.websocket.WebSocket} may be idle before being timed out.
     */
    public int getWebSocketTimeout() {
        return webSocketTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
     */
    public int getReadTimeout() {
        return readTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection
     * in pool.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection
     *         in pool.
     */
    public int getPooledConnectionIdleTimeout() {
        return pooledConnectionIdleTimeout;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} wait for a response
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} wait for a response
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
     * Return the {@link java.util.concurrent.ExecutorService} an {@link AsyncHttpClient} use for handling
     * asynchronous response.
     *
     * @return the {@link java.util.concurrent.ExecutorService} an {@link AsyncHttpClient} use for handling
     *         asynchronous response.  If no {@link ExecutorService} has been explicitly provided,
     *         this method will return <code>null</code>
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
     * Return the current {@link Realm}}
     *
     * @return the current {@link Realm}}
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
     * @return the disableUrlEncodingForBoundRequests
     */
    public boolean isDisableUrlEncodingForBoundRequests() {
        return disableUrlEncodingForBoundRequests;
    }

    /**
     * @return whether or not SPDY is enabled.
     */
    public boolean isSpdyEnabled() {
        return spdyEnabled;
    }

    /**
     * @return the windows size new SPDY sessions should be initialized to.
     */
    public int getSpdyInitialWindowSize() {
        return spdyInitialWindowSize;
    }

    /**
     * @return the maximum number of concurrent streams over one SPDY session.
     */
    public int getSpdyMaxConcurrentStreams() {
        return spdyMaxConcurrentStreams;
    }

    /**
     * Return true if the query parameters will be stripped from the request when a redirect is requested.
     *
     * @return true if the query parameters will be stripped from the request when a redirect is requested.
     */
    public boolean isRemoveQueryParamOnRedirect() {
        return removeQueryParamOnRedirect;
    }

    /**
     * @return <code>true</code> if both the application and reaper thread pools
     * haven't yet been shutdown.
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
     * Return the {@link HostnameVerifier}
     *
     * @return the {@link HostnameVerifier}
     */
    public HostnameVerifier getHostnameVerifier() {
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
     * Return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection in the pool, or -1 to keep connection while possible.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection in the pool, or -1 to keep connection while possible.
     */
    public int getConnectionTTL() {
        return connectionTTL;
    }

    /**
     * @return the TimeConverter used for converting RFC2616Dates into time
     *
     * @since 2.0.0
     */
    public TimeConverter getTimeConverter() {
        return timeConverter;
    }

    public boolean isAcceptAnyCertificate() {
        return acceptAnyCertificate;
    }

    /**
     * Builder for an {@link AsyncHttpClient}
     */
    public static class Builder {
        private int connectionTimeout = defaultConnectionTimeout();
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
        private boolean removeQueryParamOnRedirect = defaultRemoveQueryParamOnRedirect();
        private boolean strict302Handling = defaultStrict302Handling();
        private ProxyServerSelector proxyServerSelector = null;
        private boolean useProxySelector = defaultUseProxySelector();
        private boolean useProxyProperties = defaultUseProxyProperties();
        private boolean useRelativeURIsWithConnectProxies = defaultUseRelativeURIsWithConnectProxies();
        private boolean compressionEnforced = defaultCompressionEnforced();
        private String userAgent = defaultUserAgent();
        private ExecutorService applicationThreadPool;
        private Realm realm;
        private final List<RequestFilter> requestFilters = new LinkedList<RequestFilter>();
        private final List<ResponseFilter> responseFilters = new LinkedList<ResponseFilter>();
        private final List<IOExceptionFilter> ioExceptionFilters = new LinkedList<IOExceptionFilter>();
        private int maxRequestRetry = defaultMaxRequestRetry();
        private boolean disableUrlEncodingForBoundRequests = defaultDisableUrlEncodingForBoundRequests();
        private int ioThreadMultiplier = defaultIoThreadMultiplier();
        private TimeConverter timeConverter;
        private AsyncHttpProviderConfig<?, ?> providerConfig;
        
        // AHC 2
        private boolean spdyEnabled = defaultSpdyEnabled();
        private int spdyInitialWindowSize = defaultSpdyInitialWindowSize();
        private int spdyMaxConcurrentStreams = defaultSpdyMaxConcurrentStreams();
        
        public Builder() {
        }

        /**
         * Set the maximum number of connections an {@link AsyncHttpClient} can handle.
         *
         * @param maxConnections the maximum number of connections an {@link AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        /**
         * Set the maximum number of connections per hosts an {@link AsyncHttpClient} can handle.
         *
         * @param maxConnectionsPerHost the maximum number of connections per host an {@link AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaxConnectionsPerHost(int maxConnectionsPerHost) {
            this.maxConnectionsPerHost = maxConnectionsPerHost;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
         *
         * @param connectionTimeout the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
         * @return a {@link Builder}
         */
        public Builder setConnectionTimeout(int connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link org.asynchttpclient.websocket.WebSocket} can stay idle.
         *
         * @param webSocketTimeout
         *         the maximum time in millisecond an {@link org.asynchttpclient.websocket.WebSocket} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setWebSocketTimeout(int webSocketTimeout) {
            this.webSocketTimeout = webSocketTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
         *
         * @param readTimeout
         *         the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} will keep connection
         * idle in pool.
         *
         * @param pooledConnectionIdleTimeout
         *         the maximum time in millisecond an {@link AsyncHttpClient} will keep connection
         *         idle in pool.
         * @return a {@link Builder}
         */
        public Builder setPooledConnectionIdleTimeout(int pooledConnectionIdleTimeout) {
            this.pooledConnectionIdleTimeout = pooledConnectionIdleTimeout;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} wait for a response
         *
         * @param requestTimeout the maximum time in millisecond an {@link AsyncHttpClient} wait for a response
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
         * Set true if connection can be pooled by a {@link ConnectionsPool}. Default is true.
         *
         * @param allowPoolingConnections true if connection can be pooled by a {@link ConnectionsPool}
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
         * Add an {@link org.asynchttpclient.filter.RequestFilter} that will be invoked before {@link AsyncHttpClient#executeRequest(Request)}
         *
         * @param requestFilter {@link org.asynchttpclient.filter.RequestFilter}
         * @return this
         */
        public Builder addRequestFilter(RequestFilter requestFilter) {
            requestFilters.add(requestFilter);
            return this;
        }

        /**
         * Remove an {@link org.asynchttpclient.filter.RequestFilter} that will be invoked before {@link AsyncHttpClient#executeRequest(Request)}
         *
         * @param requestFilter {@link org.asynchttpclient.filter.RequestFilter}
         * @return this
         */
        public Builder removeRequestFilter(RequestFilter requestFilter) {
            requestFilters.remove(requestFilter);
            return this;
        }

        /**
         * Add an {@link org.asynchttpclient.filter.ResponseFilter} that will be invoked as soon as the response is
         * received, and before {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}.
         *
         * @param responseFilter an {@link org.asynchttpclient.filter.ResponseFilter}
         * @return this
         */
        public Builder addResponseFilter(ResponseFilter responseFilter) {
            responseFilters.add(responseFilter);
            return this;
        }

        /**
         * Remove an {@link org.asynchttpclient.filter.ResponseFilter} that will be invoked as soon as the response is
         * received, and before {@link AsyncHandler#onStatusReceived(HttpResponseStatus)}.
         *
         * @param responseFilter an {@link org.asynchttpclient.filter.ResponseFilter}
         * @return this
         */
        public Builder removeResponseFilter(ResponseFilter responseFilter) {
            responseFilters.remove(responseFilter);
            return this;
        }

        /**
         * Add an {@link org.asynchttpclient.filter.IOExceptionFilter} that will be invoked when an {@link java.io.IOException}
         * occurs during the download/upload operations.
         *
         * @param ioExceptionFilter an {@link org.asynchttpclient.filter.ResponseFilter}
         * @return this
         */
        public Builder addIOExceptionFilter(IOExceptionFilter ioExceptionFilter) {
            ioExceptionFilters.add(ioExceptionFilter);
            return this;
        }

        /**
         * Remove an {@link org.asynchttpclient.filter.IOExceptionFilter} tthat will be invoked when an {@link java.io.IOException}
         * occurs during the download/upload operations.
         *
         * @param ioExceptionFilter an {@link org.asynchttpclient.filter.ResponseFilter}
         * @return this
         */
        public Builder removeIOExceptionFilter(IOExceptionFilter ioExceptionFilter) {
            ioExceptionFilters.remove(ioExceptionFilter);
            return this;
        }

        /**
         * Set the number of times a request will be retried when an {@link java.io.IOException} occurs because of a Network exception.
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
         * Allows use unescaped URLs in requests
         * useful for retrieving data from broken sites
         *
         * @param disableUrlEncodingForBoundRequests
         * @return this
         */
        public Builder setDisableUrlEncodingForBoundRequests(boolean disableUrlEncodingForBoundRequests) {
            this.disableUrlEncodingForBoundRequests = disableUrlEncodingForBoundRequests;
            return this;
        }

        /**
         * Set to false if you don't want the query parameters removed when a redirect occurs.
         *
         * @param removeQueryParamOnRedirect
         * @return this
         */
        public Builder setRemoveQueryParamsOnRedirect(boolean removeQueryParamOnRedirect) {
            this.removeQueryParamOnRedirect = removeQueryParamOnRedirect;
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
         * Set the maximum time in millisecond connection can be added to the pool for further reuse
         *
         * @param connectionTTL the maximum time in millisecond connection can be added to the pool for further reuse
         * @return a {@link Builder}
         */
        public Builder setConnectionTTL(int connectionTTL) {
            this.connectionTTL = connectionTTL;
            return this;
        }

        /**
         * Configures this AHC instance to use relative URIs instead of absolute ones when talking with a SSL or WebSocket proxy.
         *
         * @param useRelativeURIsWithConnectProxies
         * @return this
         *
         * @since 1.7.2
         */
        public Builder setUseRelativeURIsWithConnectProxies(boolean useRelativeURIsWithConnectProxies) {
            this.useRelativeURIsWithConnectProxies = useRelativeURIsWithConnectProxies;
            return this;
        }

        /**
         * Enables SPDY support.  Note that doing so, will currently disable WebSocket support
         * for this client instance.  If not explicitly enabled, spdy will not be used.
         *
         * @param spdyEnabled configures spdy support.
         *
         * @return this
         *
         * @since 2.0
         */
        public Builder setSpdyEnabled(boolean spdyEnabled) {
            this.spdyEnabled = spdyEnabled;
            return this;
        }

        /**
         * Configures the initial window size for the SPDY session.
         *
         * @param spdyInitialWindowSize the initial window size.
         *
         * @return this
         *
         * @since 2.0
         */
        public Builder setSpdyInitialWindowSize(int spdyInitialWindowSize) {
            this.spdyInitialWindowSize = spdyInitialWindowSize;
            return this;
        }

        /**
         * Configures the maximum number of concurrent streams over a single
         * SPDY session.
         *
         * @param spdyMaxConcurrentStreams the maximum number of concurrent
         *                                 streams over a single SPDY session.
         *
         * @return this
         *
         * @since 2.0
         */
        public Builder setSpdyMaxConcurrentStreams(int spdyMaxConcurrentStreams) {
            this.spdyMaxConcurrentStreams = spdyMaxConcurrentStreams;
            return this;
        }

        public Builder setTimeConverter(TimeConverter timeConverter) {
            this.timeConverter = timeConverter;
            return this;
        }

        public Builder setAcceptAnyCertificate(boolean acceptAnyCertificate) {
            this.acceptAnyCertificate = acceptAnyCertificate;
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
            connectionTimeout = prototype.getConnectionTimeout();
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
            removeQueryParamOnRedirect = prototype.isRemoveQueryParamOnRedirect();
            hostnameVerifier = prototype.getHostnameVerifier();
            strict302Handling = prototype.isStrict302Handling();
            timeConverter = prototype.timeConverter;
            acceptAnyCertificate = prototype.acceptAnyCertificate;
            
            spdyEnabled = prototype.isSpdyEnabled();
            spdyInitialWindowSize = prototype.getSpdyInitialWindowSize();
            spdyMaxConcurrentStreams = prototype.getSpdyMaxConcurrentStreams();
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

            if (acceptAnyCertificate)
                hostnameVerifier = null;
            else if (hostnameVerifier == null)
                hostnameVerifier = new DefaultHostnameVerifier();

            return new AsyncHttpClientConfig(connectionTimeout,//
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
                    removeQueryParamOnRedirect,//
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
                    disableUrlEncodingForBoundRequests, //
                    ioThreadMultiplier, //
                    timeConverter,//
                    providerConfig, //
                    spdyEnabled, //
                    spdyInitialWindowSize, //
                    spdyMaxConcurrentStreams);
        }
    }
}
