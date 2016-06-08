/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
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
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncHttpClient;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class RedirectTest extends AbstractBasicTest {

    @BeforeClass
    @Override
    public void setUpGlobal() throws Exception {

        server = new Server();
        ServerConnector connector1 = addHttpConnector(server);
        ServerConnector connector2 = addHttpConnector(server);

        HandlerList list = new HandlerList();
        list.addHandler(new AbstractHandler() {
            @Override
            public void handle(String s, Request request, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException, ServletException {
                if (request.getLocalPort() == port2) {
                    httpServletResponse.sendRedirect(getTargetUrl());
                }
            }
        });
        list.addHandler(getWebSocketHandler());
        server.setHandler(list);

        server.start();
        port1 = connector1.getLocalPort();
        port2 = connector2.getLocalPort();
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


    @Test(groups = "standalone", timeOut = 60000)
    public void testRedirectToWSResource() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true))) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = c.prepareGet(getRedirectURL()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(WebSocket websocket) {
                    text.set("OnOpen");
                    latch.countDown();
                }

                @Override
                public void onClose(WebSocket websocket) {
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            latch.await();
            assertEquals(text.get(), "OnOpen");
            websocket.close();
        }
    }

    private String getRedirectURL() {
        return String.format("ws://localhost:%d/", port2);
    }
}
