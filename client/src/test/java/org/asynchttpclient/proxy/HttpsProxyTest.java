/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
import org.asynchttpclient.proxy.ProxyServer.Builder;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.test.EchoHandler;
import org.asynchttpclient.util.HttpConstants;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.post;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

/**
 * Proxy usage tests.
 */
public class HttpsProxyTest extends AbstractBasicTest {

    private Server server2;

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ProxyHandler();
    }

    @Override
    @BeforeEach
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(configureHandler());
        server.start();
        port1 = connector.getLocalPort();

        server2 = new Server();
        ServerConnector connector2 = addHttpsConnector(server2);
        server2.setHandler(new EchoHandler());
        server2.start();
        port2 = connector2.getLocalPort();

        logger.info("Local HTTP server started successfully");
    }

    @Override
    @AfterEach
    public void tearDownGlobal() throws Exception {
        server.stop();
        server2.stop();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testRequestProxy() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true))) {
            RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("localhost", port1));
            Response response = client.executeRequest(rb.build()).get();
            assertEquals(200, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testConfigProxy() throws Exception {
        AsyncHttpClientConfig config = config()
                .setFollowRedirect(true)
                .setProxyServer(proxyServer("localhost", port1).build())
                .setUseInsecureTrustManager(true)
                .build();

        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(get(getTargetUrl2())).get();
            assertEquals(200, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testNoDirectRequestBodyWithProxy() throws Exception {
        AsyncHttpClientConfig config = config()
                .setFollowRedirect(true)
                .setProxyServer(proxyServer("localhost", port1).build())
                .setUseInsecureTrustManager(true)
                .build();

        try (AsyncHttpClient client = asyncHttpClient(config)) {
            Response response = client.executeRequest(post(getTargetUrl2()).setBody(new ByteArrayBodyGenerator(LARGE_IMAGE_BYTES))).get();
            assertEquals(200, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDecompressBodyWithProxy() throws Exception {
        AsyncHttpClientConfig config = config()
                .setFollowRedirect(true)
                .setProxyServer(proxyServer("localhost", port1).build())
                .setUseInsecureTrustManager(true)
                .build();

        try (AsyncHttpClient client = asyncHttpClient(config)) {
            String body = "hello world";
            Response response = client.executeRequest(post(getTargetUrl2())
                    .setHeader("X-COMPRESS", "true")
                    .setBody(body)).get();

            assertEquals(200, response.getStatusCode());
            assertEquals(body, response.getResponseBody());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPooledConnectionsWithProxy() throws Exception {
        try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true).setKeepAlive(true))) {
            RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer("localhost", port1));

            Response response1 = asyncHttpClient.executeRequest(rb.build()).get();
            assertEquals(200, response1.getStatusCode());

            Response response2 = asyncHttpClient.executeRequest(rb.build()).get();
            assertEquals(200, response2.getStatusCode());
        }
    }
    
    @RepeatedIfExceptionsTest(repeats = 5)
    public void testFailedConnectWithProxy() throws Exception {
        try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setFollowRedirect(true).setUseInsecureTrustManager(true).setKeepAlive(true))) {
        	Builder proxyServer = proxyServer("localhost", port1);
        	proxyServer.setCustomHeaders(r -> r.getHeaders().add(ProxyHandler.HEADER_FORBIDDEN, "1"));
            RequestBuilder rb = get(getTargetUrl2()).setProxyServer(proxyServer);

            Response response1 = asyncHttpClient.executeRequest(rb.build()).get();
            assertEquals(403, response1.getStatusCode());

            Response response2 = asyncHttpClient.executeRequest(rb.build()).get();
            assertEquals(403, response2.getStatusCode());
            
            Response response3 = asyncHttpClient.executeRequest(rb.build()).get();
            assertEquals(403, response3.getStatusCode());
        }
    }
    
    public static class ProxyHandler extends ConnectHandler {
    	final static String HEADER_FORBIDDEN = "X-REJECT-REQUEST";

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if (HttpConstants.Methods.CONNECT.equalsIgnoreCase(request.getMethod())) {
                if (request.getHeader(HEADER_FORBIDDEN) != null) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    r.setHandled(true);
                    return;
                }
            }
            super.handle(s, r, request, response);
        }
    }
}
