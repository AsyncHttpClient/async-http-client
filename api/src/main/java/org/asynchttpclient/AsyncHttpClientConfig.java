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

import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.RequestFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.util.AllowAllHostnameVerifier;
import org.asynchttpclient.util.ProxyUtils;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration class to use with a {@link AsyncHttpClient}. System property can be also used to configure this
 * object default behavior by doing:
 * <p/>
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.nameOfTheProperty
 * ex:
 * <p/>
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.defaultMaxTotalConnections
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.defaultMaxTotalConnections
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.defaultMaxConnectionsPerHost
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.defaultConnectionTimeoutInMS
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.defaultIdleConnectionInPoolTimeoutInMS
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.defaultRequestTimeoutInMS
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.defaultRedirectsEnabled
 * -Dorg.asynchttpclient.AsyncHttpClientConfig.defaultMaxRedirects
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

    protected int maxTotalConnections;
    protected int maxConnectionPerHost;
    protected int connectionTimeOutInMs;
    protected int webSocketIdleTimeoutInMs;
    protected int idleConnectionInPoolTimeoutInMs;
    protected int idleConnectionTimeoutInMs;
    protected int requestTimeoutInMs;
    protected boolean redirectEnabled;
    protected int maxDefaultRedirects;
    protected boolean compressionEnabled;
    protected String userAgent;
    protected boolean allowPoolingConnection;
    protected ScheduledExecutorService reaper;
    protected ExecutorService applicationThreadPool;
    protected ProxyServerSelector proxyServerSelector;
    protected SSLContext sslContext;
    protected SSLEngineFactory sslEngineFactory;
    protected AsyncHttpProviderConfig<?, ?> providerConfig;
    protected ConnectionsPool<?, ?> connectionsPool;
    protected Realm realm;
    protected List<RequestFilter> requestFilters;
    protected List<ResponseFilter> responseFilters;
    protected List<IOExceptionFilter> ioExceptionFilters;
    protected int requestCompressionLevel;
    protected int maxRequestRetry;
    protected boolean allowSslConnectionPool;
    protected boolean useRawUrl;
    protected boolean removeQueryParamOnRedirect;
    protected boolean managedApplicationThreadPool;
    protected HostnameVerifier hostnameVerifier;
    protected int ioThreadMultiplier;
    protected boolean strict302Handling;
    protected int maxConnectionLifeTimeInMs;
    protected boolean useRelativeURIsWithSSLProxies;
    protected boolean spdyEnabled;
    protected int spdyInitialWindowSize;
    protected int spdyMaxConcurrentStreams;
    protected boolean rfc6265CookieEncoding;
    protected boolean asyncConnectMode;

    protected AsyncHttpClientConfig() {
    }

    private AsyncHttpClientConfig(int maxTotalConnections,
                                  int maxConnectionPerHost,
                                  int connectionTimeOutInMs,
                                  int webSocketTimeoutInMs,
                                  int idleConnectionInPoolTimeoutInMs,
                                  int idleConnectionTimeoutInMs,
                                  int requestTimeoutInMs,
                                  int connectionMaxLifeTimeInMs,
                                  boolean redirectEnabled,
                                  int maxDefaultRedirects,
                                  boolean compressionEnabled,
                                  String userAgent,
                                  boolean keepAlive,
                                  ScheduledExecutorService reaper,
                                  ExecutorService applicationThreadPool,
                                  ProxyServerSelector proxyServerSelector,
                                  SSLContext sslContext,
                                  SSLEngineFactory sslEngineFactory,
                                  AsyncHttpProviderConfig<?, ?> providerConfig,
                                  ConnectionsPool<?, ?> connectionsPool, Realm realm,
                                  List<RequestFilter> requestFilters,
                                  List<ResponseFilter> responseFilters,
                                  List<IOExceptionFilter> ioExceptionFilters,
                                  int requestCompressionLevel,
                                  int maxRequestRetry,
                                  boolean allowSslConnectionCaching,
                                  boolean useRawUrl,
                                  boolean removeQueryParamOnRedirect,
                                  HostnameVerifier hostnameVerifier,
                                  int ioThreadMultiplier,
                                  boolean strict302Handling,
                                  boolean useRelativeURIsWithSSLProxies,
                                  boolean spdyEnabled,
                                  int spdyInitialWindowSize,
                                  int spdyMaxConcurrentStreams,
                                  boolean rfc6265CookieEncoding,
                                  boolean asyncConnectMode) {

        this.maxTotalConnections = maxTotalConnections;
        this.maxConnectionPerHost = maxConnectionPerHost;
        this.connectionTimeOutInMs = connectionTimeOutInMs;
        this.webSocketIdleTimeoutInMs = webSocketTimeoutInMs;
        this.idleConnectionInPoolTimeoutInMs = idleConnectionInPoolTimeoutInMs;
        this.idleConnectionTimeoutInMs = idleConnectionTimeoutInMs;
        this.requestTimeoutInMs = requestTimeoutInMs;
        this.maxConnectionLifeTimeInMs = connectionMaxLifeTimeInMs;
        this.redirectEnabled = redirectEnabled;
        this.maxDefaultRedirects = maxDefaultRedirects;
        this.compressionEnabled = compressionEnabled;
        this.userAgent = userAgent;
        this.allowPoolingConnection = keepAlive;
        this.sslContext = sslContext;
        this.sslEngineFactory = sslEngineFactory;
        this.providerConfig = providerConfig;
        this.connectionsPool = connectionsPool;
        this.realm = realm;
        this.requestFilters = requestFilters;
        this.responseFilters = responseFilters;
        this.ioExceptionFilters = ioExceptionFilters;
        this.requestCompressionLevel = requestCompressionLevel;
        this.maxRequestRetry = maxRequestRetry;
        this.reaper = reaper;
        this.allowSslConnectionPool = allowSslConnectionCaching;
        this.removeQueryParamOnRedirect = removeQueryParamOnRedirect;
        this.hostnameVerifier = hostnameVerifier;
        this.ioThreadMultiplier = ioThreadMultiplier;
        this.strict302Handling = strict302Handling;
        this.useRelativeURIsWithSSLProxies = useRelativeURIsWithSSLProxies;

        if (applicationThreadPool == null) {
            managedApplicationThreadPool = true;
            this.applicationThreadPool = Executors.newCachedThreadPool();
        } else {
            this.applicationThreadPool = applicationThreadPool;
        }
        this.proxyServerSelector = proxyServerSelector;
        this.useRawUrl = useRawUrl;
        this.spdyEnabled = spdyEnabled;
        this.spdyInitialWindowSize = spdyInitialWindowSize;
        this.spdyMaxConcurrentStreams = spdyMaxConcurrentStreams;
        this.rfc6265CookieEncoding = rfc6265CookieEncoding;
        this.asyncConnectMode = asyncConnectMode;
    }

    /**
     * A {@link ScheduledExecutorService} used to expire idle connections.
     *
     * @return {@link ScheduledExecutorService}
     */
    public ScheduledExecutorService reaper() {
        return reaper;
    }

    /**
     * Return the maximum number of connections an {@link AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections an {@link AsyncHttpClient} can handle.
     */
    public int getMaxTotalConnections() {
        return maxTotalConnections;
    }

    /**
     * Return the maximum number of connections per hosts an {@link AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections per host an {@link AsyncHttpClient} can handle.
     */
    public int getMaxConnectionPerHost() {
        return maxConnectionPerHost;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
     */
    public int getConnectionTimeoutInMs() {
        return connectionTimeOutInMs;
    }

    /**
     * Return the maximum time, in milliseconds, a {@link org.asynchttpclient.websocket.WebSocket} may be idle before being timed out.
     * @return the maximum time, in milliseconds, a {@link org.asynchttpclient.websocket.WebSocket} may be idle before being timed out.
     */
    public int getWebSocketIdleTimeoutInMs() {
        return webSocketIdleTimeoutInMs;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
     */
    public int getIdleConnectionTimeoutInMs() {
        return idleConnectionTimeoutInMs;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection
     * in pool.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection
     *         in pool.
     */
    public int getIdleConnectionInPoolTimeoutInMs() {
        return idleConnectionInPoolTimeoutInMs;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} wait for a response
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} wait for a response
     */
    public int getRequestTimeoutInMs() {
        return requestTimeoutInMs;
    }

    /**
     * Is HTTP redirect enabled
     *
     * @return true if enabled.
     */
    public boolean isRedirectEnabled() {
        return redirectEnabled;
    }

    /**
     * Get the maximum number of HTTP redirect
     *
     * @return the maximum number of HTTP redirect
     */
    public int getMaxRedirects() {
        return maxDefaultRedirects;
    }

    /**
     * Is the {@link ConnectionsPool} support enabled.
     *
     * @return true if keep-alive is enabled
     */
    public boolean getAllowPoolingConnection() {
        return allowPoolingConnection;
    }

    /**
     * Is the {@link ConnectionsPool} support enabled.
     *
     * @return true if keep-alive is enabled
     * @deprecated - Use {@link AsyncHttpClientConfig#getAllowPoolingConnection()}
     */
    public boolean getKeepAlive() {
        return allowPoolingConnection;
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
     * Is HTTP compression enabled.
     *
     * @return true if compression is enabled
     */
    public boolean isCompressionEnabled() {
        return compressionEnabled;
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
     * @return <code>true</code> if this <code>AsyncHttpClientConfig</code> instance created the
     *  {@link ExecutorService} returned by {@link #executorService()}, otherwise returns <code>false</code>.
     *  The return from this method is typically used by the various provider implementations to determine
     *  if it should shutdown the {@link ExecutorService} when the {@link AsyncHttpClient} is closed.  Developers
     *  should take care and not share managed {@link ExecutorService} instances between client instances.
     *
     * @since 2.2.0
     */
    public boolean isManagedExecutorService() {
        return managedApplicationThreadPool;
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
     * Return an instance of {@link ConnectionsPool}
     *
     * @return an instance of {@link ConnectionsPool}
     */
    public ConnectionsPool<?, ?> getConnectionsPool() {
        return connectionsPool;
    }

    /**
     * Return an instance of {@link SSLEngineFactory} used for SSL connection.
     *
     * @return an instance of {@link SSLEngineFactory} used for SSL connection.
     */
    public SSLEngineFactory getSSLEngineFactory() {
        if (sslEngineFactory == null) {
            return new SSLEngineFactory() {
                public SSLEngine newSSLEngine() {
                    if (sslContext != null) {
                        SSLEngine sslEngine = sslContext.createSSLEngine();
                        sslEngine.setUseClientMode(true);
                        return sslEngine;
                    } else {
                        return null;
                    }
                }
            };
        }
        return sslEngineFactory;
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
     * Return the compression level, or -1 if no compression is used.
     *
     * @return the compression level, or -1 if no compression is use
     */
    public int getRequestCompressionLevel() {
        return requestCompressionLevel;
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
    public boolean isSslConnectionPoolEnabled() {
        return allowSslConnectionPool;
    }


    /**
     * @return the useRawUrl
     */
    public boolean isUseRawUrl() {
        return useRawUrl;
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
     * Return true if one of the {@link java.util.concurrent.ExecutorService} has been shutdown.
     *
     * @return true if one of the {@link java.util.concurrent.ExecutorService} has been shutdown.
     */
    public boolean isClosed() {
        return applicationThreadPool.isShutdown() || reaper.isShutdown();
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
     * @return<code>true</code> if AHC should use relative URIs instead of absolute ones when talking with a SSL proxy,
     *  otherwise <code>false</code>.
     *  
     *  @since 1.7.12
     */
    public boolean isUseRelativeURIsWithSSLProxies() {
        return useRelativeURIsWithSSLProxies;
    }

    /**
     * Return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection in the pool, or -1 to keep connection while possible.
     *
     * @return the maximum time in millisecond an {@link AsyncHttpClient} will keep connection in the pool, or -1 to keep connection while possible.
     */
    public int getMaxConnectionLifeTimeInMs() {
        return maxConnectionLifeTimeInMs;
    }

    /**
     * @return<code>true</code> if AHC should use rfc6265 for encoding client side cookies, otherwise <code>false</code>.
     * 
     * @since 1.7.18
     */
    public boolean isRfc6265CookieEncoding() {
        return rfc6265CookieEncoding;
    }

    /**
     * @return <code>true</code> if the underlying provider should make new connections asynchronously or not.  By default
     *  new connections are made synchronously.
     *
     * @since 2.0.0
     */
    public boolean isAsyncConnectMode() {
        return asyncConnectMode;
    }

    /**
     * Builder for an {@link AsyncHttpClient}
     */
    public static class Builder {
        private int defaultMaxTotalConnections = Integer.getInteger(ASYNC_CLIENT + "defaultMaxTotalConnections", -1);
        private int defaultMaxConnectionPerHost = Integer.getInteger(ASYNC_CLIENT + "defaultMaxConnectionsPerHost", -1);
        private int defaultConnectionTimeOutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultConnectionTimeoutInMS", 60 * 1000);
        private int defaultWebsocketIdleTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultWebsocketTimoutInMS", 15 * 60 * 1000);
        private int defaultIdleConnectionInPoolTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultIdleConnectionInPoolTimeoutInMS", 60 * 1000);
        private int defaultIdleConnectionTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultIdleConnectionTimeoutInMS", 60 * 1000);
        private int defaultRequestTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultRequestTimeoutInMS", 60 * 1000);
        private int defaultMaxConnectionLifeTimeInMs = Integer.getInteger(ASYNC_CLIENT + "defaultMaxConnectionLifeTimeInMs", -1);
        private boolean redirectEnabled = Boolean.getBoolean(ASYNC_CLIENT + "defaultRedirectsEnabled");
        private int maxDefaultRedirects = Integer.getInteger(ASYNC_CLIENT + "defaultMaxRedirects", 5);
        private boolean compressionEnabled = Boolean.getBoolean(ASYNC_CLIENT + "compressionEnabled");
        private String userAgent = System.getProperty(ASYNC_CLIENT + "userAgent", "AsyncHttpClient/" + AHC_VERSION);
        private boolean useProxyProperties = Boolean.getBoolean(ASYNC_CLIENT + "useProxyProperties");
        private boolean useProxySelector = Boolean.getBoolean(ASYNC_CLIENT + "useProxySelector");
        private boolean allowPoolingConnection = true;
        private boolean useRelativeURIsWithSSLProxies = Boolean.getBoolean(ASYNC_CLIENT + "useRelativeURIsWithSSLProxies");
        private ScheduledExecutorService reaper;
        private ExecutorService applicationThreadPool;
        private ProxyServerSelector proxyServerSelector = null;
        private SSLContext sslContext;
        private SSLEngineFactory sslEngineFactory;
        private AsyncHttpProviderConfig<?, ?> providerConfig;
        private ConnectionsPool<?, ?> connectionsPool;
        private Realm realm;
        private int requestCompressionLevel = -1;
        private int maxRequestRetry = 5;
        private final List<RequestFilter> requestFilters = new LinkedList<RequestFilter>();
        private final List<ResponseFilter> responseFilters = new LinkedList<ResponseFilter>();
        private final List<IOExceptionFilter> ioExceptionFilters = new LinkedList<IOExceptionFilter>();
        private boolean allowSslConnectionPool = true;
        private boolean useRawUrl = false;
        private boolean removeQueryParamOnRedirect = true;
        private HostnameVerifier hostnameVerifier = new AllowAllHostnameVerifier();
        private int ioThreadMultiplier = 2;
        private boolean strict302Handling;
        private boolean spdyEnabled;
        private int spdyInitialWindowSize = 10 * 1024 * 1024;
        private int spdyMaxConcurrentStreams = 100;
        private boolean rfc6265CookieEncoding;
        private boolean asyncConnectMode;

        public Builder() {
        }

        /**
         * Set the maximum number of connections an {@link AsyncHttpClient} can handle.
         *
         * @param defaultMaxTotalConnections the maximum number of connections an {@link AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaximumConnectionsTotal(int defaultMaxTotalConnections) {
            this.defaultMaxTotalConnections = defaultMaxTotalConnections;
            return this;
        }

        /**
         * Set the maximum number of connections per hosts an {@link AsyncHttpClient} can handle.
         *
         * @param defaultMaxConnectionPerHost the maximum number of connections per host an {@link AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaximumConnectionsPerHost(int defaultMaxConnectionPerHost) {
            this.defaultMaxConnectionPerHost = defaultMaxConnectionPerHost;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
         *
         * @param defaultConnectionTimeOutInMs the maximum time in millisecond an {@link AsyncHttpClient} can wait when connecting to a remote host
         * @return a {@link Builder}
         */
        public Builder setConnectionTimeoutInMs(int defaultConnectionTimeOutInMs) {
            this.defaultConnectionTimeOutInMs = defaultConnectionTimeOutInMs;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link org.asynchttpclient.websocket.WebSocket} can stay idle.
         *
         * @param defaultWebSocketIdleTimeoutInMs
         *         the maximum time in millisecond an {@link org.asynchttpclient.websocket.WebSocket} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setWebSocketIdleTimeoutInMs(int defaultWebSocketIdleTimeoutInMs) {
            this.defaultWebsocketIdleTimeoutInMs = defaultWebSocketIdleTimeoutInMs;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
         *
         * @param defaultIdleConnectionTimeoutInMs
         *         the maximum time in millisecond an {@link AsyncHttpClient} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setIdleConnectionTimeoutInMs(int defaultIdleConnectionTimeoutInMs) {
            this.defaultIdleConnectionTimeoutInMs = defaultIdleConnectionTimeoutInMs;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} will keep connection
         * idle in pool.
         *
         * @param defaultIdleConnectionInPoolTimeoutInMs
         *         the maximum time in millisecond an {@link AsyncHttpClient} will keep connection
         *         idle in pool.
         * @return a {@link Builder}
         */
        public Builder setIdleConnectionInPoolTimeoutInMs(int defaultIdleConnectionInPoolTimeoutInMs) {
            this.defaultIdleConnectionInPoolTimeoutInMs = defaultIdleConnectionInPoolTimeoutInMs;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link AsyncHttpClient} wait for a response
         *
         * @param defaultRequestTimeoutInMs the maximum time in millisecond an {@link AsyncHttpClient} wait for a response
         * @return a {@link Builder}
         */
        public Builder setRequestTimeoutInMs(int defaultRequestTimeoutInMs) {
            this.defaultRequestTimeoutInMs = defaultRequestTimeoutInMs;
            return this;
        }

        /**
         * Set to true to enable HTTP redirect
         *
         * @param redirectEnabled true if enabled.
         * @return a {@link Builder}
         */
        public Builder setFollowRedirects(boolean redirectEnabled) {
            this.redirectEnabled = redirectEnabled;
            return this;
        }

        /**
         * Set the maximum number of HTTP redirect
         *
         * @param maxDefaultRedirects the maximum number of HTTP redirect
         * @return a {@link Builder}
         */
        public Builder setMaximumNumberOfRedirects(int maxDefaultRedirects) {
            this.maxDefaultRedirects = maxDefaultRedirects;
            return this;
        }

        /**
         * Enable HTTP compression.
         *
         * @param compressionEnabled true if compression is enabled
         * @return a {@link Builder}
         */
        public Builder setCompressionEnabled(boolean compressionEnabled) {
            this.compressionEnabled = compressionEnabled;
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
         * @param allowPoolingConnection true if connection can be pooled by a {@link ConnectionsPool}
         * @return a {@link Builder}
         */
        public Builder setAllowPoolingConnection(boolean allowPoolingConnection) {
            this.allowPoolingConnection = allowPoolingConnection;
            return this;
        }

        /**
         * Set true if connection can be pooled by a {@link ConnectionsPool}. Default is true.
         *
         * @param allowPoolingConnection true if connection can be pooled by a {@link ConnectionsPool}
         * @return a {@link Builder}
         * @deprecated - Use {@link AsyncHttpClientConfig.Builder#setAllowPoolingConnection(boolean)}
         */
        public Builder setKeepAlive(boolean allowPoolingConnection) {
            this.allowPoolingConnection = allowPoolingConnection;
            return this;
        }

        /**
         * Set the{@link ScheduledExecutorService} used to expire idle connections.
         *
         * @param reaper the{@link ScheduledExecutorService} used to expire idle connections.
         * @return a {@link Builder}
         */
        public Builder setScheduledExecutorService(ScheduledExecutorService reaper) {
            this.reaper = reaper;
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
         * Set the {@link SSLEngineFactory} for secure connection.
         *
         * @param sslEngineFactory the {@link SSLEngineFactory} for secure connection
         * @return a {@link Builder}
         */
        public Builder setSSLEngineFactory(SSLEngineFactory sslEngineFactory) {
            this.sslEngineFactory = sslEngineFactory;
            return this;
        }

        /**
         * Set the {@link SSLContext} for secure connection.
         *
         * @param sslContext the {@link SSLContext} for secure connection
         * @return a {@link Builder}
         */
        public Builder setSSLContext(final SSLContext sslContext) {
            this.sslEngineFactory = new SSLEngineFactory() {
                public SSLEngine newSSLEngine() throws GeneralSecurityException {
                    SSLEngine sslEngine = sslContext.createSSLEngine();
                    sslEngine.setUseClientMode(true);
                    return sslEngine;
                }
            };
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
         * Set the {@link ConnectionsPool}
         *
         * @param connectionsPool the {@link ConnectionsPool}
         * @return a {@link Builder}
         */
        public Builder setConnectionsPool(ConnectionsPool<?, ?> connectionsPool) {
            this.connectionsPool = connectionsPool;
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
         * Return the compression level, or -1 if no compression is used.
         *
         * @return the compression level, or -1 if no compression is use
         */
        public int getRequestCompressionLevel() {
            return requestCompressionLevel;
        }

        /**
         * Set the compression level, or -1 if no compression is used.
         *
         * @param requestCompressionLevel compression level, or -1 if no compression is use
         * @return this
         */
        public Builder setRequestCompressionLevel(int requestCompressionLevel) {
            this.requestCompressionLevel = requestCompressionLevel;
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
         * @param allowSslConnectionPool true if enabled
         * @return this
         */
        public Builder setAllowSslConnectionPool(boolean allowSslConnectionPool) {
            this.allowSslConnectionPool = allowSslConnectionPool;
            return this;
        }

        /**
         * Allows use unescaped URLs in requests
         * useful for retrieving data from broken sites
         *
         * @param useRawUrl
         * @return this
         */
        public Builder setUseRawUrl(boolean useRawUrl) {
            this.useRawUrl = useRawUrl;
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
         * @param maxConnectionLifeTimeInMs the maximum time in millisecond connection can be added to the pool for further reuse
         * @return a {@link Builder}
         */
        public Builder setMaxConnectionLifeTimeInMs(int maxConnectionLifeTimeInMs) {
           this.defaultMaxConnectionLifeTimeInMs = maxConnectionLifeTimeInMs;
           return this;
        }

        /**
         * Configures this AHC instance to use relative URIs instead of absolute ones when talking with a SSL proxy.
         *
         * @param useRelativeURIsWithSSLProxies
         * @return this
         *
         * @since 1.7.2
         */
        public Builder setUseRelativeURIsWithSSLProxies(boolean useRelativeURIsWithSSLProxies) {
            this.useRelativeURIsWithSSLProxies = useRelativeURIsWithSSLProxies;
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

        /**
         * Configures this AHC instance to use RFC 6265 cookie encoding style
         * 
         * @param rfc6265CookieEncoding
         * @return this
         * 
         * @since 1.7.18
         */
        public Builder setRfc6265CookieEncoding(boolean rfc6265CookieEncoding) {
            this.rfc6265CookieEncoding = rfc6265CookieEncoding;
            return this;
        }

        /**
         * Configures how the underlying providers make new connections.  By default,
         * connections will be made synchronously.
         *
         * @param asyncConnectMode pass <code>true</code> to enable async connect mode.
         *
         * @return this
         *
         * @since 2.0.0
         */
        public Builder setAsyncConnectMode(boolean asyncConnectMode) {
            this.asyncConnectMode = asyncConnectMode;
            return this;
        }

        /**
         * Create a config builder with values taken from the given prototype configuration.
         *
         * @param prototype the configuration to use as a prototype.
         */
        public Builder(AsyncHttpClientConfig prototype) {
            allowPoolingConnection = prototype.getAllowPoolingConnection();
            providerConfig = prototype.getAsyncHttpProviderConfig();
            connectionsPool = prototype.getConnectionsPool();
            defaultConnectionTimeOutInMs = prototype.getConnectionTimeoutInMs();
            defaultIdleConnectionInPoolTimeoutInMs = prototype.getIdleConnectionInPoolTimeoutInMs();
            defaultIdleConnectionTimeoutInMs = prototype.getIdleConnectionTimeoutInMs();
            defaultMaxConnectionPerHost = prototype.getMaxConnectionPerHost();
            defaultMaxConnectionLifeTimeInMs = prototype.getMaxConnectionLifeTimeInMs();
            maxDefaultRedirects = prototype.getMaxRedirects();
            defaultMaxTotalConnections = prototype.getMaxTotalConnections();
            proxyServerSelector = prototype.getProxyServerSelector();
            realm = prototype.getRealm();
            defaultRequestTimeoutInMs = prototype.getRequestTimeoutInMs();
            sslContext = prototype.getSSLContext();
            sslEngineFactory = prototype.getSSLEngineFactory();
            userAgent = prototype.getUserAgent();
            redirectEnabled = prototype.isRedirectEnabled();
            compressionEnabled = prototype.isCompressionEnabled();
            reaper = prototype.reaper();
            applicationThreadPool = prototype.executorService();

            requestFilters.clear();
            responseFilters.clear();
            ioExceptionFilters.clear();

            requestFilters.addAll(prototype.getRequestFilters());
            responseFilters.addAll(prototype.getResponseFilters());
            ioExceptionFilters.addAll(prototype.getIOExceptionFilters());

            requestCompressionLevel = prototype.getRequestCompressionLevel();
            useRawUrl = prototype.isUseRawUrl();
            ioThreadMultiplier = prototype.getIoThreadMultiplier();
            maxRequestRetry = prototype.getMaxRequestRetry();
            allowSslConnectionPool = prototype.getAllowPoolingConnection();
            removeQueryParamOnRedirect = prototype.isRemoveQueryParamOnRedirect();
            hostnameVerifier = prototype.getHostnameVerifier();
            strict302Handling = prototype.isStrict302Handling();
            useRelativeURIsWithSSLProxies = prototype.isUseRelativeURIsWithSSLProxies();
            rfc6265CookieEncoding = prototype.isRfc6265CookieEncoding();
            asyncConnectMode = prototype.isAsyncConnectMode();
        }

        /**
         * Build an {@link AsyncHttpClientConfig}
         *
         * @return an {@link AsyncHttpClientConfig}
         */
        public AsyncHttpClientConfig build() {
            
            if (reaper == null) {
                reaper = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), new ThreadFactory() {
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "AsyncHttpClient-Reaper");
                        t.setDaemon(true);
                        return t;
                    }
                });
            }

            if (applicationThreadPool == null) {
                applicationThreadPool =
                        Executors.newCachedThreadPool(new ThreadFactory() {
                            final AtomicInteger counter = new AtomicInteger();
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r,
                                                      "AsyncHttpClient-Callback-" + counter.incrementAndGet());
                                t.setDaemon(true);
                                return t;
                            }
                        });
            }

            if (applicationThreadPool.isShutdown()) {
                throw new IllegalStateException("ExecutorServices closed");
            }

            if (proxyServerSelector == null && useProxySelector) {
                proxyServerSelector = ProxyUtils.getJdkDefaultProxyServerSelector();
            }

            if (proxyServerSelector == null && useProxyProperties) {
                proxyServerSelector = ProxyUtils.createProxyServerSelector(System.getProperties());
            }

            if (proxyServerSelector == null) {
                proxyServerSelector = ProxyServerSelector.NO_PROXY_SELECTOR;
            }

            return new AsyncHttpClientConfig(defaultMaxTotalConnections,
                    defaultMaxConnectionPerHost,
                    defaultConnectionTimeOutInMs,
                    defaultWebsocketIdleTimeoutInMs,
                    defaultIdleConnectionInPoolTimeoutInMs,
                    defaultIdleConnectionTimeoutInMs,
                    defaultRequestTimeoutInMs,
                    defaultMaxConnectionLifeTimeInMs,
                    redirectEnabled,
                    maxDefaultRedirects,
                    compressionEnabled,
                    userAgent,
                    allowPoolingConnection,
                    reaper,
                    applicationThreadPool,
                    proxyServerSelector,
                    sslContext,
                    sslEngineFactory,
                    providerConfig,
                    connectionsPool,
                    realm,
                    requestFilters,
                    responseFilters,
                    ioExceptionFilters,
                    requestCompressionLevel,
                    maxRequestRetry,
                    allowSslConnectionPool,
                    useRawUrl,
                    removeQueryParamOnRedirect,
                    hostnameVerifier,
                    ioThreadMultiplier,
                    strict302Handling,
                    useRelativeURIsWithSSLProxies,
                    spdyEnabled,
                    spdyInitialWindowSize,
                    spdyMaxConcurrentStreams,
                    rfc6265CookieEncoding,
                    asyncConnectMode);
        }
    }
}

