/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client;

import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.RequestFilter;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.util.ProxyUtils;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * Simple JavaBean version of  {@link AsyncHttpClientConfig}
 */
public class AsyncHttpClientConfigBean extends AsyncHttpClientConfig {

    public AsyncHttpClientConfigBean() {
        configureExecutors();
        configureDefaults();
        configureFilters();
    }

    void configureFilters() {
        requestFilters = new LinkedList<RequestFilter>();
        responseFilters = new LinkedList<ResponseFilter>();
        ioExceptionFilters = new LinkedList<IOExceptionFilter>();
    }

    void configureDefaults() {
        maxTotalConnections = Integer.getInteger(ASYNC_CLIENT + "defaultMaxTotalConnections", -1);
        maxConnectionPerHost = Integer.getInteger(ASYNC_CLIENT + "defaultMaxConnectionsPerHost", -1);
        connectionTimeOutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultConnectionTimeoutInMS", 60 * 1000);
        idleConnectionInPoolTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultIdleConnectionInPoolTimeoutInMS", 60 * 1000);
        idleConnectionTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultIdleConnectionTimeoutInMS", 60 * 1000);
        requestTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultRequestTimeoutInMS", 60 * 1000);
        redirectEnabled = Boolean.getBoolean(ASYNC_CLIENT + "defaultRedirectsEnabled");
        maxDefaultRedirects = Integer.getInteger(ASYNC_CLIENT + "defaultMaxRedirects", 5);
        compressionEnabled = Boolean.getBoolean(ASYNC_CLIENT + "compressionEnabled");
        userAgent = System.getProperty(ASYNC_CLIENT + "userAgent", "NING/1.0");

        boolean useProxyProperties = Boolean.getBoolean(ASYNC_CLIENT + "useProxyProperties");
        if (useProxyProperties) {
            proxyServer = ProxyUtils.createProxy(System.getProperties());
        }

        allowPoolingConnection = true;
        requestCompressionLevel = -1;
        maxRequestRetry = 5;
        allowSslConnectionPool = true;
        useRawUrl = false;
        removeQueryParamOnRedirect = true;
        hostnameVerifier = new HostnameVerifier() {

            public boolean verify(String s, SSLSession sslSession) {
                return true;
            }
        };
    }

    void configureExecutors() {
        reaper = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AsyncHttpClient-Reaper");
                t.setDaemon(true);
                return t;
            }
        });
        applicationThreadPool = Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AsyncHttpClient-Callback");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public AsyncHttpClientConfigBean setMaxTotalConnections(int maxTotalConnections) {
        this.maxTotalConnections = maxTotalConnections;
        return this;
    }

    public AsyncHttpClientConfigBean setMaxConnectionPerHost(int maxConnectionPerHost) {
        this.maxConnectionPerHost = maxConnectionPerHost;
        return this;
    }

    public AsyncHttpClientConfigBean setConnectionTimeOutInMs(int connectionTimeOutInMs) {
        this.connectionTimeOutInMs = connectionTimeOutInMs;
        return this;
    }

    public AsyncHttpClientConfigBean setIdleConnectionInPoolTimeoutInMs(int idleConnectionInPoolTimeoutInMs) {
        this.idleConnectionInPoolTimeoutInMs = idleConnectionInPoolTimeoutInMs;
        return this;
    }

    public AsyncHttpClientConfigBean setIdleConnectionTimeoutInMs(int idleConnectionTimeoutInMs) {
        this.idleConnectionTimeoutInMs = idleConnectionTimeoutInMs;
        return this;
    }

    public AsyncHttpClientConfigBean setRequestTimeoutInMs(int requestTimeoutInMs) {
        this.requestTimeoutInMs = requestTimeoutInMs;
        return this;
    }

    public AsyncHttpClientConfigBean setRedirectEnabled(boolean redirectEnabled) {
        this.redirectEnabled = redirectEnabled;
        return this;
    }

    public AsyncHttpClientConfigBean setMaxDefaultRedirects(int maxDefaultRedirects) {
        this.maxDefaultRedirects = maxDefaultRedirects;
        return this;
    }

    public AsyncHttpClientConfigBean setCompressionEnabled(boolean compressionEnabled) {
        this.compressionEnabled = compressionEnabled;
        return this;
    }

    public AsyncHttpClientConfigBean setUserAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    public AsyncHttpClientConfigBean setAllowPoolingConnection(boolean allowPoolingConnection) {
        this.allowPoolingConnection = allowPoolingConnection;
        return this;
    }

    public AsyncHttpClientConfigBean setReaper(ScheduledExecutorService reaper) {
        if (this.reaper != null) {
            this.reaper.shutdownNow();
        }
        this.reaper = reaper;
        return this;
    }

    public AsyncHttpClientConfigBean setApplicationThreadPool(ExecutorService applicationThreadPool) {
        if (this.applicationThreadPool != null) {
            this.applicationThreadPool.shutdownNow();
        }
        this.applicationThreadPool = applicationThreadPool;
        return this;
    }

    public AsyncHttpClientConfigBean setProxyServer(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
        return this;
    }

    public AsyncHttpClientConfigBean setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    public AsyncHttpClientConfigBean setSslEngineFactory(SSLEngineFactory sslEngineFactory) {
        this.sslEngineFactory = sslEngineFactory;
        return this;
    }

    public AsyncHttpClientConfigBean setProviderConfig(AsyncHttpProviderConfig<?, ?> providerConfig) {
        this.providerConfig = providerConfig;
        return this;
    }

    public AsyncHttpClientConfigBean setConnectionsPool(ConnectionsPool<?, ?> connectionsPool) {
        this.connectionsPool = connectionsPool;
        return this;
    }

    public AsyncHttpClientConfigBean setRealm(Realm realm) {
        this.realm = realm;
        return this;
    }

    public AsyncHttpClientConfigBean addRequestFilter(RequestFilter requestFilter) {
        requestFilters.add(requestFilter);
        return this;
    }

    public AsyncHttpClientConfigBean addResponseFilters(ResponseFilter responseFilter) {
        responseFilters.add(responseFilter);
        return this;
    }

    public AsyncHttpClientConfigBean addIoExceptionFilters(IOExceptionFilter ioExceptionFilter) {
        ioExceptionFilters.add(ioExceptionFilter);
        return this;
    }

    public AsyncHttpClientConfigBean setRequestCompressionLevel(int requestCompressionLevel) {
        this.requestCompressionLevel = requestCompressionLevel;
        return this;
    }

    public AsyncHttpClientConfigBean setMaxRequestRetry(int maxRequestRetry) {
        this.maxRequestRetry = maxRequestRetry;
        return this;
    }

    public AsyncHttpClientConfigBean setAllowSslConnectionPool(boolean allowSslConnectionPool) {
        this.allowSslConnectionPool = allowSslConnectionPool;
        return this;
    }

    public AsyncHttpClientConfigBean setUseRawUrl(boolean useRawUrl) {
        this.useRawUrl = useRawUrl;
        return this;
    }

    public AsyncHttpClientConfigBean setRemoveQueryParamOnRedirect(boolean removeQueryParamOnRedirect) {
        this.removeQueryParamOnRedirect = removeQueryParamOnRedirect;
        return this;
    }

    public AsyncHttpClientConfigBean setHostnameVerifier(HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = hostnameVerifier;
        return this;
    }

    public AsyncHttpClientConfigBean setIoThreadMultiplier(int ioThreadMultiplier) {
        this.ioThreadMultiplier = ioThreadMultiplier;
        return this;
    }
}
