/*
 *    Copyright (c) 2017-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.ws;

import org.asynchttpclient.AsyncHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code *ExpectFailure} tests wait for {@code onClose}, which fires just before the channel closes. That
 * is enough on {@code ws://}: the send is queued behind the close on the same event loop. Not verified for TLS.
 */
public class WebSocketWriteFutureTest extends AbstractBasicWebSocketTest {

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void sendTextMessage() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendTextFrame("TEXT").get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void sendTextMessageExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            WebSocket websocket = getWebSocket(c, closeLatch);
            websocket.sendCloseFrame();
            assertTrue(closeLatch.await(TIMEOUT, TimeUnit.SECONDS), "the close handshake never completed");
            assertClosedChannel(assertThrows(ExecutionException.class,
                    () -> websocket.sendTextFrame("TEXT").get(TIMEOUT, TimeUnit.SECONDS)));
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void sendByteMessage() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendBinaryFrame("BYTES".getBytes()).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void sendByteMessageExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            WebSocket websocket = getWebSocket(c, closeLatch);
            websocket.sendCloseFrame();
            assertTrue(closeLatch.await(TIMEOUT, TimeUnit.SECONDS), "the close handshake never completed");
            assertClosedChannel(assertThrows(ExecutionException.class,
                    () -> websocket.sendBinaryFrame("BYTES".getBytes()).get(TIMEOUT, TimeUnit.SECONDS)));
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void sendPingMessage() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendPingFrame("PING".getBytes()).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void sendPingMessageExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            WebSocket websocket = getWebSocket(c, closeLatch);
            websocket.sendCloseFrame();
            assertTrue(closeLatch.await(TIMEOUT, TimeUnit.SECONDS), "the close handshake never completed");
            assertClosedChannel(assertThrows(ExecutionException.class,
                    () -> websocket.sendPingFrame("PING".getBytes()).get(TIMEOUT, TimeUnit.SECONDS)));
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void sendPongMessage() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendPongFrame("PONG".getBytes()).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void sendPongMessageExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            WebSocket websocket = getWebSocket(c, closeLatch);
            websocket.sendCloseFrame();
            assertTrue(closeLatch.await(TIMEOUT, TimeUnit.SECONDS), "the close handshake never completed");
            assertClosedChannel(assertThrows(ExecutionException.class,
                    () -> websocket.sendPongFrame("PONG".getBytes()).get(TIMEOUT, TimeUnit.SECONDS)));
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void streamBytes() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendBinaryFrame("STREAM".getBytes(), true, 0).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void streamBytesExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            WebSocket websocket = getWebSocket(c, closeLatch);
            websocket.sendCloseFrame();
            assertTrue(closeLatch.await(TIMEOUT, TimeUnit.SECONDS), "the close handshake never completed");
            assertClosedChannel(assertThrows(ExecutionException.class,
                    () -> websocket.sendBinaryFrame("STREAM".getBytes(), true, 0).get(TIMEOUT, TimeUnit.SECONDS)));
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void streamText() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            getWebSocket(c).sendTextFrame("STREAM", true, 0).get(1, TimeUnit.SECONDS);
        }
    }


    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void streamTextExpectFailure() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient()) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            WebSocket websocket = getWebSocket(c, closeLatch);
            websocket.sendCloseFrame();
            assertTrue(closeLatch.await(TIMEOUT, TimeUnit.SECONDS), "the close handshake never completed");
            assertClosedChannel(assertThrows(ExecutionException.class,
                    () -> websocket.sendTextFrame("STREAM", true, 0).get(TIMEOUT, TimeUnit.SECONDS)));
        }
    }

    private static void assertClosedChannel(ExecutionException e) {
        assertInstanceOf(ClosedChannelException.class, e.getCause());
    }

    private WebSocket getWebSocket(final AsyncHttpClient c) throws Exception {
        return c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().build()).get();
    }

    private WebSocket getWebSocket(final AsyncHttpClient c, CountDownLatch closeLatch) throws Exception {
        return c.prepareGet(getTargetUrl()).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketListener() {

            @Override
            public void onOpen(WebSocket websocket) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onClose(WebSocket websocket, int code, String reason) {
                closeLatch.countDown();
            }
        }).build()).get();
    }
}
