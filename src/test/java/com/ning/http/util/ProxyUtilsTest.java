package com.ning.http.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;

public class ProxyUtilsTest
{
    @Test( groups = "fast" )
    public void testBasics()
    {
        ProxyServer proxyServer;
        Request req;

        // should avoid, there is no proxy (is null)
        req = new RequestBuilder( "GET" ).setUrl( "http://somewhere.com/foo" ).build();
        Assert.assertTrue( ProxyUtils.avoidProxy( null, req ) );

        // should avoid, it's in non-proxy hosts
        req = new RequestBuilder( "GET" ).setUrl( "http://somewhere.com/foo" ).build();
        proxyServer = new ProxyServer( "foo", 1234 );
        proxyServer.addNonProxyHost( "somewhere.com" );
        Assert.assertTrue( ProxyUtils.avoidProxy( proxyServer, req ) );

        // should avoid, it's in non-proxy hosts (with "*")
        req = new RequestBuilder( "GET" ).setUrl( "http://sub.somewhere.com/foo" ).build();
        proxyServer = new ProxyServer( "foo", 1234 );
        proxyServer.addNonProxyHost( "*.somewhere.com" );
        Assert.assertTrue( ProxyUtils.avoidProxy( proxyServer, req ) );
        
        // should use it
        req = new RequestBuilder( "GET" ).setUrl( "http://sub.somewhere.com/foo" ).build();
        proxyServer = new ProxyServer( "foo", 1234 );
        proxyServer.addNonProxyHost( "*.somewhere.org" );
        Assert.assertFalse( ProxyUtils.avoidProxy( proxyServer, req ) );
    }
}
