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

    public static String defaultThreadPoolName() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getString(ASYNC_CLIENT_CONFIG_ROOT + "threadPoolName");
    }

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

    public static int defaultConnectionTtl() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "connectionTtl");
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

    public static String[] defaultEnabledProtocols() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getStringArray(ASYNC_CLIENT_CONFIG_ROOT + "enabledProtocols");
    }

    public static boolean defaultUseProxySelector() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "useProxySelector");
    }

    public static boolean defaultUseProxyProperties() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "useProxyProperties");
    }

    public static boolean defaultValidateResponseHeaders() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "validateResponseHeaders");
    }

    public static boolean defaultStrict302Handling() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "strict302Handling");
    }

    public static boolean defaultKeepAlive() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "keepAlive");
    }

    public static int defaultMaxRequestRetry() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "maxRequestRetry");
    }

    public static boolean defaultDisableUrlEncodingForBoundRequests() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "disableUrlEncodingForBoundRequests");
    }

    public static boolean defaultUseOpenSsl() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "useOpenSsl");
    }

    public static boolean defaultAcceptAnyCertificate() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "acceptAnyCertificate");
    }

    public static int defaultSslSessionCacheSize() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "sslSessionCacheSize");
    }

    public static int defaultSslSessionTimeout() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "sslSessionTimeout");
    }

    public static int defaultHttpClientCodecMaxInitialLineLength() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "httpClientCodecMaxInitialLineLength");
    }

    public static int defaultHttpClientCodecMaxHeaderSize() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "httpClientCodecMaxHeaderSize");
    }

    public static int defaultHttpClientCodecMaxChunkSize() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "httpClientCodecMaxChunkSize");
    }

    public static boolean defaultDisableZeroCopy() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "disableZeroCopy");
    }

    public static int defaultHandshakeTimeout() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "handshakeTimeout");
    }

    public static int defaultChunkedFileChunkSize() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "chunkedFileChunkSize");
    }

    public static int defaultWebSocketMaxBufferSize() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "webSocketMaxBufferSize");
    }

    public static int defaultWebSocketMaxFrameSize() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "webSocketMaxFrameSize");
    }

    public static boolean defaultKeepEncodingHeader() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "keepEncodingHeader");
    }

    public static int defaultShutdownQuietPeriod() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "shutdownQuietPeriod");
    }

    public static int defaultShutdownTimeout() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getInt(ASYNC_CLIENT_CONFIG_ROOT + "shutdownTimeout");
    }

    public static boolean defaultUseNativeTransport() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "useNativeTransport");
    }

    public static boolean defaultUsePooledMemory() {
        return AsyncHttpClientConfigHelper.getAsyncHttpClientConfig().getBoolean(ASYNC_CLIENT_CONFIG_ROOT + "usePooledMemory");
    }
}
