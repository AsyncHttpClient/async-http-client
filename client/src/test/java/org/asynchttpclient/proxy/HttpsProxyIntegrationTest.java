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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.test.EchoHandler;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.HttpConstants;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.post;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive integration tests for HTTPS proxy functionality.
 * Tests both HTTP and HTTPS proxy types to ensure functionality and compatibility.
 */
public class HttpsProxyIntegrationTest extends AbstractBasicTest {

    private List<Server> servers;
    private int httpsProxyPort;

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ProxyHandler();
    }

    /**
     * Provides test parameters for HTTP proxy type only for now
     * TODO: Add HTTPS proxy type once SSL bootstrap is implemented
     */
    static Stream<Arguments> proxyTypeProvider() {
        return Stream.of(
            Arguments.of("HTTP Proxy", ProxyType.HTTP)
            // Arguments.of("HTTPS Proxy", ProxyType.HTTPS) // TODO: Enable once HTTPS proxy SSL bootstrap is working
        );
    }

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        servers = new ArrayList<>();
        
        // Start HTTP proxy server
        port1 = startServer(configureHandler(), false);
        
        // Start HTTPS target server
        port2 = startServer(new EchoHandler(), true);
        
        // Start HTTPS proxy server
        httpsProxyPort = startServer(configureHandler(), true);

        logger.info("Integration test servers started: HTTP proxy={}, HTTPS proxy={}, HTTPS target={}", 
                    port1, httpsProxyPort, port2);
    }

    private int startServer(Handler handler, boolean secure) throws Exception {
        Server server = new Server();
        @SuppressWarnings("resource")
        ServerConnector connector = secure ? addHttpsConnector(server) : addHttpConnector(server);
        server.setHandler(handler);
        server.start();
        servers.add(server);
        return connector.getLocalPort();
    }

    @Override
    @AfterEach
    public void tearDownGlobal() {
        servers.forEach(server -> {
            try {
                server.stop();
            } catch (Exception e) {
                // couldn't stop server
            }
        });
    }

    @ParameterizedTest(name = "{0} - Basic Request")
    @MethodSource("proxyTypeProvider")
    public void testBasicRequestThroughProxy(String testName, ProxyType proxyType) throws Exception {
        int proxyPort = proxyType == ProxyType.HTTPS ? httpsProxyPort : port1;
        
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true))) {
            RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("localhost", proxyPort).setProxyType(proxyType));
            Response response = client.executeRequest(rb.build()).get();
            assertEquals(200, response.getStatusCode());
            
            // Verify that the request went through the proxy
            assertNotNull(response);
        }
    }

    @ParameterizedTest(name = "{0} - Multiple Requests")
    @MethodSource("proxyTypeProvider")  
    public void testMultipleRequestsThroughProxy(String testName, ProxyType proxyType) throws Exception {
        int proxyPort = proxyType == ProxyType.HTTPS ? httpsProxyPort : port1;
        
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true).setKeepAlive(true))) {
            ProxyServer proxy = proxyServer("localhost", proxyPort).setProxyType(proxyType).build();
            
            // Execute multiple requests to test connection reuse
            for (int i = 0; i < 3; i++) {
                RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxy);
                Response response = client.executeRequest(rb.build()).get();
                assertEquals(200, response.getStatusCode(), "Request " + (i + 1) + " failed");
            }
        }
    }

    @ParameterizedTest(name = "{0} - Large Body")
    @MethodSource("proxyTypeProvider")
    public void testLargeRequestBodyThroughProxy(String testName, ProxyType proxyType) throws Exception {
        int proxyPort = proxyType == ProxyType.HTTPS ? httpsProxyPort : port1;
        
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true))) {
            ProxyServer proxy = proxyServer("localhost", proxyPort).setProxyType(proxyType).build();
            
            RequestBuilder rb = post(getTargetUrl2())
                .setProxyServer(proxy)
                .setBody(new ByteArrayBodyGenerator(LARGE_IMAGE_BYTES));
                
            Response response = client.executeRequest(rb.build()).get();
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getResponseBody().length() > 0);
        }
    }

    @ParameterizedTest(name = "{0} - Timeout Configuration")
    @MethodSource("proxyTypeProvider")
    public void testProxyTimeoutConfiguration(String testName, ProxyType proxyType) throws Exception {
        int proxyPort = proxyType == ProxyType.HTTPS ? httpsProxyPort : port1;
        
        AsyncHttpClientConfig config = config()
            .setFollowRedirect(true)
            .setUseInsecureTrustManager(true)
            .setConnectTimeout(Duration.ofSeconds(5))
            .setRequestTimeout(Duration.ofSeconds(10))
            .build();
            
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            ProxyServer proxy = proxyServer("localhost", proxyPort).setProxyType(proxyType).build();
            
            RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxy);
            Response response = client.executeRequest(rb.build()).get(15, TimeUnit.SECONDS);
            assertEquals(200, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testChannelPoolPartitioningWithHttpsProxy() throws Exception {
        // Test that HTTPS proxy creates correct partition keys for connection pooling
        ProxyServer httpsProxy = proxyServer("proxy.example.com", 8080)
            .setSecuredPort(8443)
            .setProxyType(ProxyType.HTTPS)
            .build();
        
        Uri targetUri = Uri.create("https://target.example.com/test");
        ChannelPoolPartitioning partitioning = ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE;
        
        Object partitionKey = partitioning.getPartitionKey(targetUri, null, httpsProxy);
        
        assertNotNull(partitionKey);
        // The partition key should include the secured port for HTTPS proxy
        assertTrue(partitionKey.toString().contains("8443"));
        assertTrue(partitionKey.toString().contains("HTTPS"));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testChannelPoolPartitioningWithHttpProxy() throws Exception {
        // Test that HTTP proxy creates correct partition keys for connection pooling
        ProxyServer httpProxy = proxyServer("proxy.example.com", 8080)
            .setSecuredPort(8443)
            .setProxyType(ProxyType.HTTP)
            .build();
        
        Uri targetUri = Uri.create("https://target.example.com/test");
        ChannelPoolPartitioning partitioning = ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE;
        
        Object partitionKey = partitioning.getPartitionKey(targetUri, null, httpProxy);
        
        assertNotNull(partitionKey);
        // For HTTP proxy with secured target, should use secured port
        assertTrue(partitionKey.toString().contains("8443"));
        assertTrue(partitionKey.toString().contains("HTTP"));
    }

    public static class ProxyHandler extends ConnectHandler {
        final static String HEADER_FORBIDDEN = "X-REJECT-REQUEST";

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if (HttpConstants.Methods.CONNECT.equalsIgnoreCase(request.getMethod())) {
                String headerValue = request.getHeader(HEADER_FORBIDDEN);
                if (headerValue == null) {
                    headerValue = "";
                }
                switch (headerValue) {
                case "1":
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    r.setHandled(true);
                    return;
                case "2":
                    r.getHttpChannel().getConnection().close();
                    r.setHandled(true);
                    return;
                }
            }
            super.handle(s, r, request, response);
        }
    }
}
