/*
 * Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
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

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for SOCKS proxy support with both HTTP and HTTPS.
 */
public class SocksProxyTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ProxyTest.ProxyHandler();
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

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSocks5ProxyWithHttp() throws Exception {
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
                    .setProxyServer(new ProxyServer.Builder("localhost", 8000).setProxyType(ProxyType.SOCKS_V5))
                    .execute();

            Response response = f.get(60, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    public void testSocks5ProxyWithHttpsDoesNotThrowException() throws Exception {
        // This test specifically verifies that HTTPS requests through SOCKS5 proxy
        // do not throw NoSuchElementException: socks anymore

        // Start SOCKS proxy in background thread
        Thread socksProxyThread = new Thread(() -> {
            try {
                new SocksProxy(10000); // shorter time for test
            } catch (Exception e) {
                logger.error("Failed to establish SocksProxy", e);
            }
        });
        socksProxyThread.start();

        // Give the proxy time to start
        Thread.sleep(1000);

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setProxyServer(new ProxyServer.Builder("localhost", 8000).setProxyType(ProxyType.SOCKS_V5))
                .setConnectTimeout(Duration.ofMillis(5000))
                .setRequestTimeout(Duration.ofMillis(10000)))) {

            // This would previously throw: java.util.NoSuchElementException: socks
            // We expect this to fail with connection timeout (since we don't have a real HTTPS target)
            // but NOT with NoSuchElementException

            try {
                Future<Response> f = client.prepareGet("https://httpbin.org/get").execute();
                f.get(8, TimeUnit.SECONDS);
                // If we reach here, great! The SOCKS proxy worked
            } catch (Exception e) {
                // We should NOT see NoSuchElementException: socks anymore
                String message = e.getMessage();
                if (message != null && message.contains("socks") && message.contains("NoSuchElementException")) {
                    throw new AssertionError("NoSuchElementException: socks still occurs", e);
                }
                // Other exceptions like connection timeout are expected since we don't have a real working SOCKS proxy setup
                logger.info("Expected exception (not the SOCKS handler bug): " + e.getClass().getSimpleName() + ": " + message);
            }
        }
    }

    @Test
    public void testSocks4ProxyWithHttpsDoesNotThrowException() throws Exception {
        // This test specifically verifies that HTTPS requests through SOCKS4 proxy
        // do not throw NoSuchElementException: socks anymore

        // Start SOCKS proxy in background thread
        Thread socksProxyThread = new Thread(() -> {
            try {
                new SocksProxy(10000); // shorter time for test
            } catch (Exception e) {
                logger.error("Failed to establish SocksProxy", e);
            }
        });
        socksProxyThread.start();

        // Give the proxy time to start
        Thread.sleep(1000);

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setProxyServer(new ProxyServer.Builder("localhost", 8000).setProxyType(ProxyType.SOCKS_V4))
                .setConnectTimeout(Duration.ofMillis(5000))
                .setRequestTimeout(Duration.ofMillis(10000)))) {

            // This would previously throw: java.util.NoSuchElementException: socks
            // We expect this to fail with connection timeout (since we don't have a real HTTPS target)
            // but NOT with NoSuchElementException

            try {
                Future<Response> f = client.prepareGet("https://httpbin.org/get").execute();
                f.get(8, TimeUnit.SECONDS);
                // If we reach here, great! The SOCKS proxy worked
            } catch (Exception e) {
                // We should NOT see NoSuchElementException: socks anymore
                String message = e.getMessage();
                if (message != null && message.contains("socks") && message.contains("NoSuchElementException")) {
                    throw new AssertionError("NoSuchElementException: socks still occurs", e);
                }
                // Other exceptions like connection timeout are expected since we don't have a real working SOCKS proxy setup
                logger.info("Expected exception (not the SOCKS handler bug): " + e.getClass().getSimpleName() + ": " + message);
            }
        }
    }

    @Test
    public void testIssue1913NoSuchElementExceptionSocks5() throws Exception {
        // Reproduces the exact issue from GitHub issue #1913 with SOCKS5
        // This uses the exact code pattern from the issue report
        var proxyServer = new ProxyServer.Builder("127.0.0.1", 1081)
                .setProxyType(ProxyType.SOCKS_V5);

        try (var client = asyncHttpClient(config()
                .setProxyServer(proxyServer.build())
                .setConnectTimeout(Duration.ofMillis(2000))
                .setRequestTimeout(Duration.ofMillis(5000)))) {

            // This would previously throw: java.util.NoSuchElementException: socks
            // We expect this to fail with connection timeout (since proxy doesn't exist)
            // but NOT with NoSuchElementException

            try {
                var response = client.prepareGet("https://cloudflare.com/cdn-cgi/trace").execute().get();
                // If we reach here, great! The fix worked and proxy connection succeeded
                logger.info("Connection successful: " + response.getStatusCode());
            } catch (Exception e) {
                // Check that we don't get the NoSuchElementException: socks anymore
                Throwable cause = e.getCause();
                String message = cause != null ? cause.getMessage() : e.getMessage();

                // This should NOT contain the original error
                if (message != null && message.contains("socks") && 
                    (e.toString().contains("NoSuchElementException") || cause != null && cause.toString().contains("NoSuchElementException"))) {
                    throw new AssertionError("NoSuchElementException: socks still occurs - fix didn't work: " + e.toString());
                }

                // Other exceptions like connection timeout are expected since we don't have a working SOCKS proxy
                logger.info("Expected exception (not the SOCKS handler bug): " + e.getClass().getSimpleName() + ": " + message);
            }
        }
    }

    @Test 
    public void testIssue1913NoSuchElementExceptionSocks4() throws Exception {
        // Reproduces the exact issue from GitHub issue #1913 with SOCKS4
        // This uses the exact code pattern from the issue report
        var proxyServer = new ProxyServer.Builder("127.0.0.1", 1081)
                .setProxyType(ProxyType.SOCKS_V4);

        try (var client = asyncHttpClient(config()
                .setProxyServer(proxyServer.build())
                .setConnectTimeout(Duration.ofMillis(2000))
                .setRequestTimeout(Duration.ofMillis(5000)))) {

            try {
                var response = client.prepareGet("https://cloudflare.com/cdn-cgi/trace").execute().get();
                logger.info("Connection successful: " + response.getStatusCode());
            } catch (Exception e) {
                // Check that we don't get the NoSuchElementException: socks anymore
                Throwable cause = e.getCause();
                String message = cause != null ? cause.getMessage() : e.getMessage();

                if (message != null && message.contains("socks") && 
                    (e.toString().contains("NoSuchElementException") || cause != null && cause.toString().contains("NoSuchElementException"))) {
                    throw new AssertionError("NoSuchElementException: socks still occurs - fix didn't work: " + e.toString());
                }

                logger.info("Expected exception (not the SOCKS handler bug): " + e.getClass().getSimpleName() + ": " + message);
            }
        }
    }
}
