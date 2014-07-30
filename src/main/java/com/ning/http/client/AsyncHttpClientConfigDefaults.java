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

import com.ning.http.util.AllowAllHostnameVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.ning.http.util.MiscUtil.getBoolean;

import javax.net.ssl.HostnameVerifier;

public final class AsyncHttpClientConfigDefaults {

    private final static Logger log = LoggerFactory.getLogger(AsyncHttpClientConfigDefaults.class);

    private AsyncHttpClientConfigDefaults() {
    }

    public static final String ASYNC_CLIENT = AsyncHttpClientConfig.class.getName() + ".";

    public static int defaultMaxTotalConnections() {
        return Integer.getInteger(ASYNC_CLIENT + "maxTotalConnections", -1);
    }

    public static int defaultMaxConnectionPerHost() {
        return Integer.getInteger(ASYNC_CLIENT + "maxConnectionsPerHost", -1);
    }

    public static int defaultConnectionTimeOutInMs() {
        return Integer.getInteger(ASYNC_CLIENT + "connectionTimeoutInMs", 60 * 1000);
    }

    public static int defaultIdleConnectionInPoolTimeoutInMs() {
        return Integer.getInteger(ASYNC_CLIENT + "idleConnectionInPoolTimeoutInMs", 60 * 1000);
    }

    public static int defaultIdleConnectionTimeoutInMs() {
        return Integer.getInteger(ASYNC_CLIENT + "idleConnectionTimeoutInMs", 60 * 1000);
    }

    public static int defaultRequestTimeoutInMs() {
        return Integer.getInteger(ASYNC_CLIENT + "requestTimeoutInMs", 60 * 1000);
    }

    public static int defaultWebSocketIdleTimeoutInMs() {
        return Integer.getInteger(ASYNC_CLIENT + "webSocketTimoutInMS", 15 * 60 * 1000);
    }

    public static int defaultMaxConnectionLifeTimeInMs() {
        return Integer.getInteger(ASYNC_CLIENT + "maxConnectionLifeTimeInMs", -1);
    }

    public static boolean defaultRedirectEnabled() {
        return Boolean.getBoolean(ASYNC_CLIENT + "redirectsEnabled");
    }

    public static int defaultMaxRedirects() {
        return Integer.getInteger(ASYNC_CLIENT + "maxRedirects", 5);
    }

    public static boolean defaultCompressionEnabled() {
        return Boolean.getBoolean(ASYNC_CLIENT + "compressionEnabled");
    }

    public static String defaultUserAgent() {
        return System.getProperty(ASYNC_CLIENT + "userAgent", "NING/1.0");
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

    public static boolean defaultAllowPoolingConnection() {
        return getBoolean(ASYNC_CLIENT + "allowPoolingConnection", true);
    }

    /**
     * @deprecated Use defaultUseRelativeURIsWithConnectProxies instead.
     */
    @Deprecated
    public static boolean defaultUseRelativeURIsWithSSLProxies() {
        String systemPropValue = System.getProperty(ASYNC_CLIENT + "useRelativeURIsWithSSLProxies");
        if (systemPropValue != null) {
            log.warn(ASYNC_CLIENT + "useRelativeURIsWithSSLProxies is deprecated, use " + ASYNC_CLIENT +
                    "defaultUseRelativeURIsWithConnectProxies instead");
            return systemPropValue.equalsIgnoreCase("true");
        } else {
            return true;
        }
    }

    public static boolean defaultUseRelativeURIsWithConnectProxies() {
        return getBoolean(ASYNC_CLIENT + "useRelativeURIsWithConnectProxies", defaultUseRelativeURIsWithSSLProxies());
    }

    // unused/broken, left there for compatibility, fixed in Netty 4
    public static int defaultRequestCompressionLevel() {
        return Integer.getInteger(ASYNC_CLIENT + "requestCompressionLevel", -1);
    }

    public static int defaultMaxRequestRetry() {
        return Integer.getInteger(ASYNC_CLIENT + "maxRequestRetry", 5);
    }

    public static boolean defaultAllowSslConnectionPool() {
        return getBoolean(ASYNC_CLIENT + "allowSslConnectionPool", true);
    }

    public static boolean defaultUseRawUrl() {
        return Boolean.getBoolean(ASYNC_CLIENT + "useRawUrl");
    }

    public static boolean defaultRemoveQueryParamOnRedirect() {
        return getBoolean(ASYNC_CLIENT + "removeQueryParamOnRedirect", true);
    }

    public static HostnameVerifier defaultHostnameVerifier() {
        return new AllowAllHostnameVerifier();
    }
}
