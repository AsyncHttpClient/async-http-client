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
package com.ning.http.client.websocket;

import com.ning.http.client.AsyncHttpClient;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

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
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                    text.set("OnOpen");
                    latch.countDown();
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            latch.await();
            assertEquals(text.get(), "OnOpen");
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void onEmptyListenerTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            WebSocket websocket = null;
            try {
                websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().build()).get();
            } catch (Throwable t) {
                fail();
            }
            assertTrue(websocket != null);
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void onFailureTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            Throwable t = null;
            try {
                c.prepareGet("ws://abcdefg").execute(new WebSocketUpgradeHandler.Builder().build()).get();
            } catch (Throwable t2) {
                t = t2;
            }
            assertTrue(t != null);
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void onTimeoutCloseTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
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
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void onClose() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
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
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void echoText() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                }

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            websocket.sendTextMessage("ECHO");

            latch.await();
            assertEquals(text.get(), "ECHO");
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void echoDoubleListenerText() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<String> text = new AtomicReference<String>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                }

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
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
                public void onFragment(String fragment, boolean last) {
                }

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            websocket.sendTextMessage("ECHO");

            latch.await();
            assertEquals(text.get(), "ECHOECHO");
        } finally {
            c.close();
        }
    }

    @Test
    public void echoTwoMessagesTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<String> text = new AtomicReference<String>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(text.get() + message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                }

                boolean t = false;

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                    websocket.sendTextMessage("ECHO").sendTextMessage("ECHO");
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
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
        } finally {
            c.close();
        }
    }

    public void echoFragments() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                }

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                }
            }).build()).get();

            websocket.streamText("ECHO", false);
            websocket.streamText("ECHO", true);

            latch.await();
            assertEquals(text.get(), "ECHOECHO");
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void wrongStatusCode() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Throwable> throwable = new AtomicReference<Throwable>();

            WebSocket websocket = c.prepareGet("http://apache.org").execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                }

                @Override
                public void onOpen(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.websocket.WebSocket websocket) {
                }

                @Override
                public void onError(Throwable t) {
                    throwable.set(t);
                    latch.countDown();
                }
            }).build()).get();

            latch.await();
            assertNotNull(throwable.get());
            assertEquals(throwable.get().getClass(), IllegalStateException.class);
        } finally {
            c.close();
        }
    }
}
