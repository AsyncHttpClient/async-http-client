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

import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.RequestFilter;
import com.ning.http.client.filter.ResponseFilter;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * Configuration class to use with a {@link AsyncHttpClient}. System property can be also used to configure this
 * object default behavior by doing:
 * <p/>
 * -Dcom.ning.http.client.AsyncHttpClientConfig.nameOfTheProperty
 * ex:
 * <p/>
 * -Dcom.ning.http.client.AsyncHttpClientConfig.defaultMaxTotalConnections
 * -Dcom.ning.http.client.AsyncHttpClientConfig.defaultMaxTotalConnections
 * -Dcom.ning.http.client.AsyncHttpClientConfig.defaultMaxConnectionsPerHost
 * -Dcom.ning.http.client.AsyncHttpClientConfig.defaultConnectionTimeoutInMS
 * -Dcom.ning.http.client.AsyncHttpClientConfig.defaultIdleConnectionTimeoutInMS
 * -Dcom.ning.http.client.AsyncHttpClientConfig.defaultRequestTimeoutInMS
 * -Dcom.ning.http.client.AsyncHttpClientConfig.defaultRedirectsEnabled
 * -Dcom.ning.http.client.AsyncHttpClientConfig.defaultMaxRedirects
 */
public class AsyncHttpClientConfig {

    private final static String ASYNC_CLIENT = AsyncHttpClientConfig.class.getName() + ".";

    private final int maxTotalConnections;
    private final int maxConnectionPerHost;
    private final int connectionTimeOutInMs;
    private final int idleConnectionTimeoutInMs;
    private final int requestTimeoutInMs;
    private final boolean redirectEnabled;
    private final int maxDefaultRedirects;
    private final boolean compressionEnabled;
    private final String userAgent;
    private final boolean allowPoolingConnection;
    private final ScheduledExecutorService reaper;
    private final ExecutorService applicationThreadPool;
    private final ProxyServer proxyServer;
    private final SSLContext sslContext;
    private final SSLEngineFactory sslEngineFactory;
    private final AsyncHttpProviderConfig<?, ?> providerConfig;
    private final ConnectionsPool<?, ?> connectionsPool;
    private final Realm realm;
    private final List<RequestFilter> requestFilters;
    private final List<ResponseFilter> responseFilters;
    private final List<IOExceptionFilter> ioExceptionFilters;
    private final int requestCompressionLevel;
    private final boolean resumableDownload;

