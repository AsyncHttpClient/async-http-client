/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.proxy;

import static org.asynchttpclient.Dsl.newConfig;
import static org.testng.Assert.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.config.AsyncHttpClientConfigDefaults;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.asynchttpclient.util.ProxyUtils;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

/**
 * Proxy usage tests.
 * 
 * @author Hubert Iwaniuk
 */
public class ProxyTest extends AbstractBasicTest {
    public static class ProxyHandler extends AbstractHandler {
        public void handle(String s,  org.eclipse.jetty.server.Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                response.addHeader("target", r.getUri().getPath());
                response.setStatus(HttpServletResponse.SC_OK);
            } else {
                // this handler is to handle POST request
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            r.setHandled(true);
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ProxyHandler();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testRequestLevelProxy() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).setProxyServer(ProxyServer.newProxyServer("127.0.0.1", port1).build()).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testGlobalProxy() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClientConfig cfg = newConfig().proxyServer(ProxyServer.newProxyServer("127.0.0.1", port1).build()).build();
        try (AsyncHttpClient client = new DefaultAsyncHttpClient(cfg)) {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testBothProxies() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClientConfig cfg = newConfig().proxyServer(ProxyServer.newProxyServer("127.0.0.1", port1 - 1).build()).build();
        try (AsyncHttpClient client = new DefaultAsyncHttpClient(cfg)) {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).setProxyServer(ProxyServer.newProxyServer("127.0.0.1", port1).build()).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        }
    }

    @Test(groups = "fast")
    public void testNonProxyHost() {

        // // should avoid, it's in non-proxy hosts
        Request req = new RequestBuilder("GET").setUrl("http://somewhere.com/foo").build();
        ProxyServer proxyServer = ProxyServer.newProxyServer("foo", 1234).nonProxyHost("somewhere.com").build();
        assertTrue(proxyServer.isIgnoredForHost(req.getUri().getHost()));
        //
        // // should avoid, it's in non-proxy hosts (with "*")
        req = new RequestBuilder("GET").setUrl("http://sub.somewhere.com/foo").build();
        proxyServer = ProxyServer.newProxyServer("foo", 1234).nonProxyHost("*.somewhere.com").build();
        assertTrue(proxyServer.isIgnoredForHost(req.getUri().getHost()));

        // should use it
        req = new RequestBuilder("GET").setUrl("http://sub.somewhere.com/foo").build();
        proxyServer = ProxyServer.newProxyServer("foo", 1234).nonProxyHost("*.somewhere.com").build();
        assertTrue(proxyServer.isIgnoredForHost(req.getUri().getHost()));
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testNonProxyHostsRequestOverridesConfig() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        
        ProxyServer configProxy = ProxyServer.newProxyServer("127.0.0.1", port1 - 1).build();
        ProxyServer requestProxy = ProxyServer.newProxyServer("127.0.0.1", port1).nonProxyHost("127.0.0.1").build();
        
        AsyncHttpClientConfig cfg = newConfig().proxyServer(configProxy).build();
        try (AsyncHttpClient client = new DefaultAsyncHttpClient(cfg)) {
            String target = "http://127.0.0.1:1234/";
            client.prepareGet(target).setProxyServer(requestProxy).execute().get();
            assertFalse(true);
        } catch (Throwable e) {
            assertNotNull(e.getCause());
            assertEquals(e.getCause().getClass(), ConnectException.class);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testRequestNonProxyHost() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        
        ProxyServer proxy = ProxyServer.newProxyServer("127.0.0.1", port1 - 1).nonProxyHost("127.0.0.1").build();
        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            String target = "http://127.0.0.1:" + port1 + "/";
            Future<Response> f = client.prepareGet(target).setProxyServer(proxy).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        }
    }
    
