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
package com.ning.http.client.ws;

import static org.testng.Assert.assertEquals;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.ws.WebSocket;
import com.ning.http.client.ws.WebSocketTextListener;
import com.ning.http.client.ws.WebSocketUpgradeHandler;
import com.ning.http.client.ws.TextMessageTest.EchoTextWebSocket;

import javax.servlet.http.HttpServletRequest;

import java.io.File;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proxy usage tests.
 */
public abstract class ProxyTunnellingTest extends AbstractBasicTest {

    int port2;
    private Server server2;

    public void setUpGlobal() throws Exception {
    }

    private void setUpServers(Connector server2Connector) throws Exception {

        port1 = findFreePort();
        port2 = findFreePort();
        Connector listener = new SelectChannelConnector();
        listener.setHost("127.0.0.1");
        listener.setPort(port1);
        addConnector(listener);
        setHandler(new ConnectHandler());
        start();

        server2 = new Server();

        server2Connector.setHost("127.0.0.1");
        server2Connector.setPort(port2);

        server2.addConnector(server2Connector);

        server2.setHandler(getWebSocketHandler());
        server2.start();
        log.info("Local HTTP server started successfully");

    }

    private void setUpServer() throws Exception {
        setUpServers(new SelectChannelConnector());
    }

    private void setUpSSlServer2() throws Exception {
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        ClassLoader cl = getClass().getClassLoader();
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        String keyStoreFile = new File(keystoreUrl.toURI()).getAbsolutePath();
        connector.setKeystore(keyStoreFile);
        connector.setKeyPassword("changeit");
        connector.setKeystoreType("JKS");
        setUpServers(connector);
    }

    @Override
    public WebSocketHandler getWebSocketHandler() {
        return new WebSocketHandler() {
            @Override
            public org.eclipse.jetty.websocket.WebSocket doWebSocketConnect(HttpServletRequest httpServletRequest, String s) {
                return new EchoTextWebSocket();
            }
        };
    }
    
    @AfterMethod(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        stop();
        if (server2 != null) {
            server2.stop();
        }
        server2 = null;
    }

    @Test(timeOut = 60000)
    public void echoWSText() throws Exception {
        setUpServer();
        runTest("ws");
    }

    @Test(timeOut = 60000)
    public void echoWSSText() throws Exception {
        setUpSSlServer2();
        runTest("wss");
    }

    private void runTest(String protocol) throws Exception {
        String targetUrl = String.format("%s://127.0.0.1:%d/", protocol, port2);

        ProxyServer ps = new ProxyServer(ProxyServer.Protocol.HTTP, "127.0.0.1", port1);
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().setProxyServer(ps).setAcceptAnyCertificate(true).build();
        try (AsyncHttpClient asyncHttpClient = getAsyncHttpClient(config)) {
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
