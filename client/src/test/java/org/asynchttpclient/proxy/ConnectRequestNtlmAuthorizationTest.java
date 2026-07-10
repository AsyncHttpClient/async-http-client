/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.test.EchoHandler;
import org.asynchttpclient.util.HttpConstants;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.ntlmAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NTLM (like Kerberos and SPNEGO) uses {@code perConnectionAuthorizationHeader} in {@code NettyRequestSender}
 * rather than the per-request path in {@code NettyRequestFactory}. That header must not be attached to the
 * CONNECT request, which is sent to the proxy in the clear before the tunnel exists.
 */
public class ConnectRequestNtlmAuthorizationTest {

    private final List<Server> servers = new ArrayList<>();
    private int httpsPort;
    private int proxyPort;
    private final AtomicReference<String> connectAuthorization = new AtomicReference<>();

    private int startServer(Handler handler, boolean secure) throws Exception {
        Server server = new Server();
        ServerConnector connector = secure ? addHttpsConnector(server) : addHttpConnector(server);
        server.setHandler(handler);
        server.start();
        servers.add(server);
        return connector.getLocalPort();
    }

    @BeforeEach
    public void setUp() throws Exception {
        httpsPort = startServer(new EchoHandler(), true);
        proxyPort = startServer(new RecordingConnectHandler(), false);
    }

    @AfterEach
    public void tearDown() {
        servers.forEach(server -> {
            try {
                server.stop();
            } catch (Exception ignored) {
                // couldn't stop server
            }
        });
    }

    @Test
    public void connectRequestDoesNotCarryPreemptiveNtlmAuthorization() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setUseInsecureTrustManager(true))) {
            RequestBuilder rb = get("https://localhost:" + httpsPort + "/foo/test")
                    .setProxyServer(proxyServer("localhost", proxyPort))
                    .setRealm(ntlmAuthRealm("user", "secret").setUsePreemptiveAuth(true));

            Response response = client.executeRequest(rb.build()).get();
            assertEquals(200, response.getStatusCode());
            assertNull(connectAuthorization.get(),
                    "CONNECT request must not expose the origin NTLM Authorization to the proxy");
        }
    }

    private class RecordingConnectHandler extends ConnectHandler {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            if (HttpConstants.Methods.CONNECT.equalsIgnoreCase(request.getMethod())) {
                connectAuthorization.set(request.getHeader("Authorization"));
            }
            super.handle(target, baseRequest, request, response);
        }
    }
}
