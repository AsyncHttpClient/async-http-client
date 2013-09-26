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
package org.asynchttpclient.websocket;

import static org.testng.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.asynchttpclient.AsyncHttpClient;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.testng.annotations.Test;

public abstract class TextMessageTest extends AbstractBasicTest {

    @Override
    public WebSocketHandler getWebSocketHandler() {
        return new WebSocketHandler() {
            @Override
            public void configure(WebSocketServletFactory factory) {
                factory.register(EchoSocket.class);
            }
        };
    }

    @Test(timeOut = 60000)
    public void onOpen() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

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
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void onEmptyListenerTest() throws Exception {
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
    public void onFailureTest() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            Throwable t = null;
            try {
                /* WebSocket websocket = */c.prepareGet("ws://abcdefg").execute(new WebSocketUpgradeHandler.Builder().build()).get();
            } catch (Throwable t2) {
                t = t2;
            }
            assertTrue(t != null);
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void onTimeoutCloseTest() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
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
    public void onClose() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
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
    public void echoText() throws Exception {
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

            websocket.sendTextMessage("ECHO");

            latch.await();
            assertEquals(text.get(), "ECHO");
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void echoDoubleListenerText() throws Exception {
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

            websocket.sendTextMessage("ECHO");

            latch.await();
            assertEquals(text.get(), "ECHOECHO");
        } finally {
            c.close();
        }
    }

    @Test
    public void echoTwoMessagesTest() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<String> text = new AtomicReference<String>("");

            /* WebSocket websocket = */c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    text.set(text.get() + message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                }

                @Override
                public void onOpen(WebSocket websocket) {
                    websocket.sendTextMessage("ECHO").sendTextMessage("ECHO");
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

            latch.await();
            assertEquals(text.get(), "ECHOECHO");
        } finally {
            c.close();
        }
    }

    public void echoFragments() throws Exception {
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

            websocket.streamText("ECHO", false);
            websocket.streamText("ECHO", true);

            latch.await();
            assertEquals(text.get(), "ECHOECHO");
        } finally {
            c.close();
        }
    }
}
