package org.asynchttpclient;

import org.testng.Assert;
import org.testng.annotations.Test;

public class AsyncHttpClientConfigBuilderTest {    
    
    @Test
    public void testDefaultConfigValues(){
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
        Assert.assertEquals(config.getConnectionTimeoutInMs(),60000);
        Assert.assertEquals(config.getRequestTimeoutInMs(),60000);
        Assert.assertEquals(config.getIdleConnectionTimeoutInMs(),60000);
        Assert.assertFalse(config.isCompressionEnabled());
        Assert.assertFalse(config.isRedirectEnabled());
        System.setProperty(AsyncHttpClientConfig.ASYNC_CLIENT+"connectionTimeoutInMs","1000");
        System.setProperty(AsyncHttpClientConfig.ASYNC_CLIENT+"requestTimeoutInMs","500");
        System.setProperty(AsyncHttpClientConfig.ASYNC_CLIENT+"compressionEnabled","true");
        System.setProperty(AsyncHttpClientConfig.ASYNC_CLIENT+"redirectsEnabled","true");
        config = new AsyncHttpClientConfig.Builder().build();
        Assert.assertEquals(config.getConnectionTimeoutInMs(),1000);
        Assert.assertEquals(config.getRequestTimeoutInMs(), 500);
        Assert.assertTrue(config.isCompressionEnabled());
        Assert.assertTrue(config.isRedirectEnabled());
        System.clearProperty(AsyncHttpClientConfig.ASYNC_CLIENT+"connectionTimeoutInMs");
        System.clearProperty(AsyncHttpClientConfig.ASYNC_CLIENT+"requestTimeoutInMs");
        System.clearProperty(AsyncHttpClientConfig.ASYNC_CLIENT+"compressionEnabled");
        System.clearProperty(AsyncHttpClientConfig.ASYNC_CLIENT+"defaultRedirectsEnabled");
    }
}
