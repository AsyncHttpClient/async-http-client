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
package org.asynchttpclient;

import org.asynchttpclient.util.AsyncPropertiesHelper;

public final class AsyncHttpClientConfigDefaults {

    private AsyncHttpClientConfigDefaults() {
    }

    public static final String ASYNC_CLIENT = AsyncHttpClientConfig.class.getName() + ".";

    public static int defaultMaxConnections() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "maxConnections");
    }

    public static int defaultMaxConnectionsPerHost() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "maxConnectionsPerHost");
    }

    public static int defaultConnectTimeout() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "connectTimeout");
    }

    public static int defaultPooledConnectionIdleTimeout() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "pooledConnectionIdleTimeout");
    }

    public static int defaultReadTimeout() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "readTimeout");
    }

    public static int defaultRequestTimeout() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "requestTimeout");
    }

    public static int defaultWebSocketTimeout() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "webSocketTimeout");
    }

    public static int defaultConnectionTTL() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "connectionTTL");
    }

    public static boolean defaultFollowRedirect() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "followRedirect");
    }

    public static int defaultMaxRedirects() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "maxRedirects");
    }

    public static boolean defaultCompressionEnforced() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "compressionEnforced");
    }

    public static String defaultUserAgent() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getString(ASYNC_CLIENT + "userAgent");
    }

    public static int defaultIoThreadMultiplier() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "ioThreadMultiplier");
    }

    public static boolean defaultUseProxySelector() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "useProxySelector");
    }

    public static boolean defaultUseProxyProperties() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "useProxyProperties");
    }

    public static boolean defaultStrict302Handling() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "strict302Handling");
    }

    public static boolean defaultAllowPoolingConnections() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "allowPoolingConnections");
    }

    public static boolean defaultUseRelativeURIsWithConnectProxies() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "useRelativeURIsWithConnectProxies");
    } 

    public static int defaultMaxRequestRetry() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "maxRequestRetry");
    }

    public static boolean defaultAllowPoolingSslConnections() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "allowPoolingSslConnections");
    }

    public static boolean defaultDisableUrlEncodingForBoundRequests() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "disableUrlEncodingForBoundRequests");
    }

    public static boolean defaultRemoveQueryParamOnRedirect() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "removeQueryParamOnRedirect");
    }

    public static boolean defaultSpdyEnabled() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "spdyEnabled");
    }

    public static int defaultSpdyInitialWindowSize() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "spdyInitialWindowSize");
    }

    public static int defaultSpdyMaxConcurrentStreams() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT + "spdyMaxConcurrentStreams");
    }
    
    public static boolean defaultAcceptAnyCertificate() {
        return AsyncPropertiesHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT + "acceptAnyCertificate");
    }
}
