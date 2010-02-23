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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class AsyncHttpClientConfig {

    private final static String ASYNC_CLIENT = AsyncHttpClient.class.getName();

    private final int maxTotalConnections;
    private final int maxConnectionPerHost;
    private final long connectionTimeOutInMs;
    private final long idleConnectionTimeoutInMs;
    private final int requestTimeoutInMs;
    private final boolean redirectEnabled;
    private final int maxDefaultRedirects;
    private final boolean compressionEnabled;
    private final String userAgent;
    private final boolean keepAlive;
    private final ScheduledExecutorService reaper;
    private final ExecutorService applicationThreadPool;
    private final ProxyServer proxyServer;

    private AsyncHttpClientConfig(int maxTotalConnections,
                                  int maxConnectionPerHost,
                                  long connectionTimeOutInMs,
                                  long idleConnectionTimeoutInMs,
                                  int requestTimeoutInMs,
                                  boolean redirectEnabled,
                                  int maxDefaultRedirects,
                                  boolean compressionEnabled,
                                  String userAgent,
                                  boolean keepAlive,
                                  ScheduledExecutorService reaper,
                                  ExecutorService applicationThreadPool,
                                  ProxyServer proxyServer) {
        
        this.maxTotalConnections = maxTotalConnections;
        this.maxConnectionPerHost = maxConnectionPerHost;
        this.connectionTimeOutInMs = connectionTimeOutInMs;
        this.idleConnectionTimeoutInMs = idleConnectionTimeoutInMs;
        this.requestTimeoutInMs = requestTimeoutInMs;
        this.redirectEnabled = redirectEnabled;
        this.maxDefaultRedirects = maxDefaultRedirects;
        this.compressionEnabled = compressionEnabled;
        this.userAgent = userAgent;
        this.keepAlive = keepAlive;

        if (reaper == null){
            this.reaper = Executors.newScheduledThreadPool(1);
        } else {
            this.reaper = reaper;
        }

        if (applicationThreadPool == null){
            this.applicationThreadPool = Executors.newCachedThreadPool();
        } else {
            this.applicationThreadPool = applicationThreadPool;            
        }
        this.proxyServer = proxyServer;
    }

    public ScheduledExecutorService reaper() {
        return reaper;
    }

    public int getMaxTotalConnections() {
        return maxTotalConnections;
    }

    public int getMaxConnectionPerHost() {
        return maxConnectionPerHost;
    }

    public long getDefaultConnectionTimeoutInMs() {
        return connectionTimeOutInMs;
    }

    public long getDefaultIdleConnectionTimeout() {
        return idleConnectionTimeoutInMs;
    }

    public int getDefaultRequestTimeout() {
        return requestTimeoutInMs;
    }

    public boolean isRedirectEnabled() {
        return redirectEnabled;
    }

    public int getDefaultMaxRedirects() {
        return maxDefaultRedirects;
    }

    public boolean getKeepAlive(){
        return keepAlive;
    }

    public String getUserAgent(){
        return userAgent;
    }

    public boolean isCompressionEnabled(){
        return compressionEnabled;
    }

    public ExecutorService executorService(){
        return applicationThreadPool;
    }

    public ProxyServer getProxyServer(){
        return proxyServer;
    }

    public static class Builder {
        private int defaultMaxTotalConnections = Integer.getInteger(ASYNC_CLIENT + ".defaultMaxTotalConnections", 2000);
        private int defaultMaxConnectionPerHost = Integer.getInteger(ASYNC_CLIENT + "defaultMaxConnectionsPerHost", 2000);
        private long defaultConnectionTimeOutInMs = Long.getLong(ASYNC_CLIENT + "defaultConnectionTimeoutInMS", 60 * 1000L);
        private long defaultIdleConnectionTimeoutInMs = Long.getLong(ASYNC_CLIENT + "defaultIdleConnectionTimeoutInMS", 15 * 1000L);
        private int defaultRequestTimeoutInMs = Integer.getInteger(ASYNC_CLIENT + "defaultRequestTimeoutInMS", 60 * 1000);
        private boolean redirectEnabled = Boolean.getBoolean(ASYNC_CLIENT + "defaultRedirectsEnabled");
        private int maxDefaultRedirects = Integer.getInteger(ASYNC_CLIENT + "defaultMaxRedirects", 5);
        private boolean compressionEnabled = false;
        private String userAgent = "NING/1.0";
        private boolean keepAlive = true;
        private ScheduledExecutorService reaper = Executors.newScheduledThreadPool(1);
        private ExecutorService applicationThreadPool = Executors.newCachedThreadPool();
        private ProxyServer proxyServer = null; 

        public Builder() {
        }

        public Builder setMaximumConnectionsTotal(int defaultMaxTotalConnections) {
            this.defaultMaxTotalConnections = defaultMaxTotalConnections;
            return this;
        }

        public Builder setMaximumConnectionsPerHost(int defaultMaxConnectionPerHost) {
            this.defaultMaxConnectionPerHost = defaultMaxConnectionPerHost;
            return this;
        }

        public Builder setConnectionTimeout(long defaultConnectionTimeOutInMs) {
            this.defaultConnectionTimeOutInMs = defaultConnectionTimeOutInMs;
            return this;
        }

        public Builder setIdleConnectionTimeout(long defaultIdleConnectionTimeoutInMs) {
            this.defaultIdleConnectionTimeoutInMs = defaultIdleConnectionTimeoutInMs;
            return this;
        }

        public Builder setRequestTimeout(int defaultRequestTimeoutInMs) {
            this.defaultRequestTimeoutInMs = defaultRequestTimeoutInMs;
            return this;
        }

        public Builder setFollowRedirects(boolean redirectEnabled) {
            this.redirectEnabled = redirectEnabled;
            return this;
        }

        public Builder setMaximumNumberOfRedirects(int maxDefaultRedirects) {
            this.maxDefaultRedirects = maxDefaultRedirects;
            return this;
        }

        public Builder setCompressionEnabled(boolean compressionEnabled) {
            this.compressionEnabled = compressionEnabled;
            return this;
        }

        public Builder setUserAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder setKeepAlive(boolean keepAlive){
            this.keepAlive = keepAlive;
            return this;
        }

        public Builder setScheduledExecutorService(ScheduledExecutorService reaper){
            this.reaper = reaper; 
            return this;
        }

        public Builder setExecutorService(ExecutorService applicationThreadPool){
            this.applicationThreadPool = applicationThreadPool;
            return this;
        }

        public Builder setProxyServer(ProxyServer proxyServer){
            this.proxyServer = proxyServer;
            return this;
        }

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
                    keepAlive,
                    reaper,
                    applicationThreadPool,
                    proxyServer);
        }

    }
}

