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
package org.asynchttpclient.ws;

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.asynchttpclient.AsyncHttpClient;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.testng.annotations.Test;

public class CloseCodeReasonMessageTest extends AbstractBasicTest {

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
    public void onCloseWithCode() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new Listener(latch, text)).build()).get();

            websocket.close();

            latch.await();
            assertTrue(text.get().startsWith("1000"), "Expected a 1000 code but got " + text.get());
        }
    }

    @Test(groups = "standalone", timeOut = 60000)
    public void onCloseWithCodeServerClose() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new Listener(latch, text)).build()).get();

            latch.await();
            assertEquals(text.get(), "1001-Idle Timeout");
        }
    }

    public final static class Listener implements WebSocketListener, WebSocketCloseCodeReasonListener {

        final CountDownLatch latch;
        final AtomicReference<String> text;

        public Listener(CountDownLatch latch, AtomicReference<String> text) {
            this.latch = latch;
            this.text = text;
        }

        @Override
        public void onOpen(WebSocket websocket) {
        }

        @Override
        public void onClose(WebSocket websocket) {
            latch.countDown();
        }

        public void onClose(WebSocket websocket, int code, String reason) {
            text.set(code + "-" + reason);
            latch.countDown();
        }

        @Override
        public void onError(Throwable t) {
            t.printStackTrace();
            latch.countDown();
        }
    }

    @Test(groups = "online", timeOut = 60000, expectedExceptions = ExecutionException.class)
    public void getWebSocketThrowsException() throws Throwable {
        final CountDownLatch latch = new CountDownLatch(1);
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.prepareGet("http://apache.org").execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
                }

                @Override
                public void onError(Throwable t) {
                    latch.countDown();
                }
            }).build()).get();
        }

        latch.await();
    }

    @Test(groups = "online", timeOut = 60000, expectedExceptions = IllegalArgumentException.class)
    public void wrongStatusCode() throws Throwable {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Throwable> throwable = new AtomicReference<>();

            client.prepareGet("http://apache.org").execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onOpen(org.asynchttpclient.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(org.asynchttpclient.ws.WebSocket websocket) {
                }

                @Override
                public void onError(Throwable t) {
                    throwable.set(t);
                    latch.countDown();
                }
            }).build());

            latch.await();
            assertNotNull(throwable.get());
            throw throwable.get();
        }
    }

    @Test(groups = "online", timeOut = 60000, expectedExceptions = IllegalStateException.class)
    public void wrongProtocolCode() throws Throwable {
        try (AsyncHttpClient c = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Throwable> throwable = new AtomicReference<>();

            c.prepareGet("ws://www.google.com").execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
                }

                @Override
                public void onError(Throwable t) {
                    throwable.set(t);
                    latch.countDown();
                }
            }).build());

            latch.await();
            assertNotNull(throwable.get());
            throw throwable.get();
        }
    }
}
