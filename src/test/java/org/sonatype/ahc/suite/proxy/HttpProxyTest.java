package org.sonatype.ahc.suite.proxy;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
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

import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.AsyncHttpClientConfig.Builder;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Response;
import org.sonatype.ahc.suite.util.AsyncSuiteConfiguration;
import org.sonatype.tests.http.runner.annotations.Configurators;
import org.sonatype.tests.http.server.jetty.configurations.HttpProxyConfigurator;
import org.sonatype.tests.http.server.jetty.impl.JettyServerProvider;
import org.testng.annotations.Test;

import java.net.MalformedURLException;
import java.net.URL;

import static org.testng.AssertJUnit.assertEquals;

/**
 * @author Benjamin Hanzelmann
 */
@Configurators(HttpProxyConfigurator.class)
public class HttpProxyTest
        extends AsyncSuiteConfiguration {

    @Override
    protected Builder settings(Builder rb) {
        return super.settings(rb).setProxyServer(new ProxyServer("localhost", provider().getPort()));
    }

    @Test(groups = "standalone")
    public void testGet()
            throws Exception {
        setAuthentication(null, null, false);
        String url = url("content", "something");
        BoundRequestBuilder get = client().prepareGet(url);
        Response response = execute(get);
        System.err.println(response.getHeaders());
        assertEquals(200, response.getStatusCode());
        assertEquals("something", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testHead()
            throws Exception {
        setAuthentication(null, null, false);
        String url = url("content", "something");
        BoundRequestBuilder get = client().prepareHead(url);
        Response response = execute(get);
        assertEquals("", response.getResponseBody());
        assertEquals(200, response.getStatusCode());
    }

    @Test(groups = "standalone")
    public void testBasicAuthBehindProxy()
            throws Exception {
        authServer("BASIC");

        setAuthentication("u", "p", false);
        BoundRequestBuilder rb = client().prepareGet(url("content", "something"));
        Response response = execute(rb);

        assertEquals("something", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testDigestAuthBehindProxy()
            throws Exception {
        authServer("DIGEST");

        setAuthentication("u", "p", false);
        BoundRequestBuilder rb = client().prepareGet(url("content", "something"));
        Response response = execute(rb);

        assertEquals("something", response.getResponseBody());
    }

    @Test(groups = "standalone")
    public void testBasicAuthFailBehindProxy()
            throws Exception {
        authServer("BASIC");

        setAuthentication("u", "wrong", false);
        BoundRequestBuilder rb = client().prepareGet(url("content", "something"));
        Response response = execute(rb);

        System.err.println(response.getResponseBody());

        assertEquals(401, response.getStatusCode());
    }

    @Test(groups = "standalone")
    public void testDigestAuthFailBehindProxy()
            throws Exception {
        authServer("DIGEST");

        setAuthentication("u", "wrong", false);
        BoundRequestBuilder rb = client().prepareGet(url("content", "something"));
        Response response = execute(rb);

        assertEquals(401, response.getStatusCode());
    }

    private void authServer(String method)
            throws Exception {
        JettyServerProvider p = (JettyServerProvider) provider();
        p.stop();
        p.initServer();
        p.addDefaultServices();
        p.addAuthentication("/*", method);
        p.addUser("u", "p");
        p.start();
    }

    @Test(groups = "standalone", enabled = false)
    // TODO: think about whether this scenario makes sense" )
    public void testSslBehindProxy()
            throws Exception {
        setTimeout(10000);
        JettyServerProvider p = (JettyServerProvider) provider();
        p.stop();
        p.setSSL("keystore", "password");
        p.initServer();
        p.addDefaultServices();
        p.start();

        BoundRequestBuilder rb = client().prepareGet("https://proxiedhost.invalid/content/foo");
        Response response = execute(rb);

        assertEquals("foo", response.getResponseBody());
    }

    @Override
    public String url() {
        URL url;
        try {
            url = new URL(super.url());
        }
        catch (MalformedURLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        String protocol = url.getProtocol();
        String host = "proxiedhost.invalid";
        int port = url.getPort();

        return String.format("%s://%s:%s", protocol, host, port);
    }
}
