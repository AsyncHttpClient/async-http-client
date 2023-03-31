/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient;

import org.junit.jupiter.api.Test;
import org.asynchttpclient.config.AsyncHttpClientConfigDefaults;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;

import java.lang.reflect.Method;

import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.ASYNC_CLIENT_CONFIG_ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class AsyncHttpClientDefaultsTest {

    @Test
    public void testDefaultUseOnlyEpollNativeTransport() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultUseOnlyEpollNativeTransport());
        testBooleanSystemProperty("useOnlyEpollNativeTransport", "defaultUseOnlyEpollNativeTransport", "false");
    }

    @Test
    public void testDefaultMaxTotalConnections() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultMaxConnections(), -1);
        testIntegerSystemProperty("maxConnections", "defaultMaxConnections", "100");
    }

    @Test
    public void testDefaultMaxConnectionPerHost() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultMaxConnectionsPerHost(), -1);
        testIntegerSystemProperty("maxConnectionsPerHost", "defaultMaxConnectionsPerHost", "100");
    }

    @Test
    public void testDefaultConnectTimeOut() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultConnectTimeout(), 5 * 1000);
        testIntegerSystemProperty("connectTimeout", "defaultConnectTimeout", "100");
    }

    @Test
    public void testDefaultPooledConnectionIdleTimeout() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultPooledConnectionIdleTimeout(), 60 * 1000);
        testIntegerSystemProperty("pooledConnectionIdleTimeout", "defaultPooledConnectionIdleTimeout", "100");
    }

    @Test
    public void testDefaultReadTimeout() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultReadTimeout(), 60 * 1000);
        testIntegerSystemProperty("readTimeout", "defaultReadTimeout", "100");
    }

    @Test
    public void testDefaultRequestTimeout() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultRequestTimeout(), 60 * 1000);
        testIntegerSystemProperty("requestTimeout", "defaultRequestTimeout", "100");
    }

    @Test
    public void testDefaultConnectionTtl() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultConnectionTtl(), -1);
        testIntegerSystemProperty("connectionTtl", "defaultConnectionTtl", "100");
    }

    @Test
    public void testDefaultFollowRedirect() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultFollowRedirect());
        testBooleanSystemProperty("followRedirect", "defaultFollowRedirect", "true");
    }

    @Test
    public void testDefaultMaxRedirects() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultMaxRedirects(), 5);
        testIntegerSystemProperty("maxRedirects", "defaultMaxRedirects", "100");
    }

    @Test
    public void testDefaultCompressionEnforced() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultCompressionEnforced());
        testBooleanSystemProperty("compressionEnforced", "defaultCompressionEnforced", "true");
    }

    @Test
    public void testDefaultUserAgent() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultUserAgent(), "AHC/2.1");
        testStringSystemProperty("userAgent", "defaultUserAgent", "MyAHC");
    }

    @Test
    public void testDefaultUseProxySelector() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultUseProxySelector());
        testBooleanSystemProperty("useProxySelector", "defaultUseProxySelector", "true");
    }

    @Test
    public void testDefaultUseProxyProperties() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultUseProxyProperties());
        testBooleanSystemProperty("useProxyProperties", "defaultUseProxyProperties", "true");
    }

    @Test
    public void testDefaultStrict302Handling() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultStrict302Handling());
        testBooleanSystemProperty("strict302Handling", "defaultStrict302Handling", "true");
    }

    @Test
    public void testDefaultAllowPoolingConnection() {
        assertTrue(AsyncHttpClientConfigDefaults.defaultKeepAlive());
        testBooleanSystemProperty("keepAlive", "defaultKeepAlive", "false");
    }

    @Test
    public void testDefaultMaxRequestRetry() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultMaxRequestRetry(), 5);
        testIntegerSystemProperty("maxRequestRetry", "defaultMaxRequestRetry", "100");
    }

    @Test
    public void testDefaultDisableUrlEncodingForBoundRequests() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultDisableUrlEncodingForBoundRequests());
        testBooleanSystemProperty("disableUrlEncodingForBoundRequests", "defaultDisableUrlEncodingForBoundRequests", "true");
    }

    @Test
    public void testDefaultUseInsecureTrustManager() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultUseInsecureTrustManager());
        testBooleanSystemProperty("useInsecureTrustManager", "defaultUseInsecureTrustManager", "false");
    }

    @Test
    public void testDefaultHashedWheelTimerTickDuration() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultHashedWheelTimerTickDuration(), 100);
        testIntegerSystemProperty("hashedWheelTimerTickDuration", "defaultHashedWheelTimerTickDuration", "100");
    }

    @Test
    public void testDefaultHashedWheelTimerSize() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultHashedWheelTimerSize(), 512);
        testIntegerSystemProperty("hashedWheelTimerSize", "defaultHashedWheelTimerSize", "512");
    }

    private void testIntegerSystemProperty(String propertyName, String methodName, String value) {
        String previous = System.getProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName);
        System.setProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName, value);
        AsyncHttpClientConfigHelper.reloadProperties();
        try {
            Method method = AsyncHttpClientConfigDefaults.class.getMethod(methodName);
            assertEquals(method.invoke(null), Integer.parseInt(value));
        } catch (Exception e) {
            fail("Couldn't find or execute method : " + methodName, e);
        }
        if (previous != null) {
            System.setProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName, previous);
        } else {
            System.clearProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName);
        }
    }

    private static void testBooleanSystemProperty(String propertyName, String methodName, String value) {
        String previous = System.getProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName);
        System.setProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName, value);
        AsyncHttpClientConfigHelper.reloadProperties();
        try {
            Method method = AsyncHttpClientConfigDefaults.class.getMethod(methodName);
            assertEquals(method.invoke(null), Boolean.parseBoolean(value));
        } catch (Exception e) {
            fail("Couldn't find or execute method : " + methodName, e);
        }
        if (previous != null) {
            System.setProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName, previous);
        } else {
            System.clearProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName);
        }
    }

    private static void testStringSystemProperty(String propertyName, String methodName, String value) {
        String previous = System.getProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName);
        System.setProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName, value);
        AsyncHttpClientConfigHelper.reloadProperties();
        try {
            Method method = AsyncHttpClientConfigDefaults.class.getMethod(methodName);
            assertEquals(method.invoke(null), value);
        } catch (Exception e) {
            fail("Couldn't find or execute method : " + methodName, e);
        }
        if (previous != null) {
            System.setProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName, previous);
        } else {
            System.clearProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName);
        }
    }
}
