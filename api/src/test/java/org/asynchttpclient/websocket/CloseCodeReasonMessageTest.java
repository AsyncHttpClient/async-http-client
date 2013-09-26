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

public abstract class CloseCodeReasonMessageTest extends AbstractBasicTest {

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
    public void onCloseWithCode() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            WebSocket websocket = c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new Listener(latch, text)).build()).get();

            websocket.close();

            latch.await();
            assertTrue(text.get().startsWith("1000"));
        } finally {
            c.close();
        }
    }

    @Test(timeOut = 60000)
    public void onCloseWithCodeServerClose() throws Exception {
        AsyncHttpClient c = getAsyncHttpClient(null);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> text = new AtomicReference<String>("");

            c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new Listener(latch, text)).build()).get();

            latch.await();
            assertEquals(text.get(), "1001-Idle Timeout");
        } finally {
            c.close();
        }
    }

    public final static class Listener implements WebSocketListener,
            WebSocketCloseCodeReasonListener {

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
}
