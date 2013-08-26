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
package com.ning.http.client.async;

import static org.testng.Assert.*;
import static org.testng.Assert.assertEquals;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Response;

import java.io.IOException;
import java.net.*;
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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

/**
 * Proxy usage tests.
 * 
 * @author Hubert Iwaniuk
 */
public abstract class ProxyTest extends AbstractBasicTest {
    private class ProxyHandler extends AbstractHandler {
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                response.addHeader("target", r.getUri().getPath());
                response.setStatus(HttpServletResponse.SC_OK);
            } else { // this handler is to handle POST request
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
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).setProxyServer(new ProxyServer("127.0.0.1", port1)).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testGlobalProxy() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClientConfig cfg = new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", port1)).build();
        AsyncHttpClient client = getAsyncHttpClient(cfg);
        try {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testBothProxies() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClientConfig cfg = new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", port1 - 1)).build();
        AsyncHttpClient client = getAsyncHttpClient(cfg);
        try {
            String target = "http://127.0.0.1:1234/";
            Future<Response> f = client.prepareGet(target).setProxyServer(new ProxyServer("127.0.0.1", port1)).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testNonProxyHosts() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClientConfig cfg = new AsyncHttpClientConfig.Builder().setProxyServer(new ProxyServer("127.0.0.1", port1 - 1)).build();
        AsyncHttpClient client = getAsyncHttpClient(cfg);
        try {

            String target = "http://127.0.0.1:1234/";
            client.prepareGet(target).setProxyServer(new ProxyServer("127.0.0.1", port1).addNonProxyHost("127.0.0.1")).execute().get();
            assertFalse(true);
        } catch (Throwable e) {
            assertNotNull(e.getCause());
            assertEquals(e.getCause().getClass(), ConnectException.class);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testNonProxyHostIssue202() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(null);
        try {
            String target = "http://127.0.0.1:" + port1 + "/";
            Future<Response> f = client.prepareGet(target).setProxyServer(new ProxyServer("127.0.0.1", port1 - 1).addNonProxyHost("127.0.0.1")).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testProxyProperties() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        Properties originalProps = System.getProperties();
        try {
            Properties props = new Properties();
            props.putAll(originalProps);

            System.setProperties(props);

            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", String.valueOf(port1));
            System.setProperty("http.nonProxyHosts", "localhost");

            AsyncHttpClientConfig cfg = new AsyncHttpClientConfig.Builder().setUseProxyProperties(true).build();
            AsyncHttpClient client = getAsyncHttpClient(cfg);
            try {
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
                client.close();
            }
        } finally {
            System.setProperties(originalProps);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testIgnoreProxyPropertiesByDefault() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        Properties originalProps = System.getProperties();
        try {
            Properties props = new Properties();
            props.putAll(originalProps);

            System.setProperties(props);

            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", String.valueOf(port1));
            System.setProperty("http.nonProxyHosts", "localhost");

            AsyncHttpClient client = getAsyncHttpClient(null);
            try {
                String target = "http://127.0.0.1:1234/";
                Future<Response> f = client.prepareGet(target).execute();
                try {
                    f.get(3, TimeUnit.SECONDS);
                    fail("should not be able to connect");
                } catch (ExecutionException e) {
                    // ok, no proxy used
                }

            } finally {
                client.close();
            }
        } finally {
            System.setProperties(originalProps);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testProxyActivationProperty() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        Properties originalProps = System.getProperties();
        try {
            Properties props = new Properties();
            props.putAll(originalProps);

            System.setProperties(props);

            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", String.valueOf(port1));
            System.setProperty("http.nonProxyHosts", "localhost");
            System.setProperty("com.ning.http.client.AsyncHttpClientConfig.useProxyProperties", "true");

            AsyncHttpClient client = getAsyncHttpClient(null);
            try {
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
                client.close();
            }
        } finally {
            System.setProperties(originalProps);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testWildcardNonProxyHosts() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        Properties originalProps = System.getProperties();
        try {
            Properties props = new Properties();
            props.putAll(originalProps);

            System.setProperties(props);

            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", String.valueOf(port1));
            System.setProperty("http.nonProxyHosts", "127.*");

            AsyncHttpClientConfig cfg = new AsyncHttpClientConfig.Builder().setUseProxyProperties(true).build();
            AsyncHttpClient client = getAsyncHttpClient(cfg);
            try {
                String target = "http://127.0.0.1:1234/";
                Future<Response> f = client.prepareGet(target).execute();
                try {
                    f.get(3, TimeUnit.SECONDS);
                    fail("should not be able to connect");
                } catch (ExecutionException e) {
                    // ok, no proxy used
                }
            } finally {
                client.close();
            }
        } finally {
            System.setProperties(originalProps);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testUseProxySelector() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        ProxySelector originalProxySelector = ProxySelector.getDefault();
        try {
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

            AsyncHttpClientConfig cfg = new AsyncHttpClientConfig.Builder().setUseProxySelector(true).build();
            AsyncHttpClient client = getAsyncHttpClient(cfg);
            try {
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
                client.close();
            }
        } finally {
            ProxySelector.setDefault(originalProxySelector);
        }
    }

}