    private AsyncHttpClientConfig(int maxTotalConnections,
                                  int maxConnectionPerHost,
                                  int connectionTimeOutInMs,
                                  int idleConnectionTimeoutInMs,
                                  int requestTimeoutInMs,
                                  boolean redirectEnabled,
                                  int maxDefaultRedirects,
                                  boolean compressionEnabled,
                                  String userAgent,
                                  boolean keepAlive,
                                  ScheduledExecutorService reaper,
                                  ExecutorService applicationThreadPool,
                                  ProxyServer proxyServer,
                                  SSLContext sslContext,
                                  SSLEngineFactory sslEngineFactory,
                                  AsyncHttpProviderConfig<?, ?> providerConfig,
                                  ConnectionsPool<?, ?> connectionsPool, Realm realm,
                                  List<RequestFilter> requestFilters,
                                  List<ResponseFilter> responseFilters,
                                  List<IOExceptionFilter> ioExceptionFilters,
                                  int requestCompressionLevel,
                                  boolean resumableDownload) {

        this.maxTotalConnections = maxTotalConnections;
        this.maxConnectionPerHost = maxConnectionPerHost;
        this.connectionTimeOutInMs = connectionTimeOutInMs;
        this.idleConnectionTimeoutInMs = idleConnectionTimeoutInMs;
        this.requestTimeoutInMs = requestTimeoutInMs;
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
        this.resumableDownload = resumableDownload;

        if (reaper == null) {
            this.reaper = Executors.newSingleThreadScheduledExecutor(new ThreadFactory(){
                public Thread newThread(Runnable r) {
                    return new Thread(r,"AsyncHttpClient-Reaper");
                }
            });
        } else {
            this.reaper = reaper;
        }

        if (applicationThreadPool == null) {
            this.applicationThreadPool = Executors.newCachedThreadPool();
        } else {
            this.applicationThreadPool = applicationThreadPool;
        }
        this.proxyServer = proxyServer;
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
     * Return the maximum number of connections an {@link com.ning.http.client.AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections an {@link com.ning.http.client.AsyncHttpClient} can handle.
     */
    public int getMaxTotalConnections() {
        return maxTotalConnections;
    }

    /**
     * Return the maximum number of connections per hosts an {@link com.ning.http.client.AsyncHttpClient} can handle.
     *
     * @return the maximum number of connections per host an {@link com.ning.http.client.AsyncHttpClient} can handle.
     */
    public int getMaxConnectionPerHost() {
        return maxConnectionPerHost;
    }

    /**
     * Return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can wait when connecting to a remote host
     *
     * @return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can wait when connecting to a remote host
     */
    public int getConnectionTimeoutInMs() {
        return connectionTimeOutInMs;
    }

    /**
     * Return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can stay idle.
     *
     * @return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can stay idle.
     */
    public int getIdleConnectionTimeoutInMs() {
        return idleConnectionTimeoutInMs;
    }

    /**
     * Return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} wait for a response
     *
     * @return the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} wait for a response
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
     * An instance of {@link com.ning.http.client.ProxyServer} used by an {@link AsyncHttpClient}
     *
     * @return instance of {@link com.ning.http.client.ProxyServer}
     */
    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    /**
     * Return an instance of {@link SSLContext} used for SSL connection.
     * @return an instance of {@link SSLContext} used for SSL connection.
     */
    public SSLContext getSSLContext() {
        return sslContext;
    }

    /**
     * Return an instance of {@link ConnectionsPool}
     * @return an instance of {@link ConnectionsPool}
     */
    public ConnectionsPool<?, ?> getConnectionsPool(){
        return connectionsPool;
    }

    /**
     * Return an instance of {@link SSLEngineFactory} used for SSL connection.
     * @return an instance of {@link SSLEngineFactory} used for SSL connection.
     */
    public SSLEngineFactory getSSLEngineFactory() {
        if (sslEngineFactory == null) {
            return new SSLEngineFactory()
            {
                public SSLEngine newSSLEngine()
                {
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
     * Return the {@link com.ning.http.client.AsyncHttpProviderConfig}
     * @return the {@link com.ning.http.client.AsyncHttpProviderConfig}
     */
    public AsyncHttpProviderConfig<?,?> getAsyncHttpProviderConfig() {
        return providerConfig;
    }

    /**
     * Return the current {@link Realm}}
     * @return the current {@link Realm}}
     */
    public Realm getRealm(){
        return realm;
    }

    /**
     * Return the list of {@link RequestFilter}
     * @return Unmodifiable list of {@link ResponseFilter}
     */
    public List<RequestFilter> getRequestFilters(){
        return Collections.unmodifiableList(requestFilters);
    }

    /**
     * Return the list of {@link ResponseFilter}
     * @return Unmodifiable list of {@link ResponseFilter}
     */
    public List<ResponseFilter> getResponseFilters(){
        return Collections.unmodifiableList(responseFilters);
    }

    /**
     * Return the list of {@link java.io.IOException}
     * @return Unmodifiable list of {@link java.io.IOException}
     */
    public List<IOExceptionFilter> getIOExceptionFilters(){
        return Collections.unmodifiableList(ioExceptionFilters);
    }

    /**
     * Return the compression level, or -1 if no compression is used.
     * @return the compression level, or -1 if no compression is use
     */
    public int getRequestCompressionLevel() {
        return requestCompressionLevel;
    }

    /**
     * Is the resumable download features enabled.
     *
     * @return true if enabled. Default is false;
     */
    public boolean isResumableDownloadEnabled() {
        return resumableDownload;
    }

    /**
     * Builder for an {@link AsyncHttpClient}
     */
    public static class Builder {
        private int defaultMaxTotalConnections = Integer.getInteger(ASYNC_CLIENT + "defaultMaxTotalConnections", -1);
        private int defaultMaxConnectionPerHost = Integer.getInteger(ASYNC_CLIENT + "defaultMaxConnectionsPerHost", -1);
        private int defaultConnectionTimeOutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultConnectionTimeoutInMS", 60 * 1000);
        private int defaultIdleConnectionTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultIdleConnectionTimeoutInMS", 60 * 1000);
        private int defaultRequestTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultRequestTimeoutInMS", 60 * 1000);
        private boolean redirectEnabled = Boolean.getBoolean(ASYNC_CLIENT + "defaultRedirectsEnabled");
        private int maxDefaultRedirects = Integer.getInteger(ASYNC_CLIENT + "defaultMaxRedirects", 5);
        private boolean compressionEnabled = Boolean.getBoolean(ASYNC_CLIENT + "compressionEnabled");
        private String userAgent = System.getProperty(ASYNC_CLIENT + "userAgent", "NING/1.0");
        private boolean allowPoolingConnection = true;
        private ScheduledExecutorService reaper = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(),
                new ThreadFactory() {
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "AsyncHttpClient-Reaper");
                    }
                });
        private ExecutorService applicationThreadPool = Executors.newCachedThreadPool();
        private ProxyServer proxyServer = null;
        private SSLContext sslContext;
        private SSLEngineFactory sslEngineFactory;
        private AsyncHttpProviderConfig<?,?> providerConfig;
        private ConnectionsPool<?, ?> connectionsPool;
        private Realm realm;
        private int requestCompressionLevel = -1;

        private final List<RequestFilter> requestFilters = new LinkedList<RequestFilter>();
        private final List<ResponseFilter> responseFilters = new LinkedList<ResponseFilter>();
        private final List<IOExceptionFilter> ioExceptionFilters = new LinkedList<IOExceptionFilter>();
        private boolean resumableDownload = false;

        public Builder() {
        }

        /**
         * Set the maximum number of connections an {@link com.ning.http.client.AsyncHttpClient} can handle.
         *
         * @param defaultMaxTotalConnections the maximum number of connections an {@link com.ning.http.client.AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaximumConnectionsTotal(int defaultMaxTotalConnections) {
            this.defaultMaxTotalConnections = defaultMaxTotalConnections;
            return this;
        }

        /**
         * Set the maximum number of connections per hosts an {@link com.ning.http.client.AsyncHttpClient} can handle.
         *
         * @param defaultMaxConnectionPerHost the maximum number of connections per host an {@link com.ning.http.client.AsyncHttpClient} can handle.
         * @return a {@link Builder}
         */
        public Builder setMaximumConnectionsPerHost(int defaultMaxConnectionPerHost) {
            this.defaultMaxConnectionPerHost = defaultMaxConnectionPerHost;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can wait when connecting to a remote host
         *
         * @param defaultConnectionTimeOutInMs the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can wait when connecting to a remote host
         * @return a {@link Builder}
         */
        public Builder setConnectionTimeoutInMs(int defaultConnectionTimeOutInMs) {
            this.defaultConnectionTimeOutInMs = defaultConnectionTimeOutInMs;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can stay idle.
         *
         * @param defaultIdleConnectionTimeoutInMs
         *         the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} can stay idle.
         * @return a {@link Builder}
         */
        public Builder setIdleConnectionTimeoutInMs(int defaultIdleConnectionTimeoutInMs) {
            this.defaultIdleConnectionTimeoutInMs = defaultIdleConnectionTimeoutInMs;
            return this;
        }

        /**
         * Set the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} wait for a response
         *
         * @param defaultRequestTimeoutInMs the maximum time in millisecond an {@link com.ning.http.client.AsyncHttpClient} wait for a response
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
         * @deprecated - Use {@link com.ning.http.client.AsyncHttpClientConfig.Builder#setAllowPoolingConnection(boolean)}
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
            if (this.reaper != null) this.reaper.shutdown();
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
            if (this.applicationThreadPool != null) this.applicationThreadPool.shutdown();
            this.applicationThreadPool = applicationThreadPool;
            return this;
        }

        /**
         * Set an instance of {@link com.ning.http.client.ProxyServer} used by an {@link AsyncHttpClient}
         *
         * @param proxyServer instance of {@link com.ning.http.client.ProxyServer}
         * @return a {@link Builder}
         */
        public Builder setProxyServer(ProxyServer proxyServer) {
            this.proxyServer = proxyServer;
            return this;
        }

        /**
         * Set the {@link SSLEngineFactory} for secure connection.
         * 
         * @param sslEngineFactory the {@link SSLEngineFactory} for secure connection
         * @return a {@link Builder}
         */
        public Builder setSSLEngineFactory(SSLEngineFactory sslEngineFactory){
            this.sslEngineFactory = sslEngineFactory;
            return this;
        }
        
        /**
         * Set the {@link SSLContext} for secure connection.
         * 
         * @param sslContext the {@link SSLContext} for secure connection
         * @return a {@link Builder}
         */
        public Builder setSSLContext(final SSLContext sslContext){
            this.sslEngineFactory = new SSLEngineFactory()
            {
                public SSLEngine newSSLEngine() throws GeneralSecurityException
                {
                    SSLEngine sslEngine = sslContext.createSSLEngine();
                    sslEngine.setUseClientMode(true);
                    return sslEngine;
                }
            };
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Set the {@link com.ning.http.client.AsyncHttpProviderConfig}
         * @param providerConfig the {@link com.ning.http.client.AsyncHttpProviderConfig}
         * @return a {@link Builder}
         */
        public Builder setAsyncHttpClientProviderConfig(AsyncHttpProviderConfig<?, ?> providerConfig) {
            this.providerConfig = providerConfig;
            return this;
        }

        /**
         * Set the {@link ConnectionsPool}
         * @param connectionsPool the {@link ConnectionsPool}
         * @return a {@link Builder}
         */
        public Builder setConnectionsPool(ConnectionsPool<?, ?> connectionsPool) {
            this.connectionsPool = connectionsPool;
            return this;
        }

        /**
         * Set the {@link Realm}  that will be used for all requests.
         * @param realm   the {@link Realm}
         * @return a {@link Builder}
         */
        public Builder setRealm(Realm realm) {
            this.realm = realm;
            return this;
        }

        /**
         * Add an {@link com.ning.http.client.filter.RequestFilter} that will be invoked before {@link com.ning.http.client.AsyncHttpClient#executeRequest(Request)}
         * @param requestFilter  {@link com.ning.http.client.filter.RequestFilter}
         * @return this
         */
        public Builder addRequestFilter(RequestFilter requestFilter) {
            requestFilters.add(requestFilter);
            return this;
        }

        /**
         * Remove an {@link com.ning.http.client.filter.RequestFilter} that will be invoked before {@link com.ning.http.client.AsyncHttpClient#executeRequest(Request)}
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
         * Create a config builder with values taken from the given prototype configuration.
         * 
         * @param prototype the configuration to use as a prototype.
         */
        public Builder( AsyncHttpClientConfig prototype )
        {
            allowPoolingConnection = prototype.getAllowPoolingConnection();
            providerConfig = prototype.getAsyncHttpProviderConfig();
            connectionsPool = prototype.getConnectionsPool();
            defaultConnectionTimeOutInMs = prototype.getConnectionTimeoutInMs();
            defaultIdleConnectionTimeoutInMs = prototype.getIdleConnectionTimeoutInMs();
            allowPoolingConnection = prototype.getKeepAlive();
            defaultMaxConnectionPerHost = prototype.getMaxConnectionPerHost();
            maxDefaultRedirects = prototype.getMaxRedirects();
            defaultMaxTotalConnections = prototype.getMaxTotalConnections();
            proxyServer = prototype.getProxyServer();
            realm = prototype.getRealm();
            defaultRequestTimeoutInMs = prototype.getRequestTimeoutInMs();
            sslContext = prototype.getSSLContext();
            sslEngineFactory = prototype.getSSLEngineFactory();
            userAgent = prototype.getUserAgent();

            requestFilters.clear();
            responseFilters.clear();

            requestFilters.addAll( prototype.getRequestFilters() );
            responseFilters.addAll( prototype.getResponseFilters() );
        }

        /**
         * Is the resumable download features enabled.
         * @return true if enabled. Default is false;
         */
        public boolean isResumableDownload() {
            return resumableDownload;
        }

        /**
         * Set to true to enabled resumable download. Be aware that if you enable this mechanism, an {@link AsyncHandler#onBodyPartReceived(HttpResponseBodyPart)}
         * will ALWAYS be invoked, in case of a JVM crash, with the remaining bytes and will never receive the bytes it already digested. An application
         * not taking care of that fact may digest corrupted bytes or produce corrupted file.
         *          *
         * See {@link com.ning.http.client.resumable.ResumableAsyncHandler}
         * description for more information about the mechanism and how it can be customized and used as a normal
         * {@link AsyncHandler}. 
         *
         * @param resumableDownload true to enabled it.
         * @return this
         */
        public Builder setResumableDownload(boolean resumableDownload) {
            this.resumableDownload = resumableDownload;
            return this;
        }

        /**
         * Build an {@link AsyncHttpClientConfig}
         *
         * @return an {@link AsyncHttpClientConfig}
         */
        public AsyncHttpClientConfig build() {
            return new AsyncHttpClientConfig(defaultMaxTotalConnections,
                    defaultMaxConnectionPerHost,
                    defaultConnectionTimeOutInMs,
                    defaultIdleConnectionTimeoutInMs,
                    defaultRequestTimeoutInMs,
                    redirectEnabled,
                    maxDefaultRedirects,
                    compressionEnabled,
                    userAgent,
                    allowPoolingConnection,
                    reaper,
                    applicationThreadPool,
                    proxyServer,
                    sslContext,
                    sslEngineFactory,
                    providerConfig,
                    connectionsPool,
                    realm,
                    requestFilters,
                    responseFilters,
                    ioExceptionFilters,
                    requestCompressionLevel,
                    resumableDownload);
        }
    }
}

