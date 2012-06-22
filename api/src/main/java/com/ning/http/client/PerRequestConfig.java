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

/**
 * Per request configuration.
 *
 * @author Hubert Iwaniuk
 * @deprecated Per request properties are set on request directly or via builder. This class will be gone in next major release.
 */
public class PerRequestConfig {
    private final ProxyServer proxyServer;
    private int requestTimeoutInMs;

    public PerRequestConfig() {
        this(null, 0);
    }

    public PerRequestConfig(ProxyServer proxyServer, int requestTimeoutInMs) {
        this.proxyServer = proxyServer;
        this.requestTimeoutInMs = requestTimeoutInMs;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public int getRequestTimeoutInMs() {
        return requestTimeoutInMs;
    }

    public void setRequestTimeoutInMs(int requestTimeoutInMs) {
        this.requestTimeoutInMs = requestTimeoutInMs;
    }
}
