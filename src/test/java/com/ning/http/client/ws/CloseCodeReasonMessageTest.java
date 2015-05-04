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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public abstract class CloseCodeReasonMessageTest extends TextMessageTest {

    @Test(timeOut = 60000)
    public void onCloseWithCode() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            WebSocket websocket = client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new Listener(latch, text)).build()).get();

            websocket.close();

            latch.await();
            assertTrue(text.get().startsWith("1000"));
        }
    }

    @Test(timeOut = 60000)
    public void onCloseWithCodeServerClose() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<>("");

            client.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new Listener(latch, text)).build()).get();

            latch.await();
            final String[] parts = text.get().split(" ");
            assertEquals(parts.length, 5);
            assertEquals(parts[0], "1000-Idle");
            assertEquals(parts[1], "for");
            assertTrue(Integer.parseInt(parts[2].substring(0, parts[2].indexOf('m'))) > 10000);
            assertEquals(parts[3], ">");
            assertEquals(parts[4], "10000ms");
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
        public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
        }

        @Override
        public void onClose(com.ning.http.client.ws.WebSocket websocket) {
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

    @Test(timeOut = 60000, expectedExceptions = { ExecutionException.class })
    public void getWebSocketThrowsException() throws Throwable {
        final CountDownLatch latch = new CountDownLatch(1);
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            client.prepareGet("http://apache.org").execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onError(Throwable t) {
                    latch.countDown();
                }
            }).build()).get();
        }
        
        latch.await();
    }

    // Netty would throw IllegalArgumentException, other providers IllegalStateException
    @Test(timeOut = 60000, expectedExceptions = { IllegalStateException.class, IllegalArgumentException.class } )
    public void wrongStatusCode() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Throwable> throwable = new AtomicReference<>();

            client.prepareGet("http://apache.org").execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
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

    // Netty would throw IllegalArgumentException, other providers IllegalStateException
    @Test(timeOut = 60000, expectedExceptions = { IllegalStateException.class, IllegalArgumentException.class } )
    public void wrongProtocolCode() throws Throwable {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Throwable> throwable = new AtomicReference<>();

            client.prepareGet("ws://www.google.com/").execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onOpen(com.ning.http.client.ws.WebSocket websocket) {
                }

                @Override
                public void onClose(com.ning.http.client.ws.WebSocket websocket) {
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
