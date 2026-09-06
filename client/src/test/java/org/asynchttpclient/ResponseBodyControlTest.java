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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.EARLY_HINTS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(NettyLeakDetectorExtension.class)
public class ResponseBodyControlTest {

    private static final AttributeKey<Integer> CONNECTION_ID =
            AttributeKey.valueOf("response-body-control-connection-id");

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final CompletableFuture<ChannelHandlerContext> responseContext = new CompletableFuture<>();
    private final CompletableFuture<ChannelHandlerContext> firstReplayContext = new CompletableFuture<>();
    private final CompletableFuture<ChannelHandlerContext> secondReplayContext = new CompletableFuture<>();
    private final CountDownLatch cancelledConnectionClosed = new CountDownLatch(1);
    private final AtomicInteger replayRequestCount = new AtomicInteger();

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private Channel tlsServerChannel;
    private ChannelGroup serverChildChannels;
    private SslContext tlsServerSslContext;
    private int serverPort;
    private int tlsServerPort;

    @BeforeEach
    public void startServer() throws InterruptedException {
        serverGroup = new NioEventLoopGroup(1);
        serverChildChannels = new DefaultChannelGroup("response-body-control-http1", GlobalEventExecutor.INSTANCE);

        serverChannel = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        serverChildChannels.add(channel);
                        channel.attr(CONNECTION_ID).set(connectionCount.incrementAndGet());
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1024))
                                .addLast(new StreamingServerHandler());
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
        if (tlsServerChannel != null) {
            tlsServerChannel.close().sync();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully(0, 100, MILLISECONDS).sync();
        }
        ReferenceCountUtil.release(tlsServerSslContext);
    }

    @Test
    public void suspensionControlsReadsAndCompletedConnectionIsPooled() throws Exception {
        AtomicReference<Channel> clientChannel = new AtomicReference<>();
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(10))
                .setHttpAdditionalChannelInitializer(clientChannel::set))) {
            RecordingHandler handler = new RecordingHandler(true);
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/controlled")).execute(handler);
            ResponseBodyControl control = handler.control.get(5, SECONDS);
            ChannelHandlerContext server = responseContext.get(5, SECONDS);

            assertFalse(clientChannel.get().config().isAutoRead(), "suspension must pause Netty auto-read");
            writeChunk(server, "one");
            assertNull(handler.items.poll(250, MILLISECONDS), "a suspended response must not read new body bytes");

            control.resume();
            assertEquals("one", handler.items.poll(5, SECONDS));
            awaitEventLoop(clientChannel.get());
            assertFalse(clientChannel.get().config().isAutoRead(), "the handler suspended the response again");

            writeChunk(server, "two");
            assertNull(handler.items.poll(250, MILLISECONDS), "the second suspension must also stop reads");
            control.resume();
            assertEquals("two", handler.items.poll(5, SECONDS));

            writeLast(server);
            control.resume();
            assertSame(handler, request.get(5, SECONDS));
            assertTrue(clientChannel.get().config().isAutoRead(), "pooling must restore the channel's read mode");

            Response pooled = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", pooled.getResponseBody());
            assertEquals(1, connectionCount.get(), "a fully consumed HTTP/1.1 connection must be reused");
            assertNull(handler.throwable.get());
        }
    }

    @Test
    public void suspensionPausesReadTimeoutAndCancellationClosesConnection() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxConnectionsPerHost(1)
                .setReadTimeout(Duration.ofMillis(100))
                .setRequestTimeout(Duration.ofSeconds(10)))) {
            RecordingHandler handler = new RecordingHandler(false);
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/cancel")).execute(handler);
            ResponseBodyControl control = handler.control.get(5, SECONDS);

            assertThrows(TimeoutException.class, () -> request.get(250, MILLISECONDS),
                    "intentional suspension must pause the network read timeout");
            control.cancel();

            assertSame(handler, request.get(5, SECONDS), "body cancellation completes the handler normally");
            assertTrue(cancelledConnectionClosed.await(5, SECONDS), "an unread HTTP/1.1 body cannot be pooled");
            assertNull(handler.throwable.get());

            Response replacement = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("2", replacement.getResponseBody());
            assertEquals(2, connectionCount.get(), "the request after cancellation must use a new connection");
        }
    }

    @Test
    public void readTimeoutRestartsWhenResponseResumes() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setReadTimeout(Duration.ofMillis(100))
                .setRequestTimeout(Duration.ofSeconds(10)))) {
            RecordingHandler handler = new RecordingHandler(false);
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/cancel")).execute(handler);
            ResponseBodyControl control = handler.control.get(5, SECONDS);

            assertThrows(TimeoutException.class, () -> request.get(250, MILLISECONDS));
            control.resume();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> request.get(5, SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(cancelledConnectionClosed.await(5, SECONDS));
            assertSame(failure.getCause(), handler.throwable.get());
        }
    }

    @Test
    public void requestTimeoutRemainsActiveWhileSuspended() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setReadTimeout(Duration.ofMillis(50))
                .setRequestTimeout(Duration.ofMillis(250)))) {
            RecordingHandler handler = new RecordingHandler(false);
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/cancel")).execute(handler);
            handler.control.get(5, SECONDS);

            ExecutionException failure = assertThrows(ExecutionException.class, () -> request.get(5, SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().startsWith("Request timeout"));
            assertTrue(cancelledConnectionClosed.await(5, SECONDS));
            assertSame(failure.getCause(), handler.throwable.get());
        }
    }

    @Test
    public void abortFromResponseBodyStartCompletesNormally() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(10)))) {
            RecordingHandler handler = new RecordingHandler(false) {
                @Override
                public State onResponseBodyStart(ResponseBodyControl newControl) {
                    return State.ABORT;
                }
            };

            assertSame(handler, client.prepareGet(url("/cancel")).execute(handler).get(5, SECONDS));
            assertTrue(handler.items.isEmpty());
            assertEquals(1, handler.completionCount.get());
            assertNull(handler.throwable.get());
            assertTrue(cancelledConnectionClosed.await(5, SECONDS));
        }
    }

    @Test
    public void exceptionFromResponseBodyStartFailsRequest() throws Exception {
        RuntimeException expected = new RuntimeException("response start failed");
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(10)))) {
            RecordingHandler handler = new RecordingHandler(false) {
                @Override
                public State onResponseBodyStart(ResponseBodyControl newControl) {
                    throw expected;
                }
            };

            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/cancel")).execute(handler);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> request.get(5, SECONDS));
            assertSame(expected, failure.getCause());
            assertSame(expected, handler.throwable.get());
            assertEquals(0, handler.completionCount.get());
            assertTrue(cancelledConnectionClosed.await(5, SECONDS));
        }
    }

    @Test
    public void cancellationFromTerminalBodyCallbackCompletesOnce() throws Exception {
        AtomicReference<ResponseBodyControl> callbackControl = new AtomicReference<>();
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(10)))) {
            RecordingHandler handler = new RecordingHandler(false) {
                @Override
                public State onResponseBodyStart(ResponseBodyControl newControl) {
                    callbackControl.set(newControl);
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws IOException {
                    State state = super.onBodyPartReceived(bodyPart);
                    callbackControl.get().cancel();
                    return state;
                }
            };

            assertSame(handler, client.prepareGet(url("/pool")).execute(handler).get(5, SECONDS));
            assertEquals("1", handler.items.poll(5, SECONDS));
            assertEquals(1, handler.completionCount.get());
            assertNull(handler.throwable.get());

            Response replacement = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", replacement.getResponseBody());
            assertEquals(1, connectionCount.get(), "a fully read response can reuse its HTTP/1.1 connection");
        }
    }

    @Test
    public void cancellationFromTrailerCallbackSkipsTerminalBodyCallbackAndReusesConnection() throws Exception {
        AtomicReference<ResponseBodyControl> callbackControl = new AtomicReference<>();
        AtomicBoolean trailerSeen = new AtomicBoolean();
        AtomicInteger bodyPartCallsAfterTrailers = new AtomicInteger();
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(10)))) {
            RecordingHandler handler = new RecordingHandler(false) {
                @Override
                public State onResponseBodyStart(ResponseBodyControl newControl) {
                    callbackControl.set(newControl);
                    return State.CONTINUE;
                }

                @Override
                public State onTrailingHeadersReceived(io.netty.handler.codec.http.HttpHeaders headers) {
                    trailerSeen.set(true);
                    callbackControl.get().cancel();
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws IOException {
                    if (trailerSeen.get()) {
                        bodyPartCallsAfterTrailers.incrementAndGet();
                    }
                    return super.onBodyPartReceived(bodyPart);
                }
            };

            assertSame(handler, client.prepareGet(url("/trailers")).execute(handler).get(5, SECONDS));
            assertTrue(trailerSeen.get());
            assertEquals(0, bodyPartCallsAfterTrailers.get(),
                    "cancellation from trailers must skip later terminal body callbacks");
            assertEquals(1, handler.completionCount.get());
            assertNull(handler.throwable.get());

            Response replacement = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", replacement.getResponseBody());
            assertEquals(1, connectionCount.get());
        }
    }

    @Test
    public void responseBodyControlIsProvidedForAnEmptyResponse() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(10)))) {
            RecordingHandler handler = new RecordingHandler(false);
            ListenableFuture<RecordingHandler> request = client.prepareGet(url("/empty")).execute(handler);
            ResponseBodyControl control = handler.control.get(5, SECONDS);

            assertSame(handler, request.get(5, SECONDS), "suspension cannot defer a bodyless response");
            control.suspend();
            control.resume();
            control.cancel();

            assertTrue(handler.items.isEmpty());
            assertEquals(1, handler.completionCount.get());
            assertNull(handler.throwable.get());

            Response pooled = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", pooled.getResponseBody());
            assertEquals(1, connectionCount.get());
        }
    }

    @Test
    public void earlyHintsDoNotStartOrCompleteTheResponseBody() throws Exception {
        AtomicInteger statuses = new AtomicInteger();
        AtomicInteger headers = new AtomicInteger();
        AtomicInteger bodyStarts = new AtomicInteger();
        AtomicInteger finalStatus = new AtomicInteger();
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(10)))) {
            RecordingHandler handler = new RecordingHandler(false) {
                @Override
                public State onStatusReceived(HttpResponseStatus responseStatus) {
                    statuses.incrementAndGet();
                    finalStatus.set(responseStatus.getStatusCode());
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(io.netty.handler.codec.http.HttpHeaders responseHeaders) {
                    headers.incrementAndGet();
                    assertEquals("present", responseHeaders.get("final-header"));
                    assertNull(responseHeaders.get("link"));
                    return State.CONTINUE;
                }

                @Override
                public State onResponseBodyStart(ResponseBodyControl control) {
                    bodyStarts.incrementAndGet();
                    return State.CONTINUE;
                }
            };

            assertSame(handler, client.prepareGet(url("/early-hints")).execute(handler).get(5, SECONDS));
            assertEquals(1, statuses.get());
            assertEquals(OK.code(), finalStatus.get());
            assertEquals(1, headers.get());
            assertEquals(1, bodyStarts.get());
            assertEquals("final", handler.items.poll(5, SECONDS));
            assertEquals(1, handler.completionCount.get());
            assertNull(handler.throwable.get());

            Response pooled = client.prepareGet(url("/pool")).execute().get(5, SECONDS);
            assertEquals("1", pooled.getResponseBody());
            assertEquals(1, connectionCount.get());
        }
    }

    @Test
    public void ioExceptionReplayReplacesControlAndRestoresDrainingChannel() throws Exception {
        startTlsServer();
        AtomicBoolean replay = new AtomicBoolean();
        IOExceptionFilter replayOnce = new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getIOException() != null && "replay response".equals(ctx.getIOException().getMessage())
                        && replay.compareAndSet(false, true)) {
                    return new FilterContext.FilterContextBuilder<>(ctx.getAsyncHandler(), ctx.getRequest())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
        List<Channel> clientChannels = new CopyOnWriteArrayList<>();
        AtomicInteger responseStarts = new AtomicInteger();
        AtomicBoolean failFirstBodyPart = new AtomicBoolean(true);
        AtomicReference<ResponseBodyControl> firstControl = new AtomicReference<>();
        CompletableFuture<ResponseBodyControl> replacementControl = new CompletableFuture<>();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setMaxRequestRetry(1)
                .setRequestTimeout(Duration.ofSeconds(10))
                .addIOExceptionFilter(replayOnce)
                .setHttpAdditionalChannelInitializer(clientChannels::add))) {
            RecordingHandler handler = new RecordingHandler(false) {
                @Override
                public State onResponseBodyStart(ResponseBodyControl control) {
                    if (responseStarts.incrementAndGet() == 1) {
                        firstControl.set(control);
                    } else {
                        replacementControl.complete(control);
                    }
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws IOException {
                    if (failFirstBodyPart.compareAndSet(true, false)) {
                        firstControl.get().suspend();
                        throw new IOException("replay response");
                    }
                    return super.onBodyPartReceived(bodyPart);
                }
            };

            ListenableFuture<RecordingHandler> request = client.prepareGet(httpsUrl("/replay")).execute(handler);
            ChannelHandlerContext firstServer = firstReplayContext.get(5, SECONDS);
            ResponseBodyControl replacement = replacementControl.get(5, SECONDS);

            Channel firstClient = clientChannels.get(0);
            awaitEventLoop(firstClient);
            assertTrue(firstClient.config().isAutoRead(), "replay must restore reads before draining the old response");

            firstControl.get().suspend();
            firstControl.get().cancel();
            ChannelHandlerContext secondServer = secondReplayContext.get(5, SECONDS);
            writeChunk(secondServer, "replayed");
            writeLast(secondServer);
            replacement.resume();

            assertSame(handler, request.get(5, SECONDS));
            assertEquals("replayed", handler.items.poll(5, SECONDS));
            assertEquals(2, responseStarts.get());
            assertNull(handler.throwable.get());

            writeLast(firstServer);
        }
    }

    @Test
    public void callsAfterClientShutdownDoNotThrow() throws Exception {
        AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(10)));
        RecordingHandler handler = new RecordingHandler(false);
        client.prepareGet(url("/cancel")).execute(handler);
        ResponseBodyControl control = handler.control.get(5, SECONDS);

        client.close();

        assertDoesNotThrow(control::suspend);
        assertDoesNotThrow(control::resume);
        assertDoesNotThrow(control::cancel);
    }

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }

    private String httpsUrl(String path) {
        return "https://localhost:" + tlsServerPort + path;
    }

    private void startTlsServer() throws Exception {
        X509Bundle bundle = new CertificateBuilder()
                .subject("CN=localhost")
                .setIsCertificateAuthority(true)
                .buildSelfSigned();
        tlsServerSslContext = SslContextBuilder.forServer(bundle.toKeyManagerFactory()).build();
        tlsServerChannel = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        serverChildChannels.add(channel);
                        channel.attr(CONNECTION_ID).set(connectionCount.incrementAndGet());
                        channel.pipeline()
                                .addLast(tlsServerSslContext.newHandler(channel.alloc()))
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1024))
                                .addLast(new StreamingServerHandler());
                    }
                })
                .bind(0)
                .sync()
                .channel();
        tlsServerPort = ((InetSocketAddress) tlsServerChannel.localAddress()).getPort();
    }

    private static void awaitEventLoop(Channel channel) throws InterruptedException {
        channel.eventLoop().submit(() -> {
        }).sync();
    }

    private static void writeChunk(ChannelHandlerContext ctx, String value) throws InterruptedException {
        ctx.executor().submit(() -> ctx.writeAndFlush(
                new DefaultHttpContent(Unpooled.copiedBuffer(value, CharsetUtil.US_ASCII)))).sync();
    }

    private static void writeLast(ChannelHandlerContext ctx) throws InterruptedException {
        ctx.executor().submit(() -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)).sync();
    }

    private final class StreamingServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            switch (request.uri()) {
                case "/controlled":
                    writeStreamingHeaders(ctx);
                    responseContext.complete(ctx);
                    break;
                case "/cancel":
                    ctx.channel().closeFuture().addListener(ignored -> cancelledConnectionClosed.countDown());
                    writeStreamingHeaders(ctx);
                    responseContext.complete(ctx);
                    break;
                case "/empty":
                    DefaultFullHttpResponse emptyResponse =
                            new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.EMPTY_BUFFER);
                    HttpUtil.setContentLength(emptyResponse, 0);
                    HttpUtil.setKeepAlive(emptyResponse, true);
                    ctx.writeAndFlush(emptyResponse);
                    break;
                case "/replay":
                    writeStreamingHeaders(ctx);
                    if (replayRequestCount.incrementAndGet() == 1) {
                        firstReplayContext.complete(ctx);
                        ctx.writeAndFlush(new DefaultHttpContent(
                                Unpooled.copiedBuffer("first", CharsetUtil.US_ASCII)));
                    } else {
                        secondReplayContext.complete(ctx);
                    }
                    break;
                case "/trailers":
                    HttpResponse trailerResponse = streamingResponse();
                    trailerResponse.headers().set("trailer", "test-trailer");
                    ctx.write(trailerResponse);
                    DefaultLastHttpContent last = new DefaultLastHttpContent(
                            Unpooled.copiedBuffer("last", CharsetUtil.US_ASCII));
                    last.trailingHeaders().set("test-trailer", "present");
                    ctx.writeAndFlush(last);
                    break;
                case "/early-hints":
                    HttpResponse earlyHints = new DefaultHttpResponse(HTTP_1_1, EARLY_HINTS);
                    earlyHints.headers().set("link", "</style.css>; rel=preload; as=style");
                    ctx.write(earlyHints);
                    ctx.write(LastHttpContent.EMPTY_LAST_CONTENT);
                    ByteBuf finalContent = Unpooled.copiedBuffer("final", CharsetUtil.US_ASCII);
                    DefaultFullHttpResponse finalResponse = new DefaultFullHttpResponse(HTTP_1_1, OK, finalContent);
                    finalResponse.headers().set("final-header", "present");
                    HttpUtil.setContentLength(finalResponse, finalContent.readableBytes());
                    HttpUtil.setKeepAlive(finalResponse, true);
                    ctx.writeAndFlush(finalResponse);
                    break;
                default:
                    ByteBuf content = Unpooled.copiedBuffer(
                            Integer.toString(ctx.channel().attr(CONNECTION_ID).get()), CharsetUtil.US_ASCII);
                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
                    HttpUtil.setContentLength(response, content.readableBytes());
                    HttpUtil.setKeepAlive(response, true);
                    ctx.writeAndFlush(response);
                    break;
            }
        }

        private void writeStreamingHeaders(ChannelHandlerContext ctx) {
            ctx.writeAndFlush(streamingResponse());
        }

        private HttpResponse streamingResponse() {
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            HttpUtil.setTransferEncodingChunked(response, true);
            HttpUtil.setKeepAlive(response, true);
            return response;
        }
    }

    private static class RecordingHandler implements AsyncHandler<RecordingHandler> {
        private final boolean suspendEveryPart;
        private final CompletableFuture<ResponseBodyControl> control = new CompletableFuture<>();
        private final LinkedBlockingQueue<String> items = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> throwable = new AtomicReference<>();
        private final AtomicInteger completionCount = new AtomicInteger();
        private ResponseBodyControl responseBodyControl;

        private RecordingHandler(boolean suspendEveryPart) {
            this.suspendEveryPart = suspendEveryPart;
        }

        @Override
        public State onStatusReceived(HttpResponseStatus responseStatus) {
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
        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws IOException {
            if (suspendEveryPart) {
                responseBodyControl.suspend();
            }
            if (bodyPart.length() > 0) {
                items.add(new String(bodyPart.getBodyPartBytes(), CharsetUtil.US_ASCII));
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
