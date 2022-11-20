/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.ws;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.proxy.ProxyServer;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proxy usage tests.
 */
public class ProxyTunnellingTest extends AbstractBasicWebSocketTest {

    private static Server server2;

    private void setUpServers(boolean targetHttps) throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new ConnectHandler());
        server.start();
        port1 = connector.getLocalPort();

        server2 = new Server();
        @SuppressWarnings("resource")
        ServerConnector connector2 = targetHttps ? addHttpsConnector(server2) : addHttpConnector(server2);
        server2.setHandler(configureHandler());
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

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void echoWSText() throws Exception {
        runTest(false);
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void echoWSSText() throws Exception {
        runTest(true);
    }

    private void runTest(boolean secure) throws Exception {
        setUpServers(secure);

        String targetUrl = String.format("%s://localhost:%d/", secure ? "wss" : "ws", port2);

        // CONNECT happens over HTTP, not HTTPS
        ProxyServer ps = proxyServer("localhost", port1).build();
        try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setProxyServer(ps).setUseInsecureTrustManager(true))) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = asyncHttpClient.prepareGet(targetUrl).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onTextFrame(String payload, boolean finalFragment, int rsv) {
                    text.set(payload);
                    latch.countDown();
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket, int code, String reason) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            websocket.sendTextFrame("ECHO");

            latch.await();
            assertEquals("ECHO", text.get());
        }
    }
}
