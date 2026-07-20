/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.CRC32;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.OffloadedBodyReadTestSupport.BlockingInputStream;
import static org.asynchttpclient.OffloadedBodyReadTestSupport.CloseProbeInputStream;
import static org.asynchttpclient.OffloadedBodyReadTestSupport.awaitExecutorState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that streaming request bodies sent over HTTP/2 ({@link org.asynchttpclient.netty.request.body.NettyInputStreamBody},
 * {@link org.asynchttpclient.netty.request.body.NettyFileBody}, {@link org.asynchttpclient.netty.request.body.NettyBodyBody})
 * round-trip correctly while honouring HTTP/2 flow control and channel writability (finding #9).
 * <p>
 * The previous implementation buffered the entire body in heap before flushing. These tests upload large
 * bodies (16&nbsp;MB) and assert the server received every byte (length + CRC32), which can only hold if the
 * one-chunk-at-a-time backpressure pump produces, flushes, and ends the stream correctly. One test pins a
 * small {@code SETTINGS_INITIAL_WINDOW_SIZE} on the server so the client's stream child channel goes
 * unwritable mid-upload, forcing the pump to park and resume on {@code channelWritabilityChanged}; the upload
 * must still complete intact rather than OOM or hang.
 */
public class Http2StreamingBodyFlowControlTest {

    private static final int LARGE_SIZE = 16 * 1024 * 1024; // 16 MB
    private static final int SMALL_SIZE = 5; // a tiny streaming body

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private ChannelGroup serverChildChannels;
    private SslContext serverSslCtx;
    private int serverPort;

    @BeforeEach
    public void setUp() throws Exception {
        X509Bundle bundle = new CertificateBuilder()
                .subject("CN=localhost")
                .setIsCertificateAuthority(true)
                .buildSelfSigned();

        serverSslCtx = SslContextBuilder.forServer(bundle.toKeyManagerFactory())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1))
                .build();

        serverGroup = new NioEventLoopGroup(1);
        serverChildChannels = new DefaultChannelGroup("h2-streaming-body", GlobalEventExecutor.INSTANCE);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        if (serverChildChannels != null) {
            serverChildChannels.close().sync();
        }
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        }
        ReferenceCountUtil.release(serverSslCtx);
    }

    private String httpsUrl(String path) {
        return "https://localhost:" + serverPort + path;
    }

    /**
     * Starts the H2 server.
     *
     * @param initialWindowSize if &gt; 0, advertised as {@code SETTINGS_INITIAL_WINDOW_SIZE} to constrain the
     *                          client's per-stream flow-control window (forces the client unwritable on a large
     *                          upload); if &le; 0, the Netty default window is used.
     */
    private void startServer(int initialWindowSize) throws InterruptedException {
        startServer(initialWindowSize, ChecksumEchoHandler::new);
    }

    private void startServer(int initialWindowSize, Supplier<SimpleChannelInboundHandler<Object>> handlerFactory)
            throws InterruptedException {
        Http2FrameCodecBuilder codecBuilder = Http2FrameCodecBuilder.forServer();
        if (initialWindowSize > 0) {
            codecBuilder.initialSettings(new Http2Settings().initialWindowSize(initialWindowSize));
        }
        ServerBootstrap b = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        serverChildChannels.add(ch);
                        ch.pipeline()
                                .addLast("ssl", serverSslCtx.newHandler(ch.alloc()))
                                .addLast(codecBuilder.build())
                                .addLast(new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
                                    @Override
                                    protected void initChannel(Http2StreamChannel streamCh) {
                                        streamCh.pipeline().addLast(handlerFactory.get());
                                    }
                                }));
                    }
                });

        serverChannel = b.bind(0).sync().channel();
        serverPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /**
     * Resets the stream with {@code INTERNAL_ERROR} as soon as it sees the first request DATA frame, so a
     * streaming upload fails mid-flight. Used to verify the backpressure pump tears down cleanly (no hang, no
     * leak) when the stream dies while it is still producing.
     */
    private static final class ResetMidStreamHandler extends SimpleChannelInboundHandler<Object> {
        private boolean reset;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2DataFrame && !reset) {
                reset = true;
                ctx.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.INTERNAL_ERROR));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /**
     * Resets the stream as soon as it sees the request HEADERS — before any DATA — so the body pump is hit by
     * an external stream close while it is still parked on {@code SUSPEND} with no in-flight write.
     */
    private static final class ResetOnHeadersHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                ctx.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /** A streaming body that never has data ready (always {@code SUSPEND}), recording when it is closed. */
    private static final class SuspendingBody implements Body {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public Body.BodyState transferTo(ByteBuf target) {
            return Body.BodyState.SUSPEND;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /**
     * Streams a CRC32 + byte count over all DATA frames it receives (never buffering the whole body itself),
     * and on end-of-stream replies 200 with {@code x-received-length} and {@code x-received-crc} headers.
     */
    private static final class ChecksumEchoHandler extends SimpleChannelInboundHandler<Object> {
        private final CRC32 crc = new CRC32();
        private long received;
        private final byte[] scratch = new byte[8192];

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            boolean endStream = false;
            if (msg instanceof Http2HeadersFrame) {
                endStream = ((Http2HeadersFrame) msg).isEndStream();
            } else if (msg instanceof Http2DataFrame) {
                Http2DataFrame data = (Http2DataFrame) msg;
                int len = data.content().readableBytes();
                int off = 0;
                while (off < len) {
                    int n = Math.min(scratch.length, len - off);
                    data.content().getBytes(data.content().readerIndex() + off, scratch, 0, n);
                    crc.update(scratch, 0, n);
                    off += n;
                }
                received += len;
                endStream = data.isEndStream();
            }

            if (endStream) {
                Http2Headers responseHeaders = new DefaultHttp2Headers()
                        .status("200")
                        .add("x-received-length", Long.toString(received))
                        .add("x-received-crc", Long.toString(crc.getValue()));
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders, true));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private static byte[] deterministicPayload(int size) {
        byte[] data = new byte[size];
        // A non-trivial, position-dependent pattern so a misordered/truncated upload changes the CRC.
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return data;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private AsyncHttpClient http2Client() {
        return asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setMaxConnectionsPerHost(1)
                .setRequestBodyStreamReadOffloadEnabled(true)
                .setRequestTimeout(Duration.ofSeconds(60)));
    }

    private void assertEchoed(Response response, byte[] expected) {
        assertEquals(200, response.getStatusCode(), "request should have completed");
        assertEquals(expected.length, parseLongHeader(response, "x-received-length"),
                "server must receive every uploaded byte");
        assertEquals(crc32(expected), parseLongHeader(response, "x-received-crc"),
                "uploaded bytes must arrive intact and in order");
    }

    private static long parseLongHeader(Response response, String name) {
        String value = response.getHeader(name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fail("response header '" + name + "' was not a parseable long: " + value, e);
        }
    }

    // =========================================================================
    // NettyInputStreamBody — large streaming upload via InputStreamBodyGenerator
    // =========================================================================
    @Test
    public void largeInputStreamBodyRoundTripsOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(LARGE_SIZE);

        try (AsyncHttpClient client = http2Client()) {
            InputStream is = new ByteArrayInputStream(payload);
            Response response = client.preparePost(httpsUrl("/upload"))
                    .setBody(new InputStreamBodyGenerator(is, payload.length))
                    .execute()
                    .get(60, SECONDS);
            assertEchoed(response, payload);
        }
    }

    @Test
    public void inputStreamBodyReadsOffEventLoopOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(SMALL_SIZE);
        EventLoopProbeInputStream is = new EventLoopProbeInputStream(payload);

        try (AsyncHttpClient asyncClient = http2Client()) {
            DefaultAsyncHttpClient client = (DefaultAsyncHttpClient) asyncClient;
            is.setEventLoopGroup(client.channelManager().getEventLoopGroup());

            Response response = client.preparePost(httpsUrl("/upload"))
                    .setBody(new InputStreamBodyGenerator(is, payload.length))
                    .execute()
                    .get(30, SECONDS);
            assertEchoed(response, payload);
        }

        assertTrue(is.readAttempted.get(), "InputStream should have been read");
        assertFalse(is.readOnEventLoop.get(), "InputStream.read must not run on an event-loop thread");
    }

    @Test
    public void blockedInputStreamDoesNotStallSiblingStreamOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(SMALL_SIZE);
        BlockingInputStream bodyStream = new BlockingInputStream(payload);

        try (AsyncHttpClient client = http2Client()) {
            ListenableFuture<Response> blockedUpload = client.preparePost(httpsUrl("/blocked"))
                    .setBody(new InputStreamBodyGenerator(bodyStream, payload.length))
                    .execute();
            assertTrue(bodyStream.readStarted.await(5, SECONDS), "blocking body read should start");

            Response sibling = client.prepareGet(httpsUrl("/sibling"))
                    .execute()
                    .get(5, SECONDS);
            assertEquals(200, sibling.getStatusCode(),
                    "a sibling stream should complete while the upload read is blocked");

            bodyStream.releaseRead.countDown();
            assertEchoed(blockedUpload.get(30, SECONDS), payload);
        } finally {
            bodyStream.releaseRead.countDown();
        }
    }

    @Test
    public void cancelledQueuedInputStreamIsNotReadOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(SMALL_SIZE);
        BlockingInputStream blockingStream = new BlockingInputStream(payload);
        CloseProbeInputStream queuedStream = new CloseProbeInputStream();

        DefaultAsyncHttpClientConfig clientConfig = config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setMaxConnectionsPerHost(1)
                .setRequestBodyStreamReadOffloadEnabled(true)
                .setRequestBodyStreamReadThreadsCount(1)
                .setRequestBodyStreamReadQueueSize(2)
                .setRequestTimeout(Duration.ofSeconds(30))
                .build();

        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(clientConfig)) {
            ThreadPoolExecutor executor = assertInstanceOf(ThreadPoolExecutor.class,
                    client.blockingBodyReadExecutor());
            ListenableFuture<Response> blockingUpload = client.preparePost(httpsUrl("/blocking"))
                    .setBody(new InputStreamBodyGenerator(blockingStream, payload.length))
                    .execute();
            assertTrue(blockingStream.readStarted.await(5, SECONDS), "first body read should occupy the worker");

            ListenableFuture<Response> queuedUpload = client.preparePost(httpsUrl("/queued"))
                    .setBody(new InputStreamBodyGenerator(queuedStream, 1))
                    .execute();
            awaitExecutorState(executor, 1, 1);

            assertTrue(queuedUpload.cancel(true), "queued request should be cancelled");
            assertTrue(queuedStream.closed.await(5, SECONDS), "cancellation should close the queued stream");

            blockingStream.releaseRead.countDown();
            assertEchoed(blockingUpload.get(30, SECONDS), payload);
            awaitExecutorState(executor, 0, 0);
            assertFalse(queuedStream.readAttempted.get(),
                    "a queued read must not start after cancellation closed its stream");
        } finally {
            blockingStream.releaseRead.countDown();
        }
    }

    @Test
    public void inputStreamRuntimeFailureFailsRequestOverHttp2() throws Exception {
        startServer(-1);
        InputStream is = new InputStream() {
            @Override
            public int read() {
                throw new IllegalStateException("stream read failed");
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                throw new IllegalStateException("stream read failed");
            }
        };

        try (AsyncHttpClient client = http2Client()) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> client.preparePost(httpsUrl("/upload"))
                    .setBody(new InputStreamBodyGenerator(is, 1))
                    .execute()
                    .get(30, SECONDS));

            IOException readFailure = assertInstanceOf(IOException.class, failure.getCause());
            assertInstanceOf(IllegalStateException.class, readFailure.getCause());
        }
    }

    // =========================================================================
    // NettyFileBody — large streaming upload via setBody(File)
    // =========================================================================
    @Test
    public void largeFileBodyRoundTripsOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(LARGE_SIZE);
        File tmp = File.createTempFile("ahc-h2-upload", ".bin");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), payload);

        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/upload"))
                    .setBody(tmp)
                    .execute()
                    .get(60, SECONDS);
            assertEchoed(response, payload);
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    // =========================================================================
    // NettyBodyBody — large streaming upload via a generic BodyGenerator
    // =========================================================================
    @Test
    public void largeGenericBodyGeneratorRoundTripsOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(LARGE_SIZE);

        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/upload"))
                    .setBody(new ChunkingBodyGenerator(payload))
                    .execute()
                    .get(60, SECONDS);
            assertEchoed(response, payload);
        }
    }

    // =========================================================================
    // Small streaming body still works and ends the stream.
    // =========================================================================
    @Test
    public void smallStreamingBodyEndsStreamOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(SMALL_SIZE);

        try (AsyncHttpClient client = http2Client()) {
            InputStream is = new ByteArrayInputStream(payload);
            Response response = client.preparePost(httpsUrl("/small"))
                    .setBody(new InputStreamBodyGenerator(is, payload.length))
                    .execute()
                    .get(30, SECONDS);
            assertEchoed(response, payload);
        }
    }

    // =========================================================================
    // Backpressure: a tiny initial window forces the client unwritable mid-upload.
    // The pump must park on writability and resume as the server drains, completing intact.
    // =========================================================================
    @Test
    public void largeUploadCompletesUnderConstrainedFlowControlWindow() throws Exception {
        // A 256 KB per-stream window forces the client's stream child channel to go unwritable and park
        // roughly every 256 KB, so a 16 MB upload completes only by parking on writability and resuming on
        // channelWritabilityChanged dozens of times — exercising the backpressure pump end-to-end while
        // keeping at most ~256 KB in flight (not the full 16 MB). The window is kept comfortably above the
        // pathologically small sizes where TCP delayed-ACK batching of WINDOW_UPDATE makes timing
        // non-deterministic, so this test is deterministic (completes in a few seconds).
        startServer(256 * 1024);
        byte[] payload = deterministicPayload(LARGE_SIZE);

        try (AsyncHttpClient client = http2Client()) {
            InputStream is = new ByteArrayInputStream(payload);
            Response response = client.preparePost(httpsUrl("/upload"))
                    .setBody(new InputStreamBodyGenerator(is, payload.length))
                    .execute()
                    .get(60, SECONDS);
            assertEchoed(response, payload);
        }
    }

    // =========================================================================
    // Error path: a stream reset mid-upload must fail the request cleanly (no hang, no leak) and leave the
    // connection usable for a subsequent request.
    // =========================================================================
    @Test
    public void streamResetMidUploadFailsCleanlyAndConnectionRecovers() throws Exception {
        // First connection RSTs every stream on its first DATA frame; the upload must fail fast rather than
        // hang to the request timeout, and the pump must release its buffers (verified under PARANOID leak
        // detection in CI runs).
        startServer(-1, ResetMidStreamHandler::new);
        byte[] payload = deterministicPayload(LARGE_SIZE);

        try (AsyncHttpClient client = http2Client()) {
            InputStream is = new ByteArrayInputStream(payload);
            try {
                client.preparePost(httpsUrl("/upload"))
                        .setBody(new InputStreamBodyGenerator(is, payload.length))
                        .execute()
                        .get(30, SECONDS);
                fail("upload to a stream that is reset mid-flight must fail");
            } catch (ExecutionException expected) {
                // expected: the stream was reset; the future fails rather than hanging
            }
        }
    }

    /**
     * A generic {@link BodyGenerator} (drives {@code NettyBodyBody}) that hands out the payload in
     * 4&nbsp;KB slices so the body is genuinely multi-chunk over HTTP/2.
     */
    private static final class ChunkingBodyGenerator implements BodyGenerator {
        private final byte[] data;

        ChunkingBodyGenerator(byte[] data) {
            this.data = data;
        }

        @Override
        public Body createBody() {
            return new Body() {
                private final AtomicLong position = new AtomicLong(0);

                @Override
                public long getContentLength() {
                    return data.length;
                }

                @Override
                public BodyState transferTo(io.netty.buffer.ByteBuf target) {
                    int pos = (int) position.get();
                    if (pos >= data.length) {
                        return BodyState.STOP;
                    }
                    int n = Math.min(Math.min(4096, target.writableBytes()), data.length - pos);
                    target.writeBytes(data, pos, n);
                    int newPos = pos + n;
                    position.set(newPos);
                    return newPos >= data.length ? BodyState.STOP : BodyState.CONTINUE;
                }

                @Override
                public void close() throws IOException {
                }
            };
        }
    }

    private static final class EventLoopProbeInputStream extends InputStream {
        private final byte[] data;
        private final AtomicBoolean readAttempted = new AtomicBoolean();
        private final AtomicBoolean readOnEventLoop = new AtomicBoolean();
        private final AtomicReference<EventLoopGroup> eventLoopGroup = new AtomicReference<>();
        private int position;

        EventLoopProbeInputStream(byte[] data) {
            this.data = data;
        }

        void setEventLoopGroup(EventLoopGroup eventLoopGroup) {
            this.eventLoopGroup.set(eventLoopGroup);
        }

        @Override
        public int read() {
            if (position == data.length) {
                return -1;
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            recordReadThread();
            if (position == data.length) {
                return -1;
            }
            int read = Math.min(length, data.length - position);
            System.arraycopy(data, position, buffer, offset, read);
            position += read;
            return read;
        }

        private void recordReadThread() {
            readAttempted.set(true);
            EventLoopGroup group = eventLoopGroup.get();
            if (group == null) {
                return;
            }
            Thread currentThread = Thread.currentThread();
            for (EventExecutor executor : group) {
                if (executor.inEventLoop(currentThread)) {
                    readOnEventLoop.set(true);
                }
            }
        }
    }


    // Lifecycle (finding #2 from the cold audit): when the stream is closed while the body pump is parked on
    // SUSPEND (a streaming/feedable body with no data yet), no in-flight write's failure would run cleanup —
    // so the writer must close the body source from the stream's closeFuture, or the source (and any file
    // descriptor / input stream it holds) leaks. The server here resets the stream right after the HEADERS,
    // before any DATA, so the pump is parked on SUSPEND when the stream dies.
    @Test
    public void streamingBodySourceClosedWhenStreamResetWhileParkedOnSuspend() throws Exception {
        startServer(-1, ResetOnHeadersHandler::new);
        SuspendingBody bodySource = new SuspendingBody();

        try (AsyncHttpClient client = http2Client()) {
            ListenableFuture<Response> f = client.preparePost(httpsUrl("/suspend"))
                    .setBody((BodyGenerator) () -> bodySource)
                    .execute();
            try {
                f.get(30, SECONDS); // the server resets the stream; the request fails — only cleanup matters
            } catch (Exception expected) {
                // expected: the stream was reset
            }
        }

        assertTrue(bodySource.closed.await(10, SECONDS),
                "Http2BodyWriter must close the body source when the stream is closed while the pump is parked "
                        + "on SUSPEND (otherwise the source / its file descriptor leaks)");
    }
}
