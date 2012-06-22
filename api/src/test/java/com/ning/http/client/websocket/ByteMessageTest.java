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
import com.ning.http.client.AsyncHttpClientConfig;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;

public abstract class ByteMessageTest extends AbstractBasicTest {

    private final class EchoByteWebSocket implements org.eclipse.jetty.websocket.WebSocket, org.eclipse.jetty.websocket.WebSocket.OnBinaryMessage {

        private Connection connection;

        @Override
        public void onOpen(Connection connection) {
            this.connection = connection;
            connection.setMaxBinaryMessageSize(1000);
        }

        @Override
        public void onClose(int i, String s) {
            connection.close();
        }

        @Override
        public void onMessage(byte[] bytes, int i, int i1) {
            try {
                connection.sendMessage(bytes, i, i1);
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
                return new EchoByteWebSocket();
            }
        };
    }

    @Test
    public void echoByte() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> text = new AtomicReference<byte[]>(new byte[0]);

        WebSocket websocket = c.prepareGet(getTargetUrl())
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketByteListener() {

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

                    @Override
                    public void onFragment(byte[] fragment, boolean last) {
                    }
                }).build()).get();

        websocket.sendMessage("ECHO".getBytes());

        latch.await();
        assertEquals(text.get(), "ECHO".getBytes());
    }

    @Test
    public void echoTwoMessagesTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicReference<byte[]> text = new AtomicReference<byte[]>(null);

        WebSocket websocket = c.prepareGet(getTargetUrl())
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketByteListener() {

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

                    @Override
                    public void onFragment(byte[] fragment, boolean last) {
                    }
                }).build()).get();

        websocket.sendMessage("ECHO".getBytes()).sendMessage("ECHO".getBytes());

        latch.await();
        assertEquals(text.get(), "ECHOECHO".getBytes());
    }

    @Test
    public void echoOnOpenMessagesTest() throws Throwable {
            AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
            final CountDownLatch latch = new CountDownLatch(2);
            final AtomicReference<byte[]> text = new AtomicReference<byte[]>(null);

            /*WebSocket websocket =*/ c.prepareGet(getTargetUrl())
                    .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketByteListener() {

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

                        @Override
                        public void onFragment(byte[] fragment, boolean last) {
                        }
                    }).build()).get();

            latch.await();
            assertEquals(text.get(), "ECHOECHO".getBytes());
    }


    public void echoFragments() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<byte[]> text = new AtomicReference<byte[]>(null);

                WebSocket websocket = c.prepareGet(getTargetUrl())
                        .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketByteListener() {

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

                            @Override
                            public void onFragment(byte[] fragment, boolean last) {
                            }
                        }).build()).get();
                websocket.stream("ECHO".getBytes(), false);
                websocket.stream("ECHO".getBytes(), true);
                latch.await();
                assertEquals(text.get(), "ECHOECHO".getBytes());
    }
}
