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
package org.asynchttpclient;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(NettyLeakDetectorExtension.class)
public class Http2ResponseBodyControlTest {

    private static final AttributeKey<Integer> CONNECTION_ID =
            AttributeKey.valueOf("response-body-control-h2-connection-id");
    private static final int FRAME_SIZE = 16 * 1024;
    private static final int FRAME_COUNT = 16;
    private static final int SIBLING_FRAME_COUNT = 64;
    private static final int CANCELLATION_ATTEMPTS = 8;

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final CountDownLatch largeResponseQueued = new CountDownLatch(1);
    private final CompletableFuture<Void> largeResponseWritten = new CompletableFuture<>();
    private final CountDownLatch cancelledStreamClosed = new CountDownLatch(1);
    private final LinkedBlockingQueue<Boolean> cancelledLargeStreamClosed = new LinkedBlockingQueue<>();

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private ChannelGroup serverChildChannels;
    private SslContext serverSslContext;
    private int serverPort;

    @BeforeEach
    public void prepareServer() throws Exception {
        X509Bundle bundle = new CertificateBuilder()
                .subject("CN=localhost")
                .setIsCertificateAuthority(true)
                .buildSelfSigned();
        serverSslContext = SslContextBuilder.forServer(bundle.toKeyManagerFactory())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();

        serverGroup = new NioEventLoopGroup(1);
        serverChildChannels = new DefaultChannelGroup("response-body-control-http2", GlobalEventExecutor.INSTANCE);
        serverChannel = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        serverChildChannels.add(channel);
                        channel.attr(CONNECTION_ID).set(connectionCount.incrementAndGet());
                        channel.pipeline()
                                .addLast(serverSslContext.newHandler(channel.alloc()))
                                .addLast(Http2FrameCodecBuilder.forServer().build())
                                .addLast(new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
                                    @Override
                                    protected void initChannel(Http2StreamChannel streamChannel) {
                                        serverChildChannels.add(streamChannel);
                                        streamChannel.pipeline().addLast(new StreamingServerHandler());
                                    }
                                }));
                    }
                })
                .bind(0)
                .sync()
                .channel();
        serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    public void stopServer() throws InterruptedException {
        if (serverChildChannels != null) {
            serverChildChannels.close().sync();
        }
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully(0, 100, MILLISECONDS).sync();
        }
        ReferenceCountUtil.release(serverSslContext);
    }

    @Test
    public void suspensionAppliesHttp2FlowControlAndParentIsReused() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            RecordingHandler handler = new RecordingHandler();
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/large")).execute(handler);
            ResponseBodyControl control = handler.control.get(5, SECONDS);

            assertTrue(largeResponseQueued.await(5, SECONDS));
            assertThrows(TimeoutException.class, () -> largeResponseWritten.get(250, MILLISECONDS),
                    "suspension must eventually exhaust the HTTP/2 receive window");
            assertTrue(handler.bodyBytes.get() < (long) FRAME_SIZE * FRAME_COUNT,
                    "the full response must not be delivered while suspended");

            control.resume();
            largeResponseWritten.get(5, SECONDS);
            assertSame(handler, request.get(5, SECONDS));
            assertEquals((long) FRAME_SIZE * FRAME_COUNT, handler.bodyBytes.get());
            assertEquals(2, handler.protocolMajorVersion.get());

            Response pooled = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", pooled.getResponseBody());
            assertEquals(1, connectionCount.get(), "the next stream must reuse the HTTP/2 parent connection");
            assertNull(handler.throwable.get());
        }
    }

    @Test
    public void cancellationResetsOnlyTheHttp2Stream() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            RecordingHandler handler = new RecordingHandler();
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/cancel")).execute(handler);
            ResponseBodyControl control = handler.control.get(5, SECONDS);

            control.resume();
            assertEquals("first", handler.items.poll(5, SECONDS));
            control.cancel();

            assertSame(handler, request.get(5, SECONDS));
            assertTrue(cancelledStreamClosed.await(5, SECONDS), "cancellation must close the HTTP/2 child stream");
            assertNull(handler.throwable.get());

            Response sibling = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", sibling.getResponseBody());
            assertEquals(1, connectionCount.get(), "cancellation must preserve the shared HTTP/2 connection");
        }
    }

    @Test
    public void suspendedStreamDoesNotStallSiblingStream() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            RecordingHandler suspendedHandler = new RecordingHandler();
            ListenableFuture<RecordingHandler> suspendedRequest =
                    client.prepareGet(url("/large")).execute(suspendedHandler);
            ResponseBodyControl control = suspendedHandler.control.get(5, SECONDS);

            assertTrue(largeResponseQueued.await(5, SECONDS));
            assertThrows(TimeoutException.class, () -> suspendedRequest.get(250, MILLISECONDS));

            Response sibling = client.prepareGet(url("/large-sibling"))
                    .setReadTimeout(Duration.ofSeconds(5))
                    .execute()
                    .get(10, SECONDS);
            assertEquals((long) FRAME_SIZE * SIBLING_FRAME_COUNT, sibling.getResponseBodyAsBytes().length);
            assertEquals(1, connectionCount.get(), "a suspended stream must not stall a sibling on the same connection");
            assertFalse(largeResponseWritten.isDone(),
                    "connection-level refills must not consume the suspended stream's flow-control window");

            control.cancel();
            assertSame(suspendedHandler, suspendedRequest.get(5, SECONDS));
            assertNull(suspendedHandler.throwable.get());
        }
    }

    @Test
    public void repeatedCancellationReturnsHttp2ConnectionWindow() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            for (int i = 0; i < CANCELLATION_ATTEMPTS; i++) {
                RecordingHandler handler = new RecordingHandler(true);
                ListenableFuture<RecordingHandler> request =
                        client.prepareGet(url("/cancel-large")).execute(handler);
                ResponseBodyControl control = handler.control.get(5, SECONDS);

                control.resume();
                handler.firstBodyPart.get(5, SECONDS);
                control.cancel();

                assertSame(handler, request.get(5, SECONDS));
                assertTrue(Boolean.TRUE.equals(cancelledLargeStreamClosed.poll(5, SECONDS)));
                assertNull(handler.throwable.get());
            }

            Response sibling = client.prepareGet(url("/large-sibling"))
                    .setReadTimeout(Duration.ofSeconds(5))
                    .execute()
                    .get(10, SECONDS);
            assertEquals((long) FRAME_SIZE * SIBLING_FRAME_COUNT, sibling.getResponseBodyAsBytes().length);
            assertEquals(1, connectionCount.get(), "cancelled streams must return connection-level flow-control credit");
        }
    }

    @Test
    public void cancellationFromTerminalBodyCallbackCompletesOnce() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            RecordingHandler handler = new RecordingHandler(false, true);
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/pool")).execute(handler);

            handler.control.get(5, SECONDS).resume();

            assertSame(handler, request.get(5, SECONDS));
            assertEquals(1, handler.bodyBytes.get());
            assertEquals(1, handler.completionCount.get());
            assertNull(handler.throwable.get());

            Response sibling = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", sibling.getResponseBody());
            assertEquals(1, connectionCount.get());
        }
    }

    @Test
    public void suspensionCannotDeferBodylessResponse() throws Exception {
        try (AsyncHttpClient client = http2Client()) {
            RecordingHandler handler = new RecordingHandler();
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/empty")).execute(handler);
            ResponseBodyControl control = handler.control.get(5, SECONDS);

            assertSame(handler, request.get(5, SECONDS));
            control.suspend();
            control.resume();
            control.cancel();

            assertEquals(0, handler.bodyBytes.get());
            assertEquals(1, handler.completionCount.get());
            assertNull(handler.throwable.get());

            Response sibling = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", sibling.getResponseBody());
            assertEquals(1, connectionCount.get());
        }
    }

    private AsyncHttpClient http2Client() {
        return asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setHttp2InitialWindowSize(32 * 1024)
                .setMaxConnectionsPerHost(1)
                .setReadTimeout(Duration.ofMillis(100))
                .setRequestTimeout(Duration.ofSeconds(10)));
    }

    private String url(String path) {
        return "https://localhost:" + serverPort + path;
    }

    private final class StreamingServerHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof Http2HeadersFrame)) {
                return;
            }
            Http2HeadersFrame request = (Http2HeadersFrame) message;
            String path = request.headers().path().toString();
            switch (path) {
                case "/large":
                    writeHeaders(ctx);
                    ChannelFuture finalWrite = writeFrames(ctx, FRAME_COUNT);
                    largeResponseQueued.countDown();
                    finalWrite.addListener(result -> {
                        if (result.isSuccess()) {
                            largeResponseWritten.complete(null);
                        } else {
                            largeResponseWritten.completeExceptionally(result.cause());
                        }
                    });
                    break;
                case "/cancel":
                    ctx.channel().closeFuture().addListener(ignored -> cancelledStreamClosed.countDown());
                    writeHeaders(ctx);
                    ctx.writeAndFlush(new DefaultHttp2DataFrame(
                            Unpooled.copiedBuffer("first", CharsetUtil.US_ASCII), false));
                    break;
                case "/cancel-large":
                    ctx.channel().closeFuture().addListener(ignored -> cancelledLargeStreamClosed.offer(Boolean.TRUE));
                    writeHeaders(ctx);
                    writeFrames(ctx, FRAME_COUNT);
                    break;
                case "/large-sibling":
                    writeHeaders(ctx);
                    writeFrames(ctx, SIBLING_FRAME_COUNT);
                    break;
                case "/empty":
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(
                            new DefaultHttp2Headers().status("200"), true));
                    break;
                default:
                    writeHeaders(ctx);
                    Integer connectionId = ctx.channel().parent().attr(CONNECTION_ID).get();
                    ctx.writeAndFlush(new DefaultHttp2DataFrame(
                            Unpooled.copiedBuffer(Integer.toString(connectionId), CharsetUtil.US_ASCII), true));
                    break;
            }
        }

        private void writeHeaders(ChannelHandlerContext ctx) {
            ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
        }

        private ChannelFuture writeFrames(ChannelHandlerContext ctx, int frameCount) {
            ChannelFuture finalWrite = null;
            for (int i = 0; i < frameCount; i++) {
                ByteBuf content = ctx.alloc().buffer(FRAME_SIZE).writeZero(FRAME_SIZE);
                boolean last = i == frameCount - 1;
                finalWrite = last
                        ? ctx.writeAndFlush(new DefaultHttp2DataFrame(content, true))
                        : ctx.write(new DefaultHttp2DataFrame(content, false));
            }
            return finalWrite;
        }
    }

    private static final class RecordingHandler implements AsyncHandler<RecordingHandler> {
        private final CompletableFuture<ResponseBodyControl> control = new CompletableFuture<>();
        private final LinkedBlockingQueue<String> items = new LinkedBlockingQueue<>();
        private final AtomicLong bodyBytes = new AtomicLong();
        private final AtomicInteger protocolMajorVersion = new AtomicInteger();
        private final AtomicReference<Throwable> throwable = new AtomicReference<>();
        private final CompletableFuture<Void> firstBodyPart = new CompletableFuture<>();
        private final AtomicInteger completionCount = new AtomicInteger();
        private final boolean suspendEveryPart;
        private final boolean cancelOnBodyPart;
        private ResponseBodyControl responseBodyControl;

        private RecordingHandler() {
            this(false, false);
        }

        private RecordingHandler(boolean suspendEveryPart) {
            this(suspendEveryPart, false);
        }

        private RecordingHandler(boolean suspendEveryPart, boolean cancelOnBodyPart) {
            this.suspendEveryPart = suspendEveryPart;
            this.cancelOnBodyPart = cancelOnBodyPart;
        }

        @Override
        public State onStatusReceived(HttpResponseStatus responseStatus) {
            protocolMajorVersion.set(responseStatus.getProtocolMajorVersion());
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(io.netty.handler.codec.http.HttpHeaders headers) {
            return State.CONTINUE;
        }

        @Override
        public State onResponseBodyStart(ResponseBodyControl newControl) {
            responseBodyControl = newControl;
            newControl.suspend();
            control.complete(newControl);
            return State.CONTINUE;
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
            byte[] bytes = bodyPart.getBodyPartBytes();
            bodyBytes.addAndGet(bytes.length);
            if (bytes.length > 0) {
                if (suspendEveryPart) {
                    responseBodyControl.suspend();
                }
                items.add(new String(bytes, CharsetUtil.US_ASCII));
                firstBodyPart.complete(null);
            }
            if (cancelOnBodyPart) {
                responseBodyControl.cancel();
            }
            return State.CONTINUE;
        }

        @Override
        public void onThrowable(Throwable error) {
            throwable.compareAndSet(null, error);
        }

        @Override
        public RecordingHandler onCompleted() {
            completionCount.incrementAndGet();
            return this;
        }
    }
}