    @Test(groups = { "standalone", "default_provider" })
    public void runSequentiallyBecauseNotThreadSafe() throws Exception {
        testProxyProperties();
        testIgnoreProxyPropertiesByDefault();
        testProxyActivationProperty();
        testWildcardNonProxyHosts();
        testUseProxySelector();
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void testProxyProperties() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        // FIXME not threadsafe!
        Properties originalProps = new Properties();
        originalProps.putAll(System.getProperties());
        System.setProperty(ProxyUtils.PROXY_HOST, "127.0.0.1");
        System.setProperty(ProxyUtils.PROXY_PORT, String.valueOf(port1));
        System.setProperty(ProxyUtils.PROXY_NONPROXYHOSTS, "localhost");
        AsyncHttpClientConfigHelper.reloadProperties();

        AsyncHttpClientConfig cfg = newConfig().useProxyProperties(true).build();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient(cfg)) {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");

            target = "http://localhost:1234/";
            f = client.prepareGet(target).execute();
            try {
                resp = f.get(3, TimeUnit.SECONDS);
                fail("should not be able to connect");
            } catch (ExecutionException e) {
                // ok, no proxy used
            }
        } finally {
            System.setProperties(originalProps);
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void testIgnoreProxyPropertiesByDefault() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        // FIXME not threadsafe!
        Properties originalProps = new Properties();
        originalProps.putAll(System.getProperties());
        System.setProperty(ProxyUtils.PROXY_HOST, "127.0.0.1");
        System.setProperty(ProxyUtils.PROXY_PORT, String.valueOf(port1));
        System.setProperty(ProxyUtils.PROXY_NONPROXYHOSTS, "localhost");
        AsyncHttpClientConfigHelper.reloadProperties();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).execute();
            try {
                f.get(3, TimeUnit.SECONDS);
                fail("should not be able to connect");
            } catch (ExecutionException e) {
                // ok, no proxy used
            }
        } finally {
            System.setProperties(originalProps);
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void testProxyActivationProperty() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        // FIXME not threadsafe!
        Properties originalProps = new Properties();
        originalProps.putAll(System.getProperties());
        System.setProperty(ProxyUtils.PROXY_HOST, "127.0.0.1");
        System.setProperty(ProxyUtils.PROXY_PORT, String.valueOf(port1));
        System.setProperty(ProxyUtils.PROXY_NONPROXYHOSTS, "localhost");
        System.setProperty(AsyncHttpClientConfigDefaults.ASYNC_CLIENT_CONFIG_ROOT + "useProxyProperties", "true");
        AsyncHttpClientConfigHelper.reloadProperties();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");

            target = "http://localhost:1234/";
            f = client.prepareGet(target).execute();
            try {
                resp = f.get(3, TimeUnit.SECONDS);
                fail("should not be able to connect");
            } catch (ExecutionException e) {
                // ok, no proxy used
            }
        } finally {
            System.setProperties(originalProps);
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void testWildcardNonProxyHosts() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        // FIXME not threadsafe!
        Properties originalProps = new Properties();
        originalProps.putAll(System.getProperties());
        System.setProperty(ProxyUtils.PROXY_HOST, "127.0.0.1");
        System.setProperty(ProxyUtils.PROXY_PORT, String.valueOf(port1));
        System.setProperty(ProxyUtils.PROXY_NONPROXYHOSTS, "127.*");
        AsyncHttpClientConfigHelper.reloadProperties();

        AsyncHttpClientConfig cfg = newConfig().useProxyProperties(true).build();
        try (AsyncHttpClient client = new DefaultAsyncHttpClient(cfg)) {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).execute();
            try {
                f.get(3, TimeUnit.SECONDS);
                fail("should not be able to connect");
            } catch (ExecutionException e) {
                // ok, no proxy used
            }
        } finally {
            System.setProperties(originalProps);
        }
    }

    // @Test(groups = { "standalone", "default_provider" })
    public void testUseProxySelector() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        ProxySelector originalProxySelector = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            public List<Proxy> select(URI uri) {
                if (uri.getHost().equals("127.0.0.1")) {
                    return Arrays.asList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port1)));
                } else {
                    return Arrays.asList(Proxy.NO_PROXY);
                }
            }

            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });

        AsyncHttpClientConfig cfg = newConfig().useProxySelector(true).build();
        try (AsyncHttpClient client = new DefaultAsyncHttpClient(cfg)) {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");

            target = "http://localhost:1234/";
            f = client.prepareGet(target).execute();
            try {
                f.get(3, TimeUnit.SECONDS);
                fail("should not be able to connect");
            } catch (ExecutionException e) {
                // ok, no proxy used
            }
        } finally {
            // FIXME not threadsafe
            ProxySelector.setDefault(originalProxySelector);
        }
    }
}
