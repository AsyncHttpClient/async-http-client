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

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.config.AsyncHttpClientConfigDefaults;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.ASYNC_CLIENT_CONFIG_ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class AsyncHttpClientDefaultsTest {

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultUseOnlyEpollNativeTransport() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultUseOnlyEpollNativeTransport());
        testBooleanSystemProperty("useOnlyEpollNativeTransport", "defaultUseOnlyEpollNativeTransport", "false");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultMaxTotalConnections() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultMaxConnections(), -1);
        testIntegerSystemProperty("maxConnections", "defaultMaxConnections", "100");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultMaxConnectionPerHost() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultMaxConnectionsPerHost(), -1);
        testIntegerSystemProperty("maxConnectionsPerHost", "defaultMaxConnectionsPerHost", "100");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultConnectTimeOut() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultConnectTimeout(), Duration.ofSeconds(5));
        testDurationSystemProperty("connectTimeout", "defaultConnectTimeout", "PT0.1S");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultPooledConnectionIdleTimeout() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultPooledConnectionIdleTimeout(), 60 * 1000);
        testIntegerSystemProperty("pooledConnectionIdleTimeout", "defaultPooledConnectionIdleTimeout", "100");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultReadTimeout() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultReadTimeout(), 60 * 1000);
        testIntegerSystemProperty("readTimeout", "defaultReadTimeout", "100");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultRequestTimeout() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultRequestTimeout(), Duration.ofSeconds(60));
        testDurationSystemProperty("requestTimeout", "defaultRequestTimeout", "PT0.1S");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultConnectionTtl() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultConnectionTtl(), -1);
        testIntegerSystemProperty("connectionTtl", "defaultConnectionTtl", "100");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultFollowRedirect() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultFollowRedirect());
        testBooleanSystemProperty("followRedirect", "defaultFollowRedirect", "true");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultMaxRedirects() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultMaxRedirects(), 5);
        testIntegerSystemProperty("maxRedirects", "defaultMaxRedirects", "100");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultCompressionEnforced() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultCompressionEnforced());
        testBooleanSystemProperty("compressionEnforced", "defaultCompressionEnforced", "true");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultUserAgent() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultUserAgent(), "AHC/2.1");
        testStringSystemProperty("userAgent", "defaultUserAgent", "MyAHC");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultUseProxySelector() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultUseProxySelector());
        testBooleanSystemProperty("useProxySelector", "defaultUseProxySelector", "true");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultUseProxyProperties() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultUseProxyProperties());
        testBooleanSystemProperty("useProxyProperties", "defaultUseProxyProperties", "true");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultStrict302Handling() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultStrict302Handling());
        testBooleanSystemProperty("strict302Handling", "defaultStrict302Handling", "true");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultAllowPoolingConnection() {
        assertTrue(AsyncHttpClientConfigDefaults.defaultKeepAlive());
        testBooleanSystemProperty("keepAlive", "defaultKeepAlive", "false");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultMaxRequestRetry() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultMaxRequestRetry(), 5);
        testIntegerSystemProperty("maxRequestRetry", "defaultMaxRequestRetry", "100");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultDisableUrlEncodingForBoundRequests() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultDisableUrlEncodingForBoundRequests());
        testBooleanSystemProperty("disableUrlEncodingForBoundRequests", "defaultDisableUrlEncodingForBoundRequests", "true");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultUseInsecureTrustManager() {
        assertFalse(AsyncHttpClientConfigDefaults.defaultUseInsecureTrustManager());
        testBooleanSystemProperty("useInsecureTrustManager", "defaultUseInsecureTrustManager", "false");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultHashedWheelTimerTickDuration() {
        assertEquals(AsyncHttpClientConfigDefaults.defaultHashedWheelTimerTickDuration(), 100);
        testIntegerSystemProperty("hashedWheelTimerTickDuration", "defaultHashedWheelTimerTickDuration", "100");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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

    private static void testDurationSystemProperty(String propertyName, String methodName, String value) {
        String previous = System.getProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName);
        System.setProperty(ASYNC_CLIENT_CONFIG_ROOT + propertyName, value);
        AsyncHttpClientConfigHelper.reloadProperties();
        try {
            Method method = AsyncHttpClientConfigDefaults.class.getMethod(methodName);
            assertEquals(method.invoke(null), Duration.parse(value));
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
