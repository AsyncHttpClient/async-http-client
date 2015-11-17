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

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.proxy.ProxyServer;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Proxy usage tests.
 */
public class ProxyTunnellingTest extends AbstractBasicTest {

    private Server server2;

    public void setUpServers(boolean targetHttps) throws Exception {
        port1 = findFreePort();
        server = newJettyHttpServer(port1);
        server.setHandler(new ConnectHandler());
        server.start();

        port2 = findFreePort();

        server2 = targetHttps ? newJettyHttpsServer(port2) : newJettyHttpServer(port2);
        server2.setHandler(getWebSocketHandler());
        server2.start();

        logger.info("Local HTTP server started successfully");
    }

    @Override
    public WebSocketHandler getWebSocketHandler() {
        return new WebSocketHandler() {
            @Override
            public void configure(WebSocketServletFactory factory) {
                factory.register(EchoSocket.class);
            }
        };
    }

    @AfterMethod(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        server.stop();
        server2.stop();
    }

    @Test(groups = "standalone", timeOut = 60000)
    public void echoWSText() throws Exception {
        runTest(false);
    }

    @Test(groups = "standalone", timeOut = 60000)
    public void echoWSSText() throws Exception {
        runTest(true);
    }

    private void runTest(boolean secure) throws Exception {

        setUpServers(secure);

        String targetUrl = String.format("%s://127.0.0.1:%d/", secure ? "wss" : "ws", port2);

        // CONNECT happens over HTTP, not HTTPS
        ProxyServer ps = proxyServer("127.0.0.1", port1).build();
        try (AsyncHttpClient asyncHttpClient = asyncHttpClient(config().setProxyServer(ps).setAcceptAnyCertificate(true))) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = asyncHttpClient.prepareGet(targetUrl).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(message);
                    latch.countDown();
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            websocket.sendMessage("ECHO");

            latch.await();
            assertEquals(text.get(), "ECHO");
        }
    }
}
