/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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

public abstract class TextMessageTest extends AbstractBasicTest {

    private final class EchoTextWebSocket implements org.eclipse.jetty.websocket.WebSocket, org.eclipse.jetty.websocket.WebSocket.OnTextMessage {

        private Connection connection;

        @Override
        public void onOpen(Connection connection) {
            this.connection = connection;
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

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Test(timeOut = 60000)
    public void onOpen() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<String>("");

        WebSocket websocket = c.prepareGet(getTargetUrl())
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

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
    }

    @Test(timeOut = 60000)
    public void onClose() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<String>("");

        WebSocket websocket = c.prepareGet(getTargetUrl())
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

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
    }

    @Test(timeOut = 60000)
    public void echoText() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<String>("");

        WebSocket websocket = c.prepareGet(getTargetUrl())
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                    @Override
                    public void onMessage(String message) {
                        text.set(message);
                        latch.countDown();
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
    }

    @Test(timeOut = 60000)
    public void echoDoubleListenerText() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicReference<String> text = new AtomicReference<String>("");

        WebSocket websocket = c.prepareGet(getTargetUrl())
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                    @Override
                    public void onMessage(String message) {
                        text.set(message);
                        latch.countDown();
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
    }

    @Test
    public void echoTwoMessagesTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicReference<String> text = new AtomicReference<String>("");

        WebSocket websocket = c.prepareGet(getTargetUrl())
                .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                    @Override
                    public void onMessage(String message) {
                        text.set(text.get() + message);
                        latch.countDown();
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
    }
}
