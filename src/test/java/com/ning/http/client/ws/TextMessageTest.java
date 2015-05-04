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
package com.ning.http.client.ws;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;

import javax.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public abstract class TextMessageTest extends AbstractBasicTest {

    public static final class EchoTextWebSocket implements org.eclipse.jetty.websocket.WebSocket, org.eclipse.jetty.websocket.WebSocket.OnTextMessage {

        private Connection connection;

        @Override
        public void onOpen(Connection connection) {
            this.connection = connection;
            connection.setMaxTextMessageSize(1000);
        }

        @Override
        public void onClose(int i, String s) {
            connection.close();
        }

        @Override
        public void onMessage(String s) {
            try {
                if (s.equals("CLOSE"))
                    connection.close();
                else
                    connection.sendMessage(s);
            } catch (IOException e) {
                try {
                    connection.sendMessage("FAIL");
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
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

    @Test(timeOut = 60000)
    public void onOpen() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                    text.set("OnOpen");
                    latch.countDown();
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            latch.await();
            assertEquals(text.get(), "OnOpen");
        }
    }

    @Test(timeOut = 60000)
    public void onEmptyListenerTest() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            WebSocket websocket = null;
            try {
                websocket = client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().build()).get();
            } catch (Throwable t) {
                fail();
            }
            assertTrue(websocket != null);
        }
    }

    @Test(timeOut = 60000, expectedExceptions = { ConnectException.class, UnresolvedAddressException.class, UnknownHostException.class })
    public void onFailureTest() throws Throwable {
        try (AsyncHttpClient c = getAsyncHttpClient(null)) {
            c.prepareGet("ws://abcdefg").execute(new WebSocketUpgradeHandler.Builder().build()).get();
        } catch (ExecutionException e) {
            if (e.getCause() != null)
                throw e.getCause();
            else
                throw e;
        }
    }

    @Test(timeOut = 60000)
    public void onTimeoutCloseTest() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
                    text.set("OnClose");
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            latch.await();
            assertEquals(text.get(), "OnClose");
        }
    }

    @Test(timeOut = 60000)
    public void onClose() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
                    text.set("OnClose");
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            websocket.close();

            latch.await();
            assertEquals(text.get(), "OnClose");
        }
    }

    @Test(timeOut = 60000)
    public void echoText() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(message);
                    latch.countDown();
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
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

    @Test(timeOut = 60000)
    public void echoDoubleListenerText() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(message);
                    latch.countDown();
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(text.get() + message);
                    latch.countDown();
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
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
            assertEquals(text.get(), "ECHOECHO");
        }
    }

    @Test
    public void echoTwoMessagesTest() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(text.get() + message);
                    latch.countDown();
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                    websocket.sendMessage("ECHO").sendMessage("ECHO");
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            latch.await();
            assertEquals(text.get(), "ECHOECHO");
        }
    }

    public void echoFragments() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(message);
                    latch.countDown();
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            websocket.stream("ECHO", false);
            websocket.stream("ECHO", true);

            latch.await();
            assertEquals(text.get(), "ECHOECHO");
        }
    }

    @Test(timeOut = 60000)
    public void echoTextAndThenClose() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch textLatch = new CountDownLatch(1);
            final CountDownLatch closeLatch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            final WebSocket websocket = client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(text.get() + message);
                    textLatch.countDown();
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
                    closeLatch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    closeLatch.countDown();
                }
            }).build()).get();

            websocket.sendMessage("ECHO");
            textLatch.await();

            websocket.sendMessage("CLOSE");
            closeLatch.await();

            assertEquals(text.get(), "ECHO");
        }
    }
}
