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
import static org.testng.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.asynchttpclient.AsyncHttpClient;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.testng.annotations.Test;

public class ByteMessageTest extends AbstractBasicTest {

    @Override
    public WebSocketHandler getWebSocketHandler() {
        return new WebSocketHandler() {
            @Override
            public void configure(WebSocketServletFactory factory) {
                factory.register(EchoSocket.class);
            }
        };
    }

    @Test(groups = "standalone")
    public void echoByte() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<byte[]> text = new AtomicReference<>(new byte[0]);

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketByteListener() {

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

                @Override
                public void onMessage(byte[] message) {
                    text.set(message);
                    latch.countDown();
                }

            }).build()).get();

            websocket.sendMessage("ECHO".getBytes());

            latch.await();
            assertEquals(text.get(), "ECHO".getBytes());
        }
    }

    @Test(groups = "standalone")
    public void echoTwoMessagesTest() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<byte[]> text = new AtomicReference<>(null);

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketByteListener() {

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

                @Override
                public void onMessage(byte[] message) {
                    if (text.get() == null) {
                        text.set(message);
                    } else {
                        byte[] n = new byte[text.get().length + message.length];
                        System.arraycopy(text.get(), 0, n, 0, text.get().length);
                        System.arraycopy(message, 0, n, text.get().length, message.length);
                        text.set(n);
                    }
                    latch.countDown();
                }

            }).build()).get();

            websocket.sendMessage("ECHO".getBytes()).sendMessage("ECHO".getBytes());

            latch.await();
            assertEquals(text.get(), "ECHOECHO".getBytes());
        }
    }

    @Test
    public void echoOnOpenMessagesTest() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<byte[]> text = new AtomicReference<>(null);

            /* WebSocket websocket = */c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketByteListener() {

                @Override
                public void onOpen(WebSocket websocket) {
                    websocket.sendMessage("ECHO".getBytes()).sendMessage("ECHO".getBytes());
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

                @Override
                public void onMessage(byte[] message) {
                    if (text.get() == null) {
                        text.set(message);
                    } else {
                        byte[] n = new byte[text.get().length + message.length];
                        System.arraycopy(text.get(), 0, n, 0, text.get().length);
                        System.arraycopy(message, 0, n, text.get().length, message.length);
                        text.set(n);
                    }
                    latch.countDown();
                }

            }).build()).get();

            latch.await();
            assertEquals(text.get(), "ECHOECHO".getBytes());
        }
    }

    public void echoFragments() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<byte[]> text = new AtomicReference<>(null);

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketByteListener() {

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

                @Override
                public void onMessage(byte[] message) {
                    if (text.get() == null) {
                        text.set(message);
                    } else {
                        byte[] n = new byte[text.get().length + message.length];
                        System.arraycopy(text.get(), 0, n, 0, text.get().length);
                        System.arraycopy(message, 0, n, text.get().length, message.length);
                        text.set(n);
                    }
                    latch.countDown();
                }

            }).build()).get();
            websocket.stream("ECHO".getBytes(), false);
            websocket.stream("ECHO".getBytes(), true);
            latch.await();
            assertEquals(text.get(), "ECHOECHO".getBytes());
        }
    }
}
