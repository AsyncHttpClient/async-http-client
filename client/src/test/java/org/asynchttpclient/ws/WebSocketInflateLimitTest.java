/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * permessage-deflate end to end: Jetty compresses, so the inflate bound is exercised through the real pipeline order.
 */
public class WebSocketInflateLimitTest extends AbstractBasicWebSocketTest {

    private static final int MESSAGE = 8 * 1024 * 1024;
    private static final int FRAGMENTS = 16;

    private static volatile CompletableFuture<Integer> serverCloseCode = new CompletableFuture<>();

    public static class WholeSender extends WebSocketAdapter {
        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            try {
                getRemote().sendBytes(ByteBuffer.allocate(MESSAGE));
            } catch (IOException e) {
                serverCloseCode.completeExceptionally(e);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            serverCloseCode.complete(statusCode);
        }
    }

    public static class FragmentSender extends WebSocketAdapter {
        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            try {
                for (int i = 0; i < FRAGMENTS; i++) {
                    getRemote().sendPartialBytes(ByteBuffer.allocate(MESSAGE / FRAGMENTS), i == FRAGMENTS - 1);
                }
            } catch (IOException e) {
                serverCloseCode.completeExceptionally(e);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            serverCloseCode.complete(statusCode);
        }
    }

    @Override
    public AbstractHandler configureHandler() {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.addMapping("/whole", WholeSender.class);
            wsContainer.addMapping("/fragments", FragmentSender.class);
        });
        return context;
    }

    private static final class Result {
        final int closeCode;
        final long delivered;

        Result(int closeCode, long delivered) {
            this.closeCode = closeCode;
            this.delivered = delivered;
        }
    }

    private Result run(String path, int inflateLimit) throws Exception {
        serverCloseCode = new CompletableFuture<>();
        AtomicLong delivered = new AtomicLong();
        DefaultAsyncHttpClientConfig.Builder builder = config()
                .setEnablewebSocketCompression(true)
                .setWebSocketMaxFrameSize(1024 * 1024)
                .setWebSocketMaxDecompressedFrameSize(inflateLimit);
        try (AsyncHttpClient client = asyncHttpClient(builder)) {
            client.prepareGet("ws://localhost:" + port1 + path).execute(new WebSocketUpgradeHandler.Builder()
                    .addWebSocketListener(new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket websocket) {
                        }

                        @Override
                        public void onClose(WebSocket websocket, int code, String reason) {
                        }

                        @Override
                        public void onError(Throwable t) {
                        }

                        @Override
                        public void onBinaryFrame(byte[] payload, boolean finalFragment, int rsv) {
                            delivered.addAndGet(payload.length);
                            if (delivered.get() >= MESSAGE) {
                                serverCloseCode.complete(-1);
                            }
                        }
                    }).build()).get();
            int code = serverCloseCode.get(20, TimeUnit.SECONDS);
            return new Result(code, delivered.get());
        }
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void aMessageUnderTheLimitIsDelivered() throws Exception {
        Result r = run("/whole", 2 * MESSAGE);
        assertEquals(MESSAGE, r.delivered);
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void aWholeMessageOverTheInflateLimitClosesWith1009() throws Exception {
        Result r = run("/whole", 1024 * 1024);
        assertEquals(0, r.delivered, "nothing past the limit may be delivered");
        assertEquals(1009, r.closeCode, "RFC 6455 section 7.4.1: a message too big to process closes with 1009");
    }

    @Test
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 60000)
    public void aFragmentedMessageIsBoundedAsAWhole() throws Exception {
        Result r = run("/fragments", 1024 * 1024);
        assertEquals(0, r.delivered, "fragments each under the limit must not add up past it once aggregated");
    }
}
