/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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

import static com.ning.http.util.MiscUtils.getBoolean;

public final class AsyncHttpClientConfigDefaults {

    private AsyncHttpClientConfigDefaults() {
    }

    public static final String ASYNC_CLIENT = AsyncHttpClientConfig.class.getName() + ".";

    public static int defaultMaxConnections() {
        return Integer.getInteger(ASYNC_CLIENT + "maxConnections", -1);
    }

    public static int defaultMaxConnectionsPerHost() {
        return Integer.getInteger(ASYNC_CLIENT + "maxConnectionsPerHost", -1);
    }

    public static int defaultConnectTimeout() {
        return Integer.getInteger(ASYNC_CLIENT + "connectTimeout", 5 * 1000);
    }

    public static int defaultPooledConnectionIdleTimeout() {
        return Integer.getInteger(ASYNC_CLIENT + "pooledConnectionIdleTimeout", 60 * 1000);
    }

    public static int defaultReadTimeout() {
        return Integer.getInteger(ASYNC_CLIENT + "readTimeout", 60 * 1000);
    }

    public static int defaultRequestTimeout() {
        return Integer.getInteger(ASYNC_CLIENT + "requestTimeout", 60 * 1000);
    }

    public static int defaultWebSocketTimeout() {
        return Integer.getInteger(ASYNC_CLIENT + "webSocketTimeout", 15 * 60 * 1000);
    }

    public static int defaultConnectionTTL() {
        return Integer.getInteger(ASYNC_CLIENT + "connectionTTL", -1);
    }

    public static boolean defaultFollowRedirect() {
        return Boolean.getBoolean(ASYNC_CLIENT + "followRedirect");
    }

    public static int defaultMaxRedirects() {
        return Integer.getInteger(ASYNC_CLIENT + "maxRedirects", 5);
    }

    public static boolean defaultCompressionEnforced() {
        return getBoolean(ASYNC_CLIENT + "compressionEnforced", false);
    }

    public static String defaultUserAgent() {
        return System.getProperty(ASYNC_CLIENT + "userAgent", "AHC/1.0");
    }

    public static int defaultIoThreadMultiplier() {
        return Integer.getInteger(ASYNC_CLIENT + "ioThreadMultiplier", 2);
    }

    public static boolean defaultUseProxySelector() {
        return Boolean.getBoolean(ASYNC_CLIENT + "useProxySelector");
    }

    public static boolean defaultUseProxyProperties() {
        return Boolean.getBoolean(ASYNC_CLIENT + "useProxyProperties");
    }

    public static boolean defaultStrict302Handling() {
        return Boolean.getBoolean(ASYNC_CLIENT + "strict302Handling");
    }

    public static boolean defaultAllowPoolingConnections() {
        return getBoolean(ASYNC_CLIENT + "allowPoolingConnections", true);
    }

    public static boolean defaultUseRelativeURIsWithConnectProxies() {
        return getBoolean(ASYNC_CLIENT + "useRelativeURIsWithConnectProxies", true);
    }

    public static int defaultMaxRequestRetry() {
        return Integer.getInteger(ASYNC_CLIENT + "maxRequestRetry", 5);
    }

    public static boolean defaultAllowPoolingSslConnections() {
        return getBoolean(ASYNC_CLIENT + "allowPoolingSslConnections", true);
    }

    public static boolean defaultDisableUrlEncodingForBoundRequests() {
        return Boolean.getBoolean(ASYNC_CLIENT + "disableUrlEncodingForBoundRequests");
    }

    public static boolean defaultRemoveQueryParamOnRedirect() {
        return getBoolean(ASYNC_CLIENT + "removeQueryParamOnRedirect", true);
    }
    
    public static boolean defaultAcceptAnyCertificate() {
        return getBoolean(ASYNC_CLIENT + "acceptAnyCertificate", false);
    }
}
