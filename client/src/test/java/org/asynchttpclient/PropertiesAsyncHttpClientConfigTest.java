package org.asynchttpclient;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Properties;
import java.util.function.Function;

@Test
public class PropertiesAsyncHttpClientConfigTest {
    
    public void testMaxTotalConnections() {
        testProperty(PropertiesAsyncHttpClientConfig::getMaxConnections, "maxConnections", 100, -1);
    }

    public void testMaxConnectionPerHost() {
        testProperty(PropertiesAsyncHttpClientConfig::getMaxConnectionsPerHost, "maxConnectionsPerHost", 100, -1);
    }

    public void testConnectTimeOut() {
        testProperty(PropertiesAsyncHttpClientConfig::getConnectTimeout, "connectTimeout", 100, 5 * 1000);
    }

    public void testPooledConnectionIdleTimeout() {
        testProperty(PropertiesAsyncHttpClientConfig::getPooledConnectionIdleTimeout, "pooledConnectionIdleTimeout", 200,6 * 10000);
    }

    public void testReadTimeout() {
        testProperty(PropertiesAsyncHttpClientConfig::getReadTimeout, "readTimeout", 100,60 * 1000);
    }

    public void testRequestTimeout() {
        testProperty(PropertiesAsyncHttpClientConfig::getRequestTimeout, "requestTimeout",200,6 * 10000);
    }

    public void testConnectionTtl() {
        testProperty(PropertiesAsyncHttpClientConfig::getConnectionTtl, "connectionTtl", 100, -1);
    }

    public void testFollowRedirect() {
        testProperty(PropertiesAsyncHttpClientConfig::isFollowRedirect, "followRedirect", true, false);
    }

    public void testMaxRedirects() {
        testProperty(PropertiesAsyncHttpClientConfig::getMaxRedirects, "maxRedirects", 100, 5);
    }

    public void testCompressionEnforced() {
        testProperty(PropertiesAsyncHttpClientConfig::isCompressionEnforced, "compressionEnforced", true, false);
    }

    public void testUserAgent() {
        testProperty(PropertiesAsyncHttpClientConfig::getUserAgent, "userAgent", "MyAHC", "AHC/2.1");
    }

    public void testStrict302Handling() {
        testProperty(PropertiesAsyncHttpClientConfig::isStrict302Handling, "strict302Handling", true, false);
    }

    public void testAllowPoolingConnection() {
        testProperty(PropertiesAsyncHttpClientConfig::isKeepAlive, "keepAlive", false, true);
    }

    public void testMaxRequestRetry() {
        testProperty(PropertiesAsyncHttpClientConfig::getMaxRequestRetry, "maxRequestRetry", 100, 5);
    }

    public void testDisableUrlEncodingForBoundRequests() {
        testProperty(PropertiesAsyncHttpClientConfig::isDisableUrlEncodingForBoundRequests, "disableUrlEncodingForBoundRequests", true, false);
    }

    public void testUseInsecureTrustManager() {
        testProperty(PropertiesAsyncHttpClientConfig::isUseInsecureTrustManager, "useInsecureTrustManager", true, false);
    }

    private <T> void testProperty(Function<PropertiesAsyncHttpClientConfig, T> func, String propertyName, T value, T defaultValue) {
        PropertiesAsyncHttpClientConfig defaultConfig = new PropertiesAsyncHttpClientConfig(new Properties());
        Assert.assertEquals(func.apply(defaultConfig), defaultValue);

        Properties properties = new Properties();
        properties.setProperty(propertyName, value.toString());
        PropertiesAsyncHttpClientConfig config = new PropertiesAsyncHttpClientConfig(properties);
        Assert.assertEquals(func.apply(config), value);
    }

}
