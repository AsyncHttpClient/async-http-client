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



import javax.net.ssl.HostnameVerifier;

import org.asynchttpclient.util.DefaultHostnameVerifier;
import static org.asynchttpclient.util.MiscUtil.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AsyncHttpClientConfigDefaults {
	public final static Logger logger = LoggerFactory.getLogger(AsyncHttpClientConfigDefaults.class);

    private AsyncHttpClientConfigDefaults() {
    }

    public static final String ASYNC_CLIENT = AsyncHttpClientConfig.class.getName() + ".";

    public static int defaultMaxTotalConnections() {
        return getIntValue(ASYNC_CLIENT + "maxTotalConnections", -1);
    }

    public static int defaultMaxConnectionPerHost() {
        return getIntValue(ASYNC_CLIENT + "maxConnectionsPerHost", -1);
    }

    public static int defaultConnectionTimeOutInMs() {
        return getIntValue(ASYNC_CLIENT + "connectionTimeoutInMs", 60 * 1000);
    }

    public static int defaultIdleConnectionInPoolTimeoutInMs() {
        return getIntValue(ASYNC_CLIENT + "idleConnectionInPoolTimeoutInMs", 60 * 1000);
    }

    public static int defaultIdleConnectionTimeoutInMs() {
        return getIntValue(ASYNC_CLIENT + "idleConnectionTimeoutInMs", 60 * 1000);
    }

    public static int defaultRequestTimeoutInMs() {
        return getIntValue(ASYNC_CLIENT + "requestTimeoutInMs", 60 * 1000);
    }

    public static int defaultWebSocketIdleTimeoutInMs() {
        return getIntValue(ASYNC_CLIENT + "webSocketTimoutInMS", 15 * 60 * 1000);
    }

    public static int defaultMaxConnectionLifeTimeInMs() {
        return getIntValue(ASYNC_CLIENT + "maxConnectionLifeTimeInMs", -1);
    }

    public static boolean defaultRedirectEnabled() {
        return getBooleanValue(ASYNC_CLIENT + "redirectsEnabled",false);
    }

    public static int defaultMaxRedirects() {
        return getIntValue(ASYNC_CLIENT + "maxRedirects", 5);
    }

    public static boolean defaultCompressionEnabled() {
        return getBooleanValue(ASYNC_CLIENT + "compressionEnabled",false);
    }

    public static String defaultUserAgent() {
        return System.getProperty(ASYNC_CLIENT + "userAgent", "NING/1.0");
    }

    public static int defaultIoThreadMultiplier() {
        return getIntValue(ASYNC_CLIENT + "ioThreadMultiplier", 2);
    }

    public static boolean defaultUseProxySelector() {
        return getBooleanValue(ASYNC_CLIENT + "useProxySelector",false);
    }

    public static boolean defaultUseProxyProperties() {
        return getBooleanValue(ASYNC_CLIENT + "useProxyProperties",false);
    }

    public static boolean defaultStrict302Handling() {
        return getBooleanValue(ASYNC_CLIENT + "strict302Handling",false);
    }

    public static boolean defaultAllowPoolingConnection() {
        return getBooleanValue(ASYNC_CLIENT + "allowPoolingConnection", true);
    }

    public static boolean defaultUseRelativeURIsWithSSLProxies() {
        return getBooleanValue(ASYNC_CLIENT + "useRelativeURIsWithSSLProxies", true);
    }

    // unused/broken, left there for compatibility, fixed in Netty 4
    public static int defaultRequestCompressionLevel() {
        return getIntValue(ASYNC_CLIENT + "requestCompressionLevel", -1);
    }

    public static int defaultMaxRequestRetry() {
        return getIntValue(ASYNC_CLIENT + "maxRequestRetry", 5);
    }

    public static boolean defaultAllowSslConnectionPool() {
        return getBooleanValue(ASYNC_CLIENT + "allowSslConnectionPool", true);
    }

    public static boolean defaultUseRawUrl() {
        return getBooleanValue(ASYNC_CLIENT + "useRawUrl",false);
    }

    public static boolean defaultRemoveQueryParamOnRedirect() {
        return getBooleanValue(ASYNC_CLIENT + "removeQueryParamOnRedirect", true);
    }

    public static HostnameVerifier defaultHostnameVerifier() {
        return new DefaultHostnameVerifier();
    }

    public static boolean defaultSpdyEnabled() {
        return getBooleanValue(ASYNC_CLIENT + "spdyEnabled",false);
    }

    public static int defaultSpdyInitialWindowSize() {
        return getIntValue(ASYNC_CLIENT + "spdyInitialWindowSize", 10 * 1024 * 1024);
    }

    public static int defaultSpdyMaxConcurrentStreams() {
        return getIntValue(ASYNC_CLIENT + "spdyMaxConcurrentStreams", 100);
    }

}
