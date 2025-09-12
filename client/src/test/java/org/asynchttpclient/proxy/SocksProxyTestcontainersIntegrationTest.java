/*
 *    Copyright (c) 2025 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.proxy;

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for SOCKS proxy support using Dante SOCKS server in TestContainers.
 * This validates the fix for GitHub issue #1913.
 */
@Testcontainers
public class SocksProxyTestcontainersIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocksProxyTestcontainersIntegrationTest.class);

    private static final int SOCKS_PORT = 1080;

    private static final String TARGET_HTTP_URL = "http://httpbin.org/get";
    private static final String TARGET_HTTPS_URL = "https://www.example.com/";

    private static boolean dockerAvailable = false;
    private static GenericContainer<?> socksProxy;

    @BeforeAll
    static void checkDockerAvailability() {
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
            LOGGER.info("Docker availability check: {}", dockerAvailable);
        } catch (Exception e) {
            LOGGER.warn("Failed to check Docker availability: {}", e.getMessage());
            dockerAvailable = false;
        }
        // Skip tests if Docker not available, unless force-enabled
        if (!dockerAvailable && !"true".equals(System.getProperty("docker.tests"))) {
            LOGGER.info("Docker is not available - skipping integration tests. Use -Ddocker.tests=true to force run.");
            return; // Don't start container if Docker not available
        }
        // Allow force-disabling Docker tests
        if ("true".equals(System.getProperty("no.docker.tests"))) {
            LOGGER.info("Docker tests disabled via -Dno.docker.tests=true");
            return;
        }
        // Only start container if Docker is available
        if (dockerAvailable) {
            try {
                socksProxy = new GenericContainer<>(
                        new ImageFromDockerfile()
                                .withFileFromPath("Dockerfile", Path.of("src/test/resources/dante/Dockerfile"))
                                .withFileFromPath("sockd.conf", Path.of("src/test/resources/dante/sockd.conf"))
                )
                        .withExposedPorts(SOCKS_PORT)
                        .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix("DANTE"))
                        .waitingFor(Wait.forLogMessage(".*sockd.*", 1)
                                .withStartupTimeout(Duration.ofMinutes(2)));
                socksProxy.start();
                LOGGER.info("Dante SOCKS proxy started successfully on port {}", socksProxy.getMappedPort(SOCKS_PORT));
            } catch (Exception e) {
                LOGGER.warn("Failed to start Dante SOCKS proxy container: {}", e.getMessage());
                dockerAvailable = false; // Mark as unavailable if container start fails
            }
        }
    }

    @AfterAll
    static void stopContainer() {
        if (socksProxy != null && socksProxy.isRunning()) {
            socksProxy.stop();
        }
    }

    @RepeatedIfExceptionsTest(repeats = 3)
    public void testSocks4ProxyToHttpTarget() throws Exception {
        assumeTrue(dockerAvailable, "Docker is not available - skipping test");
        LOGGER.info("Testing SOCKS4 proxy to HTTP target");
        AsyncHttpClientConfig config = config()
                .setProxyServer(proxyServer("localhost", socksProxy.getMappedPort(SOCKS_PORT))
                        .setProxyType(ProxyType.SOCKS_V4)
                        .build())
                .setConnectTimeout(Duration.ofMillis(10000))
                .setRequestTimeout(Duration.ofMillis(30000))
                .build();
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(TARGET_HTTP_URL)).get(30, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("httpbin"));
            LOGGER.info("SOCKS4 proxy to HTTP target test passed");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 3)
    public void testSocks5ProxyToHttpTarget() throws Exception {
        assumeTrue(dockerAvailable, "Docker is not available - skipping test");
        LOGGER.info("Testing SOCKS5 proxy to HTTP target");
        AsyncHttpClientConfig config = config()
                .setProxyServer(proxyServer("localhost", socksProxy.getMappedPort(SOCKS_PORT))
                        .setProxyType(ProxyType.SOCKS_V5)
                        .build())
                .setConnectTimeout(Duration.ofMillis(10000))
                .setRequestTimeout(Duration.ofMillis(30000))
                .build();
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(TARGET_HTTP_URL)).get(30, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("httpbin"));
            LOGGER.info("SOCKS5 proxy to HTTP target test passed");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 3)
    public void testSocks4ProxyToHttpsTarget() throws Exception {
        assumeTrue(dockerAvailable, "Docker is not available - skipping test");
        LOGGER.info("Testing SOCKS4 proxy to HTTPS target - validates issue #1913 fix");
        AsyncHttpClientConfig config = config()
                .setProxyServer(proxyServer("localhost", socksProxy.getMappedPort(SOCKS_PORT))
                        .setProxyType(ProxyType.SOCKS_V4)
                        .build())
                .setUseInsecureTrustManager(true)
                .setConnectTimeout(Duration.ofMillis(10000))
                .setRequestTimeout(Duration.ofMillis(30000))
                .build();
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(TARGET_HTTPS_URL)).get(30, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("Example Domain") ||
                    response.getResponseBody().contains("example"));
            LOGGER.info("SOCKS4 proxy to HTTPS target test passed - issue #1913 RESOLVED!");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 3)
    public void testSocks5ProxyToHttpsTarget() throws Exception {
        assumeTrue(dockerAvailable, "Docker is not available - skipping test");
        LOGGER.info("Testing SOCKS5 proxy to HTTPS target - validates issue #1913 fix");
        AsyncHttpClientConfig config = config()
                .setProxyServer(proxyServer("localhost", socksProxy.getMappedPort(SOCKS_PORT))
                        .setProxyType(ProxyType.SOCKS_V5)
                        .build())
                .setUseInsecureTrustManager(true)
                .setConnectTimeout(Duration.ofMillis(10000))
                .setRequestTimeout(Duration.ofMillis(30000))
                .build();
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(TARGET_HTTPS_URL)).get(30, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("Example Domain") ||
                    response.getResponseBody().contains("example"));
            LOGGER.info("SOCKS5 proxy to HTTPS target test passed - issue #1913 RESOLVED!");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 3)
    public void testIssue1913ReproductionWithRealProxy() throws Exception {
        assumeTrue(dockerAvailable, "Docker is not available - skipping test");
        LOGGER.info("Testing exact issue #1913 reproduction with real SOCKS proxy");
        
        // This reproduces the exact scenario from the GitHub issue but with a real working proxy
        var proxyServer = proxyServer("localhost", socksProxy.getMappedPort(SOCKS_PORT))
                .setProxyType(ProxyType.SOCKS_V5);

        try (var client = asyncHttpClient(config()
                .setProxyServer(proxyServer)
                .setUseInsecureTrustManager(true)
                .setConnectTimeout(Duration.ofMillis(10000))
                .setRequestTimeout(Duration.ofMillis(30000)))) {
            
            // This would previously throw: java.util.NoSuchElementException: socks
            var response = client.prepareGet("https://www.example.com/").execute().get(30, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("Example Domain") ||
                    response.getResponseBody().contains("example"));
            LOGGER.info("Issue #1913 reproduction test PASSED - NoSuchElementException: socks is FIXED!");
        }
    }

    @Test
    public void testDockerInfrastructureReady() {
        assumeTrue(dockerAvailable, "Docker is not available - skipping test");
        LOGGER.info("Docker infrastructure test - validating Dante SOCKS container is ready");
        LOGGER.info("Dante SOCKS proxy available at: localhost:{}", socksProxy.getMappedPort(SOCKS_PORT));
        assertTrue(socksProxy.isRunning(), "Dante SOCKS container should be running");
        assertTrue(socksProxy.getMappedPort(SOCKS_PORT) > 0, "SOCKS port should be mapped");
        LOGGER.info("Dante SOCKS infrastructure is ready and accessible");
    }
}
