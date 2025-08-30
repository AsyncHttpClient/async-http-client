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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
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

@Testcontainers
public class HttpsProxyTestcontainersIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpsProxyTestcontainersIntegrationTest.class);
    
    private static final int SQUID_HTTP_PORT = 3128;
    private static final int SQUID_HTTPS_PORT = 3129;
    
    private static final String TARGET_HTTP_URL = "http://httpbin.org/get";
    private static final String TARGET_HTTPS_URL = "https://www.example.com/";
    
    private static boolean dockerAvailable = false;
    
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
            assumeTrue(false, "Docker is not available - skipping integration tests. Use -Ddocker.tests=true to force run.");
        }
        
        // Allow force-disabling Docker tests
        if ("true".equals(System.getProperty("no.docker.tests"))) {
            assumeTrue(false, "Docker tests disabled via -Dno.docker.tests=true");
        }
    }

    /**
     * Self-contained Squid proxy container that generates its own certificates and configuration.
     * The container is automatically started before tests and stopped after tests.
     */
    @Container
    static final GenericContainer<?> squidProxy = new GenericContainer<>(
        new ImageFromDockerfile()
            .withFileFromPath("Dockerfile", Path.of("src/test/resources/squid/Dockerfile"))
            .withFileFromPath("squid.conf", Path.of("src/test/resources/squid/squid.conf"))
        )
        .withExposedPorts(SQUID_HTTP_PORT, SQUID_HTTPS_PORT)
        .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix("SQUID"))
        .waitingFor(Wait.forLogMessage(".*Accepting HTTP.*", 1)
            .withStartupTimeout(Duration.ofMinutes(2)));

    @RepeatedIfExceptionsTest(repeats = 3)
    public void testHttpProxyToHttpTarget() throws Exception {
        LOGGER.info("Testing HTTP proxy to HTTP target");
        
        AsyncHttpClientConfig config = config()
            .setProxyServer(proxyServer("localhost", squidProxy.getMappedPort(SQUID_HTTP_PORT))
                .setProxyType(ProxyType.HTTP)
                .build())
            .setConnectTimeout(Duration.ofMillis(10000))
            .setRequestTimeout(Duration.ofMillis(30000))
            .build();
            
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(TARGET_HTTP_URL)).get(30, TimeUnit.SECONDS);
            
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("httpbin"));
            
            LOGGER.info("HTTP proxy to HTTP target test passed");
        }
    }
    
    @RepeatedIfExceptionsTest(repeats = 3)
    public void testHttpsProxyToHttpTarget() throws Exception {
        LOGGER.info("Testing HTTPS proxy to HTTP target");
        
        AsyncHttpClientConfig config = config()
            .setProxyServer(proxyServer("localhost", squidProxy.getMappedPort(SQUID_HTTPS_PORT))
                .setProxyType(ProxyType.HTTPS)
                .build())
            .setUseInsecureTrustManager(true)  // Accept self-signed proxy cert
            .setConnectTimeout(Duration.ofMillis(10000))
            .setRequestTimeout(Duration.ofMillis(30000))
            .build();
            
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(TARGET_HTTP_URL)).get(30, TimeUnit.SECONDS);
            
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("httpbin"));
            
            LOGGER.info("HTTPS proxy to HTTP target test passed");
        }
    }
    
    @RepeatedIfExceptionsTest(repeats = 3)
    public void testHttpProxyToHttpsTarget() throws Exception {
        LOGGER.info("Testing HTTP proxy to HTTPS target");
        
        AsyncHttpClientConfig config = config()
            .setProxyServer(proxyServer("localhost", squidProxy.getMappedPort(SQUID_HTTP_PORT))
                .setProxyType(ProxyType.HTTP)
                .build())
            .setUseInsecureTrustManager(true)  // Accept any HTTPS target cert
            .setConnectTimeout(Duration.ofMillis(10000))
            .setRequestTimeout(Duration.ofMillis(30000))
            .build();
            
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(TARGET_HTTPS_URL)).get(30, TimeUnit.SECONDS);
            
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("Example Domain") || 
                      response.getResponseBody().contains("example"));
            
            LOGGER.info("HTTP proxy to HTTPS target test passed");
        }
    }
    
    @RepeatedIfExceptionsTest(repeats = 3)
    public void testHttpsProxyToHttpsTarget() throws Exception {
        LOGGER.info("Testing HTTPS proxy to HTTPS target - validates issue #1907 fix");
        
        AsyncHttpClientConfig config = config()
            .setProxyServer(proxyServer("localhost", squidProxy.getMappedPort(SQUID_HTTPS_PORT))
                .setProxyType(ProxyType.HTTPS)
                .build())
            .setUseInsecureTrustManager(true)  // Accept self-signed proxy cert and any HTTPS target cert
            .setConnectTimeout(Duration.ofMillis(10000))
            .setRequestTimeout(Duration.ofMillis(30000))
            .build();
            
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(TARGET_HTTPS_URL)).get(30, TimeUnit.SECONDS);
            
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().contains("Example Domain") || 
                      response.getResponseBody().contains("example"));
            
            LOGGER.info("HTTPS proxy to HTTPS target test passed - core issue #1907 RESOLVED!");
        }
    }
    
    @Test
    public void testDockerInfrastructureReady() {
        LOGGER.info("Docker infrastructure test - validating container is ready");
        LOGGER.info("Squid HTTP proxy available at: localhost:{}", squidProxy.getMappedPort(SQUID_HTTP_PORT));
        LOGGER.info("Squid HTTPS proxy available at: localhost:{}", squidProxy.getMappedPort(SQUID_HTTPS_PORT));
        
        assertTrue(squidProxy.isRunning(), "Squid container should be running");
        assertTrue(squidProxy.getMappedPort(SQUID_HTTP_PORT) > 0, "HTTP port should be mapped");
        assertTrue(squidProxy.getMappedPort(SQUID_HTTPS_PORT) > 0, "HTTPS port should be mapped");
        
        LOGGER.info("Docker infrastructure is ready and accessible");
    }
}
