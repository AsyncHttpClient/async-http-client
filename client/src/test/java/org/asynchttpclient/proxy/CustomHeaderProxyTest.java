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
import io.netty.handler.codec.http.DefaultHttpHeaders;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.test.EchoHandler;
import org.asynchttpclient.util.HttpConstants;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.post;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proxy usage tests.
 */
public class CustomHeaderProxyTest extends AbstractBasicTest {

    private static Server server2;

    private static final String customHeaderName = "Custom-Header";
    private static final String customHeaderValue = "Custom-Value";

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new ProxyHandler(customHeaderName, customHeaderValue);
    }

    @Override
    @BeforeAll
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
    @AfterAll
    public void tearDownGlobal() throws Exception {
        server.stop();
        server2.stop();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testHttpProxy() throws Exception {
        AsyncHttpClientConfig config = config()
                .setFollowRedirect(true)
                .setProxyServer(
                        proxyServer("localhost", port1)
                                .setCustomHeaders(req -> new DefaultHttpHeaders().add(customHeaderName, customHeaderValue))
                                .build()
                )
                .setUseInsecureTrustManager(true)
                .build();
        try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config)) {
            Response r = asyncHttpClient.executeRequest(post(getTargetUrl2()).setBody(new ByteArrayBodyGenerator(LARGE_IMAGE_BYTES))).get();
            assertEquals(200, r.getStatusCode());
        }
    }

    public static class ProxyHandler extends ConnectHandler {
        String customHeaderName;
        String customHeaderValue;

        public ProxyHandler(String customHeaderName, String customHeaderValue) {
            this.customHeaderName = customHeaderName;
            this.customHeaderValue = customHeaderValue;
        }

        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if (HttpConstants.Methods.CONNECT.equalsIgnoreCase(request.getMethod())) {
                if (request.getHeader(customHeaderName).equals(customHeaderValue)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    super.handle(s, r, request, response);
                } else {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    r.setHandled(true);
                }
            } else {
                super.handle(s, r, request, response);
            }
        }
    }
}
