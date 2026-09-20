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
package org.asynchttpclient.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtension;
import io.netty.util.HashedWheelTimer;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame and buffer limits only bound compressed bytes, so a {@code permessage-deflate} message needs its own
 * ceiling. These drive the handler {@link ChannelManager} builds, so they fail if it reverts to Netty's unbounded one.
 */
class WebSocketDecompressionLimitTest {

    private static final int INFLATED_SIZE = 8 * 1024 * 1024;
    private static final int LIMIT_ABOVE_MESSAGE = 16 * 1024 * 1024;
    private static final int LIMIT_BELOW_MESSAGE = 1024 * 1024;

    private static Timer timer;

    @BeforeAll
    static void startTimer() {
        timer = new HashedWheelTimer();
    }

    @AfterAll
    static void stopTimer() {
        timer.stop();
    }

    @Test
    void aMessageThatInflatesPastTheLimitFailsTheConnection() {
        EmbeddedChannel channel = negotiatedChannel(LIMIT_BELOW_MESSAGE);
        try {
            BinaryWebSocketFrame bomb = compressedBomb();
            assertThrows(DecompressionException.class, () -> channel.writeInbound(bomb),
                    "a message inflating past webSocketMaxDecompressedFrameSize must fail the connection");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aMessageWithinTheLimitStillInflates() {
        EmbeddedChannel channel = negotiatedChannel(LIMIT_ABOVE_MESSAGE);
        try {
            assertTrue(channel.writeInbound(compressedBomb()));
            WebSocketFrame inflated = channel.readInbound();
            assertNotNull(inflated, "a message below the limit must still be delivered");
            try {
                assertEquals(INFLATED_SIZE, inflated.content().readableBytes());
            } finally {
                ReferenceCountUtil.release(inflated);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void byDefaultAMessageIsBoundedAlikeCompressedOrNot() {
        AsyncHttpClientConfig defaults = new DefaultAsyncHttpClientConfig.Builder().build();
        assertEquals(defaults.getWebSocketMaxBufferSize(), defaults.getWebSocketMaxDecompressedFrameSize());
    }

    /** A custom config that predates the setting inherits the buffer size, not a fixed number. */
    @Test
    void theInterfaceDefaultFollowsTheBufferSize() {
        // Not a Mockito spy: the byte-buddy that Mockito 4 bundles cannot call a real default method on JDK 21+.
        AsyncHttpClientConfig custom = (AsyncHttpClientConfig) Proxy.newProxyInstance(AsyncHttpClientConfig.class.getClassLoader(),
                new Class<?>[]{AsyncHttpClientConfig.class}, (proxy, method, args) -> {
                    if (method.isDefault()) {
                        return MethodHandles.privateLookupIn(AsyncHttpClientConfig.class, MethodHandles.lookup())
                                .unreflectSpecial(method, AsyncHttpClientConfig.class)
                                .bindTo(proxy)
                                .invokeWithArguments(args == null ? new Object[0] : args);
                    }
                    if ("getWebSocketMaxBufferSize".equals(method.getName())) {
                        return 4096;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        assertEquals(4096, custom.getWebSocketMaxDecompressedFrameSize());
    }

    /** The handler the ChannelManager built, taken through extension negotiation so its inflater is installed. */
    private static EmbeddedChannel negotiatedChannel(int maxDecompressedFrameSize) {
        AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setEnablewebSocketCompression(true)
                .setWebSocketMaxDecompressedFrameSize(maxDecompressedFrameSize)
                .build();
        ChannelManager channelManager = new ChannelManager(config, timer);
        try {
            EmbeddedChannel channel = new EmbeddedChannel(compressionHandlerOf(channelManager));

            channel.writeOutbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
            ReferenceCountUtil.release(channel.readOutbound());

            FullHttpResponse handshake = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
            handshake.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            handshake.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            handshake.headers().set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
            channel.writeInbound(handshake);
            ReferenceCountUtil.release(channel.readInbound());

            return channel;
        } finally {
            channelManager.close();
        }
    }

    private static ChannelHandler compressionHandlerOf(ChannelManager channelManager) {
        try {
            Field field = ChannelManager.class.getDeclaredField("webSocketCompressionHandler");
            field.setAccessible(true);
            ChannelHandler handler = (ChannelHandler) field.get(channelManager);
            assertNotNull(handler, "the ChannelManager must build a compression handler");
            return handler;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the compression handler the ChannelManager installs must be reachable", e);
        }
    }

    /** Deflated with SYNC_FLUSH minus the trailing {@code 00 00 FF FF}, as a permessage-deflate sender does. */
    private static BinaryWebSocketFrame compressedBomb() {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(new byte[INFLATED_SIZE]);
            byte[] compressed = new byte[64 * 1024];
            int written = 0;
            while (written < compressed.length) {
                int count = deflater.deflate(compressed, written, compressed.length - written, Deflater.SYNC_FLUSH);
                if (count == 0) {
                    break;
                }
                written += count;
            }
            ByteBuf payload = Unpooled.wrappedBuffer(compressed, 0, written - 4);
            return new BinaryWebSocketFrame(true, WebSocketExtension.RSV1, payload);
        } finally {
            deflater.end();
        }
    }
}
