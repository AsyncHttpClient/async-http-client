/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.asynchttpclient.config.AsyncHttpClientConfigDefaults;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.asynchttpclient.testserver.SocksProxy;
import org.asynchttpclient.util.ProxyUtils;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proxy usage tests.
 *
 * @author Hubert Iwaniuk
 */
public class ProxyTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ProxyHandler();
    }

    @Test
    public void testRequestLevelProxy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            String target = "http://localhost:1234/";
            Future<Response> f = client.prepareGet(target).setProxyServer(proxyServer("localhost", port1)).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            assertEquals("/", resp.getHeader("target"));
        }
    }

    @Test
    public void asyncDoPostProxyTest() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient(config().setProxyServer(proxyServer("localhost", port2).build()))) {
            HttpHeaders h = new DefaultHttpHeaders();
            h.add(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append("param_").append(i).append("=value_").append(i).append('&');
            }
            sb.setLength(sb.length() - 1);

            Response response = client.preparePost(getTargetUrl())
                    .setHeaders(h)
                    .setBody(sb.toString())
                    .execute(new AsyncCompletionHandler<Response>() {
                        @Override
                        public Response onCompleted(Response response) {
                            return response;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                        }
                    }).get();

            assertEquals(200, response.getStatusCode());
            assertEquals(APPLICATION_X_WWW_FORM_URLENCODED.toString(), response.getHeader("X-" + CONTENT_TYPE));
        }
    }

    @Test
    public void testGlobalProxy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setProxyServer(proxyServer("localhost", port1)))) {
            String target = "http://localhost:1234/";
            Future<Response> f = client.prepareGet(target).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        }
    }

    @Test
    public void testBothProxies() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setProxyServer(proxyServer("localhost", port1 - 1)))) {
            String target = "http://localhost:1234/";
            Future<Response> f = client.prepareGet(target).setProxyServer(proxyServer("localhost", port1)).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        }
    }

    @Test
    public void testNonProxyHost() {
        // // should avoid, it's in non-proxy hosts
        Request req = get("http://somewhere.com/foo").build();
        ProxyServer proxyServer = proxyServer("localhost", 1234).setNonProxyHost("somewhere.com").build();
        assertTrue(proxyServer.isIgnoredForHost(req.getUri().getHost()));
        //
        // // should avoid, it's in non-proxy hosts (with "*")
        req = get("http://sub.somewhere.com/foo").build();
        proxyServer = proxyServer("localhost", 1234).setNonProxyHost("*.somewhere.com").build();
        assertTrue(proxyServer.isIgnoredForHost(req.getUri().getHost()));

        // should use it
        req = get("http://sub.somewhere.com/foo").build();
        proxyServer = proxyServer("localhost", 1234).setNonProxyHost("*.somewhere.com").build();
        assertTrue(proxyServer.isIgnoredForHost(req.getUri().getHost()));
    }

    @Test
    public void testNonProxyHostsRequestOverridesConfig() throws Exception {
        ProxyServer configProxy = proxyServer("localhost", port1 - 1).build();
        ProxyServer requestProxy = proxyServer("localhost", port1).setNonProxyHost("localhost").build();

        try (AsyncHttpClient client = asyncHttpClient(config().setProxyServer(configProxy))) {
            client.prepareGet("http://localhost:1234/").setProxyServer(requestProxy).execute().get();
        } catch (Exception ex) {
            assertInstanceOf(ConnectException.class, ex.getCause());
        }
    }

    @Test
    public void testRequestNonProxyHost() throws Exception {
        ProxyServer proxy = proxyServer("localhost", port1 - 1).setNonProxyHost("localhost").build();
        try (AsyncHttpClient client = asyncHttpClient()) {
            String target = "http://localhost:" + port1 + '/';
            Future<Response> f = client.prepareGet(target).setProxyServer(proxy).execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");
        }
    }

    @Test
    public void runSequentiallyBecauseNotThreadSafe() throws Exception {
        testProxyProperties();
        testIgnoreProxyPropertiesByDefault();
        testProxyActivationProperty();
        testWildcardNonProxyHosts();
        testUseProxySelector();
    }

    @Test
    public void testProxyProperties() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        // FIXME not threadsafe!
        Properties originalProps = new Properties();
        originalProps.putAll(System.getProperties());
        System.setProperty(ProxyUtils.PROXY_HOST, "127.0.0.1");
        System.setProperty(ProxyUtils.PROXY_PORT, String.valueOf(port1));
        System.setProperty(ProxyUtils.PROXY_NONPROXYHOSTS, "localhost");
        AsyncHttpClientConfigHelper.reloadProperties();

        try (AsyncHttpClient client = asyncHttpClient(config().setUseProxyProperties(true))) {
            String proxifiedtarget = "http://127.0.0.1:1234/";
            Future<Response> responseFuture = client.prepareGet(proxifiedtarget).execute();
            Response resp = responseFuture.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");

            String nonProxifiedtarget = "http://localhost:1234/";
            final Future<Response> secondResponseFuture = client.prepareGet(nonProxifiedtarget).execute();

            assertThrows(Exception.class, () -> secondResponseFuture.get(3, TimeUnit.SECONDS));
        } finally {
            System.setProperties(originalProps);
        }
    }

    @Test
    public void testIgnoreProxyPropertiesByDefault() throws IOException, TimeoutException, InterruptedException {
        // FIXME not threadsafe!
        Properties originalProps = new Properties();
        originalProps.putAll(System.getProperties());
        System.setProperty(ProxyUtils.PROXY_HOST, "localhost");
        System.setProperty(ProxyUtils.PROXY_PORT, String.valueOf(port1));
        System.setProperty(ProxyUtils.PROXY_NONPROXYHOSTS, "localhost");
        AsyncHttpClientConfigHelper.reloadProperties();

        try (AsyncHttpClient client = asyncHttpClient()) {
            String target = "http://localhost:1234/";
            final Future<Response> responseFuture = client.prepareGet(target).execute();
            assertThrows(Exception.class, () -> responseFuture.get(3, TimeUnit.SECONDS));
        } finally {
            System.setProperties(originalProps);
        }
    }

    @Test
    public void testProxyActivationProperty() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        // FIXME not threadsafe!
        Properties originalProps = new Properties();
        originalProps.putAll(System.getProperties());
        System.setProperty(ProxyUtils.PROXY_HOST, "127.0.0.1");
        System.setProperty(ProxyUtils.PROXY_PORT, String.valueOf(port1));
        System.setProperty(ProxyUtils.PROXY_NONPROXYHOSTS, "localhost");
        System.setProperty(AsyncHttpClientConfigDefaults.ASYNC_CLIENT_CONFIG_ROOT + "useProxyProperties", "true");
        AsyncHttpClientConfigHelper.reloadProperties();

        try (AsyncHttpClient client = asyncHttpClient()) {
            String proxifiedTarget = "http://127.0.0.1:1234/";
            Future<Response> responseFuture = client.prepareGet(proxifiedTarget).execute();
            Response resp = responseFuture.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");

            String nonProxifiedTarget = "http://localhost:1234/";
            Future<Response> secondResponseFuture = client.prepareGet(nonProxifiedTarget).execute();
            assertThrows(Exception.class, () -> secondResponseFuture.get(3, TimeUnit.SECONDS));
        } finally {
            System.setProperties(originalProps);
        }
    }

    @Test
    public void testWildcardNonProxyHosts() throws IOException, TimeoutException, InterruptedException {
        // FIXME not threadsafe!
        Properties originalProps = new Properties();
        originalProps.putAll(System.getProperties());
        System.setProperty(ProxyUtils.PROXY_HOST, "127.0.0.1");
        System.setProperty(ProxyUtils.PROXY_PORT, String.valueOf(port1));
        System.setProperty(ProxyUtils.PROXY_NONPROXYHOSTS, "127.*");
        AsyncHttpClientConfigHelper.reloadProperties();

        try (AsyncHttpClient client = asyncHttpClient(config().setUseProxyProperties(true))) {
            String nonProxifiedTarget = "http://127.0.0.1:1234/";
            Future<Response> secondResponseFuture = client.prepareGet(nonProxifiedTarget).execute();
            assertThrows(Exception.class, () -> secondResponseFuture.get(3, TimeUnit.SECONDS));
        } finally {
            System.setProperties(originalProps);
        }
    }

    @Test
    public void testUseProxySelector() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        ProxySelector originalProxySelector = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if ("127.0.0.1".equals(uri.getHost())) {
                    return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port1)));
                } else {
                    return Collections.singletonList(Proxy.NO_PROXY);
                }
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // NO-OP
            }
        });

        try (AsyncHttpClient client = asyncHttpClient(config().setUseProxySelector(true))) {
            String proxifiedTarget = "http://127.0.0.1:1234/";
            Future<Response> responseFuture = client.prepareGet(proxifiedTarget).execute();
            Response resp = responseFuture.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getHeader("target"), "/");

            String nonProxifiedTarget = "http://localhost:1234/";
            Future<Response> secondResponseFuture = client.prepareGet(nonProxifiedTarget).execute();
            assertThrows(Exception.class, () -> secondResponseFuture.get(3, TimeUnit.SECONDS));
        } finally {
            // FIXME not threadsafe
            ProxySelector.setDefault(originalProxySelector);
        }
    }

    @Test
    public void runSocksProxy() throws Exception {
        new Thread(() -> {
            try {
                new SocksProxy(60000);
            } catch (IOException e) {
                logger.error("Failed to establish SocksProxy", e);
            }
        }).start();

        try (AsyncHttpClient client = asyncHttpClient()) {
            String target = "http://localhost:" + port1 + '/';
            Future<Response> f = client.prepareGet(target)
                    .setProxyServer(new ProxyServer.Builder("localhost", 8000).setProxyType(ProxyType.SOCKS_V4))
                    .execute();

            assertEquals(200, f.get(60, TimeUnit.SECONDS).getStatusCode());
        }
    }

    public static class ProxyHandler extends AbstractHandler {

        @Override
        public void handle(String s, org.eclipse.jetty.server.Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if ("GET".equalsIgnoreCase(request.getMethod())) {
                response.addHeader("target", r.getHttpURI().getPath());
                response.setStatus(HttpServletResponse.SC_OK);
            } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                response.addHeader("X-" + CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED.toString());
            } else {
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
            r.setHandled(true);
        }
    }
}
