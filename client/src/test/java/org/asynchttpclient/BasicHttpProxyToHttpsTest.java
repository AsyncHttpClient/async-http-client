/*
 *    Copyright (c) 2016-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.USER_AGENT;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.Dsl.realm;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUserAgent;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test that validates that when having an HTTP proxy and trying to access an HTTPS
 * through the proxy the proxy credentials and a custom user-agent (if set) should be passed during the CONNECT request.
 */
public class BasicHttpProxyToHttpsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BasicHttpProxyToHttpsTest.class);
    private static final String CUSTOM_USER_AGENT = "custom-user-agent";

    private int httpPort;
    private int proxyPort;

    private Server httpServer;
    private Server proxy;

    @BeforeEach
    public void setUpGlobal() throws Exception {
        // HTTP server
        httpServer = new Server();
        ServerConnector connector1 = addHttpsConnector(httpServer);
        httpServer.setHandler(new EchoHandler());
        httpServer.start();
        httpPort = connector1.getLocalPort();

        // proxy
        proxy = new Server();
        ServerConnector connector2 = addHttpConnector(proxy);
        ConnectHandler connectHandler = new ConnectHandler() {

            @Override
            // This proxy receives a CONNECT request from the client before making the real request for the target host.
            protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address) {

                // If the userAgent of the CONNECT request is the same as the default userAgent,
                // then the custom userAgent was not properly propagated and the test should fail.
                String userAgent = request.getHeader(USER_AGENT.toString());
                if (userAgent.equals(defaultUserAgent())) {
                    return false;
                }

                // If the authentication failed, the test should also fail.
                String authorization = request.getHeader(PROXY_AUTHORIZATION.toString());
                if (authorization == null) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.setHeader(PROXY_AUTHENTICATE.toString(), "Basic realm=\"Fake Realm\"");
                    return false;
                }
                if ("Basic am9obmRvZTpwYXNz".equals(authorization)) {
                    return true;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        };
        proxy.setHandler(connectHandler);
        proxy.start();
        proxyPort = connector2.getLocalPort();

        LOGGER.info("Local HTTP Server (" + httpPort + "), Proxy (" + proxyPort + ") started successfully");
    }

    @AfterEach
    public void tearDownGlobal() throws Exception {
        httpServer.stop();
        proxy.stop();
    }

    @Test
    public void nonPreemptiveProxyAuthWithHttpsTarget() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setUseInsecureTrustManager(true))) {
            String targetUrl = "https://localhost:" + httpPort + "/foo/bar";
            Request request = get(targetUrl)
                    .setProxyServer(proxyServer("127.0.0.1", proxyPort).setRealm(realm(AuthScheme.BASIC, "johndoe", "pass")))
                    .setHeader("user-agent", CUSTOM_USER_AGENT)
                    // .setRealm(realm(AuthScheme.BASIC, "user", "passwd"))
                    .build();
            Future<Response> responseFuture = client.executeRequest(request);
            Response response = responseFuture.get();

            assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals("/foo/bar", response.getHeader("X-pathInfo"));
        }
    }
}
