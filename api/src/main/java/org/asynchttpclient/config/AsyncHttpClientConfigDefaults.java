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
package org.asynchttpclient.config;


public final class AsyncHttpClientConfigDefaults {

    private AsyncHttpClientConfigDefaults() {
    }

    public static final String ASYNC_CLIENT_CONFIG_ROOT = "org.asynchttpclient.";

    public static int defaultMaxConnections() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "maxConnections");
    }

    public static int defaultMaxConnectionsPerHost() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "maxConnectionsPerHost");
    }

    public static int defaultConnectTimeout() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "connectTimeout");
    }

    public static int defaultPooledConnectionIdleTimeout() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "pooledConnectionIdleTimeout");
    }

    public static int defaultReadTimeout() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "readTimeout");
    }

    public static int defaultRequestTimeout() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "requestTimeout");
    }

    public static int defaultWebSocketTimeout() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "webSocketTimeout");
    }

    public static int defaultConnectionTTL() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "connectionTTL");
    }

    public static boolean defaultFollowRedirect() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "followRedirect");
    }

    public static int defaultMaxRedirects() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "maxRedirects");
    }

    public static boolean defaultCompressionEnforced() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "compressionEnforced");
    }

    public static String defaultUserAgent() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getString(ASYNC_CLIENT_CONFIG_ROOT + "userAgent");
    }

    public static int defaultIoThreadMultiplier() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "ioThreadMultiplier");
    }

    public static boolean defaultUseProxySelector() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "useProxySelector");
    }

    public static boolean defaultUseProxyProperties() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "useProxyProperties");
    }

    public static boolean defaultStrict302Handling() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "strict302Handling");
    }

    public static boolean defaultAllowPoolingConnections() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "allowPoolingConnections");
    }

    public static boolean defaultUseRelativeURIsWithConnectProxies() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "useRelativeURIsWithConnectProxies");
    } 

    public static int defaultMaxRequestRetry() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "maxRequestRetry");
    }

    public static boolean defaultAllowPoolingSslConnections() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "allowPoolingSslConnections");
    }

    public static boolean defaultDisableUrlEncodingForBoundRequests() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "disableUrlEncodingForBoundRequests");
    }

    public static boolean defaultAcceptAnyCertificate() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "acceptAnyCertificate");
    }

    public static Integer defaultSslSessionCacheSize() {
        return Integer.getInteger(ASYNC_CLIENT_CONFIG_ROOT + "sslSessionCacheSize");
    }

    public static Integer defaultSslSessionTimeout() {
        return Integer.getInteger(ASYNC_CLIENT_CONFIG_ROOT + "sslSessionTimeout");
    }
}
