/*
 * Copyright (c) 2024-2026 AsyncHttpClient Project. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asynchttpclient.proxy;

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.testserver.SocksProxy;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for SOCKS proxy support with both HTTP and HTTPS.
 * Validates fix for GitHub issue #2139 (SOCKS proxy support broken).
 */
public class SocksProxyTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ProxyTest.ProxyHandler();
    }

    /**
     * Returns a port that is not in use by binding to port 0 and then closing the socket.
     */
    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSocks4ProxyWithHttp() throws Exception {
        // Start SOCKS proxy in background thread
        Thread socksProxyThread = new Thread(() -> {
            try {
                new SocksProxy(60000);
            } catch (Exception e) {
                logger.error("Failed to establish SocksProxy", e);
            }
        });
        socksProxyThread.start();

        // Give the proxy time to start
        Thread.sleep(1000);

        try (AsyncHttpClient client = asyncHttpClient()) {
            String target = "http://localhost:" + port1 + '/';
            Future<Response> f = client.prepareGet(target)
                    .setProxyServer(new ProxyServer.Builder("localhost", 8000).setProxyType(ProxyType.SOCKS_V4))
                    .execute();

            Response response = f.get(60, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
        }
    }

    /**
     * Validates that when a SOCKS5 proxy is configured at an address where no
     * SOCKS server is running, the HTTP request FAILS instead of silently
     * bypassing the proxy and using normal routing.
     * This is the core regression test for GitHub issue #2139.
     */
    @Test
    public void testSocks5ProxyNotRunningMustFailHttp() throws Exception {
        int freePort = findFreePort();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setConnectTimeout(Duration.ofMillis(5000))
                .setRequestTimeout(Duration.ofMillis(10000)))) {
            String target = "http://localhost:" + port1 + '/';
            Future<Response> f = client.prepareGet(target)
                    .setProxyServer(new ProxyServer.Builder("127.0.0.1", freePort)
                            .setProxyType(ProxyType.SOCKS_V5))
                    .execute();
            assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS),
                    "Request should fail when SOCKS5 proxy is not running, not bypass proxy");
        }
    }

    /**
     * Validates that when a SOCKS4 proxy is configured at an address where no
     * SOCKS server is running, the HTTP request FAILS instead of silently
     * bypassing the proxy and using normal routing.
     */
    @Test
    public void testSocks4ProxyNotRunningMustFailHttp() throws Exception {
        int freePort = findFreePort();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setConnectTimeout(Duration.ofMillis(5000))
                .setRequestTimeout(Duration.ofMillis(10000)))) {
            String target = "http://localhost:" + port1 + '/';
            Future<Response> f = client.prepareGet(target)
                    .setProxyServer(new ProxyServer.Builder("127.0.0.1", freePort)
                            .setProxyType(ProxyType.SOCKS_V4))
                    .execute();
            assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS),
                    "Request should fail when SOCKS4 proxy is not running, not bypass proxy");
        }
    }

    /**
     * Validates that when a SOCKS5 proxy is configured at an address where no
     * SOCKS server is running, an HTTPS request FAILS instead of silently
     * bypassing the proxy and using normal routing.
     */
    @Test
    public void testSocks5ProxyNotRunningMustFailHttps() throws Exception {
        int freePort = findFreePort();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setConnectTimeout(Duration.ofMillis(5000))
                .setRequestTimeout(Duration.ofMillis(10000)))) {
            String target = "https://localhost:" + port2 + '/';
            Future<Response> f = client.prepareGet(target)
                    .setProxyServer(new ProxyServer.Builder("127.0.0.1", freePort)
                            .setProxyType(ProxyType.SOCKS_V5))
                    .execute();
            assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS),
                    "Request should fail when SOCKS5 proxy is not running, not bypass proxy");
        }
    }

    /**
     * Validates that when a SOCKS4 proxy is configured at an address where no
     * SOCKS server is running, an HTTPS request FAILS instead of silently
     * bypassing the proxy and using normal routing.
     */
    @Test
    public void testSocks4ProxyNotRunningMustFailHttps() throws Exception {
        int freePort = findFreePort();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setConnectTimeout(Duration.ofMillis(5000))
                .setRequestTimeout(Duration.ofMillis(10000)))) {
            String target = "https://localhost:" + port2 + '/';
            Future<Response> f = client.prepareGet(target)
                    .setProxyServer(new ProxyServer.Builder("127.0.0.1", freePort)
                            .setProxyType(ProxyType.SOCKS_V4))
                    .execute();
            assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS),
                    "Request should fail when SOCKS4 proxy is not running, not bypass proxy");
        }
    }

    /**
     * Validates that per-request SOCKS5 proxy config with a non-existent proxy
     * also correctly fails the request.
     */
    @Test
    public void testPerRequestSocks5ProxyNotRunningMustFail() throws Exception {
        int freePort = findFreePort();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setConnectTimeout(Duration.ofMillis(5000))
                .setRequestTimeout(Duration.ofMillis(10000)))) {
            String target = "http://localhost:" + port1 + '/';
            Future<Response> f = client.prepareGet(target)
                    .setProxyServer(new ProxyServer.Builder("127.0.0.1", freePort)
                            .setProxyType(ProxyType.SOCKS_V5))
                    .execute();
            assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS),
                    "Per-request SOCKS5 proxy config should not be silently ignored");
        }
    }

    /**
     * Validates that client-level SOCKS5 proxy config with a non-existent proxy
     * also correctly fails the request.
     */
    @Test
    public void testClientLevelSocks5ProxyNotRunningMustFail() throws Exception {
        int freePort = findFreePort();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setProxyServer(new ProxyServer.Builder("127.0.0.1", freePort)
                        .setProxyType(ProxyType.SOCKS_V5))
                .setConnectTimeout(Duration.ofMillis(5000))
                .setRequestTimeout(Duration.ofMillis(10000)))) {
            String target = "http://localhost:" + port1 + '/';
            Future<Response> f = client.prepareGet(target).execute();
            assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS),
                    "Client-level SOCKS5 proxy config should not be silently ignored");
        }
    }
}
