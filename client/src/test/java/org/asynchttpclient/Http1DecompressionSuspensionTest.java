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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.asynchttpclient.channel.ChannelPool;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.netty.channel.DefaultChannelPool;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.zip.GZIPOutputStream;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A suspended HTTP/1.1 response must not keep inflating the compressed bytes it has already read. A highly
 * compressible body inflates ~1000:1, so a single 64 KiB socket read can otherwise produce tens of megabytes
 * of body parts that no one asked for.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
public class Http1DecompressionSuspensionTest {

    private static final int MAX_PART_SIZE = 64 * 1024;

    /** 64 MiB of zeros: gzip inflates it ~1000:1, so the whole compressed body is about one socket read. */
    private static final int ZEROS_SIZE = 64 * 1024 * 1024;
    private static final byte[] ZEROS_GZIP = gzip(new byte[ZEROS_SIZE]);

    private static final byte[] TEXT = text(4 * 1024 * 1024);
    private static final byte[] TEXT_GZIP = gzip(TEXT);

    private final AtomicInteger connectionCount = new AtomicInteger();

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private ChannelGroup serverChildChannels;
    private int serverPort;

    private static byte[] gzip(byte[] payload) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(payload);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return compressed.toByteArray();
    }

    private static byte[] text(int length) {
        String[] words = "the quick brown fox jumps over the lazy dog while streams wait for demand".split(" ");
        Random random = new Random(42);
        StringBuilder text = new StringBuilder(length + 16);
        while (text.length() < length) {
            text.append(words[random.nextInt(words.length)]).append(random.nextInt(9) == 0 ? '\n' : ' ');
        }
        return text.substring(0, length).getBytes(StandardCharsets.US_ASCII);
    }

    @BeforeEach
    public void startServer() throws InterruptedException {
        serverGroup = new NioEventLoopGroup(1);
        serverChildChannels = new DefaultChannelGroup("http1-decompression-suspension", GlobalEventExecutor.INSTANCE);
        serverChannel = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        serverChildChannels.add(channel);
                        connectionCount.incrementAndGet();
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1024))
                                .addLast(new CompressedResponseHandler());
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
    }

    @Test
    public void suspensionStopsInflationUntilResumed() throws Exception {
        assertDemandBoundedInflation(true);
    }

    @Test
    public void suspensionStopsInflationWithAutoReadDisabled() throws Exception {
        assertDemandBoundedInflation(false);
    }

    private void assertDemandBoundedInflation(boolean autoRead) throws Exception {
        AtomicReference<Channel> clientChannel = new AtomicReference<>();
        DefaultAsyncHttpClientConfig.Builder config = config()
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(60))
                .setHttpAdditionalChannelInitializer(clientChannel::set);
        if (!autoRead) {
            config.addChannelOption(ChannelOption.AUTO_READ, false);
        }
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            SuspendingHandler handler = new SuspendingHandler();
            ListenableFuture<Void> response = client.prepareGet(url("/zeros")).execute(handler);

            int first = handler.parts.poll(10, SECONDS);
            awaitEventLoop(clientChannel.get());
            assertTrue(first > 0 && first <= MAX_PART_SIZE, "unexpected first part size " + first);
            assertEquals(first, handler.bytes.get(), "a suspended response inflated " + (handler.bytes.get() - first)
                    + " more bytes in " + (handler.partCount.get() - 1) + " parts from input it had already read");

            int resumes = 0;
            while (!handler.last.get()) {
                long before = handler.bytes.get();
                handler.control.resume();
                resumes++;
                int part = handler.parts.poll(10, SECONDS);
                awaitEventLoop(clientChannel.get());
                Integer terminal = handler.parts.poll();
                if (terminal != null) {
                    assertEquals(0, terminal, "only the empty terminal part may follow a data part");
                    assertNull(handler.parts.poll());
                }
                assertTrue(part <= MAX_PART_SIZE, "a resume produced a part of " + part + " bytes");
                assertEquals(before + part, handler.bytes.get(), "one resume must produce at most one part");
            }

            response.get(10, SECONDS);
            assertNull(handler.failure.get());
            assertEquals(ZEROS_SIZE, handler.bytes.get());
            assertFalse(handler.corrupt.get(), "the inflated body must be all zeros");
            assertTrue(resumes > ZEROS_SIZE / MAX_PART_SIZE / 2, "suspension must hold back most parts: " + resumes);

            Response pooled = client.prepareGet(url("/empty")).execute().get(10, SECONDS);
            assertEquals(200, pooled.getStatusCode());
            assertEquals(1, connectionCount.get(), "a fully drained connection must be reused");
        }
    }

    @Test
    public void chunkedBodyAndTrailersAreDeliveredIntactAcrossSuspensions() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(60)))) {
            SuspendingHandler handler = new SuspendingHandler();
            handler.keepBody = true;
            ListenableFuture<Void> response = client.prepareGet(url("/text-chunked")).execute(handler);

            while (!handler.last.get()) {
                assertNotNull(handler.parts.poll(10, SECONDS));
                handler.control.resume();
            }
            response.get(10, SECONDS);

            assertNull(handler.failure.get());
            assertArrayEquals(TEXT, handler.body.toByteArray());
            assertEquals("done", handler.trailer.get(), "trailers must arrive after the whole body");

            Response pooled = client.prepareGet(url("/empty")).execute().get(10, SECONDS);
            assertEquals(200, pooled.getStatusCode());
            assertEquals(1, connectionCount.get(),
                    "a fully drained chunked response must leave the connection reusable");
        }
    }

    @Test
    public void bodyEndedByConnectionCloseIsDeliveredIntactAcrossSuspensions() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(60)))) {
            SuspendingHandler handler = new SuspendingHandler();
            handler.keepBody = true;
            ListenableFuture<Void> response = client.prepareGet(url("/text-close")).execute(handler);

            while (!handler.last.get()) {
                assertNotNull(handler.parts.poll(10, SECONDS));
                handler.control.resume();
            }
            response.get(10, SECONDS);

            assertNull(handler.failure.get());
            assertArrayEquals(TEXT, handler.body.toByteArray());
        }
    }

    @Test
    public void cancellationWhileCompressedInputIsBufferedClosesTheConnection() throws Exception {
        AtomicReference<Channel> clientChannel = new AtomicReference<>();
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(60))
                .setHttpAdditionalChannelInitializer(clientChannel::set))) {
            SuspendingHandler handler = new SuspendingHandler();
            ListenableFuture<Void> response = client.prepareGet(url("/zeros")).execute(handler);

            assertNotNull(handler.parts.poll(10, SECONDS));
            handler.control.cancel();
            response.get(10, SECONDS);
            clientChannel.get().closeFuture().await(10, SECONDS);
            assertFalse(clientChannel.get().isOpen(), "an unfinished response cannot leave its connection reusable");
            assertTrue(handler.bytes.get() < ZEROS_SIZE);

            Response next = client.prepareGet(url("/empty")).execute().get(10, SECONDS);
            assertEquals(200, next.getStatusCode());
            assertEquals(2, connectionCount.get());
        }
    }

    @Test
    public void abortWhileCompressedInputIsBufferedClosesTheConnection() throws Exception {
        AtomicReference<Channel> clientChannel = new AtomicReference<>();
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(60))
                .setHttpAdditionalChannelInitializer(clientChannel::set))) {
            SuspendingHandler handler = new SuspendingHandler();
            handler.abortAfterFirstPart = true;
            client.prepareGet(url("/zeros")).execute(handler).get(10, SECONDS);

            clientChannel.get().closeFuture().await(10, SECONDS);
            assertFalse(clientChannel.get().isOpen());
            assertEquals(1, handler.partCount.get(), "no part may be delivered after ABORT");
        }
    }

    @Test
    public void replayWhileInputIsHeldBackStartsTheResponseAfresh() throws Exception {
        assertReplayStartsAfresh(true);
    }

    @Test
    public void replayWhileInputIsHeldBackStartsTheResponseAfreshWithAutoReadDisabled() throws Exception {
        assertReplayStartsAfresh(false);
    }

    /**
     * An IOException filter replays the request while the decompressor holds back input of the first response. The
     * handler, told to retry, must receive nothing more of the first response, whose connection is drained and closed.
     */
    private void assertReplayStartsAfresh(boolean autoRead) throws Exception {
        AtomicBoolean replayed = new AtomicBoolean();
        IOExceptionFilter replayOnce = new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getIOException() != null && replayed.compareAndSet(false, true)) {
                    return new FilterContext.FilterContextBuilder<>(ctx.getAsyncHandler(), ctx.getRequest())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
        List<Channel> clientChannels = new CopyOnWriteArrayList<>();
        DefaultAsyncHttpClientConfig.Builder config = config()
                .setMaxRequestRetry(1)
                .setRequestTimeout(Duration.ofSeconds(60))
                .addIOExceptionFilter(replayOnce)
                .setHttpAdditionalChannelInitializer(clientChannels::add);
        if (!autoRead) {
            config.addChannelOption(ChannelOption.AUTO_READ, false);
        }
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            SuspendingHandler handler = new SuspendingHandler();
            ListenableFuture<Void> response = client.prepareGet(url("/zeros")).execute(handler);
            assertNotNull(handler.parts.poll(10, SECONDS));

            // Fail the connection only once the body callback and the decompressor's pass have returned, so the
            // decompressor holds back the rest of the input it has read when the replay completes the old response.
            Channel first = clientChannels.get(0);
            first.eventLoop().submit(() -> {
                first.pipeline().fireExceptionCaught(new IOException("injected"));
            }).sync();
            assertTrue(replayed.get());

            while (!handler.last.get()) {
                assertNotNull(handler.parts.poll(10, SECONDS));
                handler.control.resume();
            }
            response.get(10, SECONDS);
            assertNull(handler.failure.get());

            List<String> events = handler.events;
            int retry = events.indexOf("retry");
            assertEquals("status", events.get(retry + 1),
                    "after onRetry, the handler must not receive the first response's body: " + events);
            assertEquals(ZEROS_SIZE, handler.bytes.get() - handler.bytesAtRetry);
            assertFalse(handler.corrupt.get());
            assertTrue(first.closeFuture().await(10, SECONDS), "the first connection must be drained and closed");
        }
    }

    @Test
    public void drainedBodyOfAReplayedResponseIsNotInflated() throws Exception {
        // A response filter replays the request on the headers of a compressed response whose body inflates far beyond
        // the limit. Nobody reads that body, so draining its connection must not inflate it. Inflating it would exceed
        // the limit, which closes the drained connection and could fail the replayed request, which shares the future.
        AtomicBoolean replayed = new AtomicBoolean();
        ResponseFilter replayOnce = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (replayed.compareAndSet(false, true)) {
                    return new FilterContext.FilterContextBuilder<>(ctx)
                            .request(Dsl.get(url("/empty")).build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
        List<Channel> clientChannels = new CopyOnWriteArrayList<>();
        HashedWheelTimer timer = new HashedWheelTimer();
        AcceptingPool pool = new AcceptingPool(new DefaultChannelPool(config().build(), timer));
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setMaxDecompressedResponseSize(1024 * 1024)
                .setRequestTimeout(Duration.ofSeconds(10))
                .setChannelPool(pool)
                .setHttpAdditionalChannelInitializer(clientChannels::add)
                .addResponseFilter(replayOnce))) {
            Response response = client.prepareGet(url("/zeros")).execute().get(10, SECONDS);
            assertEquals(200, response.getStatusCode());
            assertEquals(0, response.getResponseBodyAsBytes().length);
            assertTrue(replayed.get());

            // The drained connection goes back to the pool once its last chunk has arrived.
            Channel drained = clientChannels.get(0);
            Channel accepted;
            do {
                accepted = pool.accepted.poll(10, SECONDS);
                assertNotNull(accepted, "the drained connection must be returned to the pool");
            } while (accepted != drained);
        } finally {
            timer.stop();
        }
    }

    @Test
    public void compressedResponsesWithoutBodyComplete() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setMaxConnectionsPerHost(1))) {
            Response head = client.prepareHead(url("/zeros")).execute().get(10, SECONDS);
            assertEquals(200, head.getStatusCode());
            assertEquals(0, head.getResponseBodyAsBytes().length);

            Response empty = client.prepareGet(url("/empty")).execute().get(10, SECONDS);
            assertEquals(200, empty.getStatusCode());
            assertEquals(0, empty.getResponseBodyAsBytes().length);
            assertNull(empty.getHeader(HttpHeaderNames.CONTENT_ENCODING));

            assertEquals(1, connectionCount.get());
        }
    }

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }

    private static void awaitEventLoop(Channel channel) throws InterruptedException {
        channel.eventLoop().submit(() -> {
        }).sync();
    }

    /** Delegates to a pool and reports every connection that the pool has accepted. */
    private static final class AcceptingPool implements ChannelPool {

        final LinkedBlockingQueue<Channel> accepted = new LinkedBlockingQueue<>();
        private final ChannelPool delegate;

        AcceptingPool(ChannelPool delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean offer(Channel channel, Object partitionKey) {
            boolean offered = delegate.offer(channel, partitionKey);
            if (offered) {
                accepted.add(channel);
            }
            return offered;
        }

        @Override
        public @Nullable Channel poll(Object partitionKey) {
            return delegate.poll(partitionKey);
        }

        @Override
        public boolean removeAll(Channel channel) {
            return delegate.removeAll(channel);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void destroy() {
            delegate.destroy();
        }

        @Override
        public void flushPartitions(Predicate<Object> predicate) {
            delegate.flushPartitions(predicate);
        }

        @Override
        public Map<String, Long> getIdleChannelCountPerHost() {
            return delegate.getIdleChannelCountPerHost();
        }
    }

    /**
     * Suspends inline from every body callback, the way a demand-driven consumer does, and records what was
     * delivered.
     */
    private static final class SuspendingHandler implements AsyncHandler<Void> {

        final LinkedBlockingQueue<Integer> parts = new LinkedBlockingQueue<>();
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger partCount = new AtomicInteger();
        final AtomicBoolean corrupt = new AtomicBoolean();
        final AtomicBoolean last = new AtomicBoolean();
        final AtomicReference<String> trailer = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile ResponseBodyControl control;
        volatile long bytesAtRetry;
        volatile boolean keepBody;
        volatile boolean abortAfterFirstPart;

        @Override
        public State onStatusReceived(HttpResponseStatus responseStatus) {
            events.add("status");
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(HttpHeaders headers) {
            return State.CONTINUE;
        }

        @Override
        public State onResponseBodyStart(ResponseBodyControl control) {
            this.control = control;
            return State.CONTINUE;
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
            events.add("part");
            control.suspend();
            byte[] bytes = bodyPart.getBodyPartBytes();
            if (keepBody) {
                body.write(bytes, 0, bytes.length);
            } else {
                for (byte b : bytes) {
                    if (b != 0) {
                        corrupt.set(true);
                        break;
                    }
                }
            }
            this.bytes.addAndGet(bytes.length);
            partCount.incrementAndGet();
            if (bodyPart.isLast()) {
                last.set(true);
            }
            parts.add(bytes.length);
            return abortAfterFirstPart ? State.ABORT : State.CONTINUE;
        }

        @Override
        public State onTrailingHeadersReceived(HttpHeaders headers) {
            trailer.set(last.get() || bytes.get() != TEXT.length ? "early" : headers.get("x-trailer"));
            return State.CONTINUE;
        }

        @Override
        public void onRetry() {
            events.add("retry");
            bytesAtRetry = bytes.get();
        }

        @Override
        public void onThrowable(Throwable t) {
            failure.set(t);
        }

        @Override
        public Void onCompleted() {
            return null;
        }
    }

    private static final class CompressedResponseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            switch (request.uri()) {
                case "/zeros": {
                    boolean head = HttpMethod.HEAD.equals(request.method());
                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK,
                            head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(ZEROS_GZIP));
                    response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
                    HttpUtil.setContentLength(response, ZEROS_GZIP.length);
                    ctx.writeAndFlush(response);
                    break;
                }
                case "/empty": {
                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.EMPTY_BUFFER);
                    response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
                    HttpUtil.setContentLength(response, 0);
                    ctx.writeAndFlush(response);
                    break;
                }
                case "/text-chunked": {
                    HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
                    response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
                    HttpUtil.setTransferEncodingChunked(response, true);
                    ctx.write(response);
                    for (int offset = 0; offset < TEXT_GZIP.length; offset += 10_000) {
                        int length = Math.min(10_000, TEXT_GZIP.length - offset);
                        ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(TEXT_GZIP, offset, length)));
                    }
                    LastHttpContent last = new DefaultLastHttpContent();
                    last.trailingHeaders().set("x-trailer", "done");
                    ctx.writeAndFlush(last);
                    break;
                }
                case "/text-close": {
                    HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
                    response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                    ctx.write(response);
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(TEXT_GZIP)))
                            .addListener(ChannelFutureListener.CLOSE);
                    break;
                }
                default:
                    throw new IllegalArgumentException(request.uri());
            }
        }
    }
}
