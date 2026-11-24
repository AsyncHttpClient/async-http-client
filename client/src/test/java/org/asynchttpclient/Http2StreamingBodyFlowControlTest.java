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
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.FilePart;
import org.asynchttpclient.request.body.multipart.InputStreamPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.CRC32;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
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
    private Http2FrameLogger serverFrameLogger;

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
        if (serverFrameLogger != null) {
            codecBuilder.frameLogger(serverFrameLogger);
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
        serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
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
    // NettyInputStreamBody: a stream with more bytes than its declared length, or no end after it.
    // =========================================================================
    @Test
    public void inputStreamBodyLongerThanItsDeclaredLengthSendsOnlyThatLengthOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(SMALL_SIZE);

        try (AsyncHttpClient client = http2Client()) {
            // The pattern does not depend on the size, so the payload is a prefix of the stream.
            InputStream is = new ByteArrayInputStream(deterministicPayload(SMALL_SIZE + 3));
            Response response = client.preparePost(httpsUrl("/small"))
                    .setBody(new InputStreamBodyGenerator(is, payload.length))
                    .execute()
                    .get(30, SECONDS);
            assertEchoed(response, payload);
            assertEquals(3, is.available(), "the body read past its declared length");
        }
    }

    @Test
    public void inputStreamBodyIsNotReadPastItsDeclaredLengthOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(SMALL_SIZE);
        AtomicBoolean readPastDeclaredLength = new AtomicBoolean();

        try (AsyncHttpClient client = http2Client()) {
            // A socket-backed stream has nothing more to give, so a read for its end would block until it ends.
            InputStream is = new ByteArrayInputStream(payload) {
                @Override
                public synchronized int read(byte[] b, int off, int len) {
                    if (available() == 0) {
                        readPastDeclaredLength.set(true);
                    }
                    return super.read(b, off, len);
                }
            };
            Response response = client.preparePost(httpsUrl("/small"))
                    .setBody(new InputStreamBodyGenerator(is, payload.length))
                    .execute()
                    .get(30, SECONDS);
            assertEchoed(response, payload);
            assertFalse(readPastDeclaredLength.get(), "the body read past its declared length");
        }
    }

    @Test
    public void inputStreamBodyShorterThanItsDeclaredLengthFailsWithItsErrorOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(SMALL_SIZE);
        AtomicInteger retries = new AtomicInteger();

        try (AsyncHttpClient client = replayingHttp2Client()) {
            // Supports mark/reset, so a replay could send it again, and fail again.
            InputStream is = new ByteArrayInputStream(payload, 0, 2);
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(httpsUrl("/small"))
                    .setBody(new InputStreamBodyGenerator(is, payload.length))
                    .execute(retryCounting(retries))
                    .get(30, SECONDS));
            EOFException cause = assertInstanceOf(EOFException.class, thrown.getCause());
            assertEquals("InputStream body ended after 2 of its 5 declared bytes", cause.getMessage());
            assertEquals(0, retries.get(), "a request whose body failed was replayed");
        }
    }

    @Test
    public void streamPartShorterThanItsDeclaredLengthFailsWithItsErrorOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(SMALL_SIZE);
        AtomicInteger retries = new AtomicInteger();

        try (AsyncHttpClient client = replayingHttp2Client()) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(httpsUrl("/small"))
                    .addBodyPart(new InputStreamPart("file", new ByteArrayInputStream(payload, 0, 2), "file.bin", payload.length))
                    .execute(retryCounting(retries))
                    .get(30, SECONDS));
            EOFException cause = assertInstanceOf(EOFException.class, thrown.getCause());
            assertEquals("Multipart InputStream body part 'file' ended after 2 of its 5 declared bytes", cause.getMessage());
            assertEquals(0, retries.get(), "a request whose body failed was replayed");
        }
    }

    // Replays a request after any IOException, up to the retry limit.
    private AsyncHttpClient replayingHttp2Client() {
        IOExceptionFilter replay = new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                return new FilterContext.FilterContextBuilder<>(ctx.getAsyncHandler(), ctx.getRequest())
                        .replayRequest(true)
                        .build();
            }
        };
        return asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setMaxConnectionsPerHost(1)
                .setMaxRequestRetry(3)
                .addIOExceptionFilter(replay)
                .setRequestTimeout(Duration.ofSeconds(30)));
    }

    private static AsyncCompletionHandlerBase retryCounting(AtomicInteger retries) {
        return new AsyncCompletionHandlerBase() {
            @Override
            public void onRetry() {
                retries.incrementAndGet();
            }
        };
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
                public BodyState transferTo(ByteBuf target) {
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

    // =========================================================================
    // A file that changes after the request took its length, which the content-length header announces: the
    // body must keep that length, or the request must fail.
    // =========================================================================
    private interface FileChange {
        void apply(File file) throws IOException;
    }

    private static final FileChange GROW =
            f -> Files.write(f.toPath(), new byte[32 * 1024], StandardOpenOption.APPEND);

    private static FileChange truncateTo(long size) {
        return f -> {
            try (FileChannel channel = FileChannel.open(f.toPath(), StandardOpenOption.WRITE)) {
                channel.truncate(size);
            }
        };
    }

    /**
     * Changes the file on the first attempt, once the request is built and before its body is written, and records
     * that it did, and the announced length. Counts the attempts. An exception thrown here is only logged, so the
     * tests check what was recorded.
     */
    private static final class ChangeOnSend extends AsyncCompletionHandlerBase {
        private final File file;
        private final FileChange change;
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicBoolean changed = new AtomicBoolean();
        final AtomicLong declared = new AtomicLong(-1);

        ChangeOnSend(File file, FileChange change) {
            this.file = file;
            this.change = change;
        }

        @Override
        public void onRequestSend(NettyRequest request) {
            if (attempts.incrementAndGet() > 1) {
                return;
            }
            try {
                change.apply(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            changed.set(true);
            String contentLength = request.getHttpRequest().headers().get("content-length");
            if (contentLength != null) {
                declared.set(Long.parseLong(contentLength));
            }
        }
    }

    private static File tempFile(byte[] content) throws IOException {
        File tmp = File.createTempFile("ahc-h2-file-change", ".bin");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), content);
        return tmp;
    }

    /**
     * Counts the request streams the client ended with END_STREAM on a DATA frame, as frames arrive and before the
     * server's own content-length check, which resets a stream that ends short of its content-length.
     */
    private static final class EndedRequestStreams extends Http2FrameLogger {
        private final AtomicInteger count = new AtomicInteger();

        EndedRequestStreams() {
            super(LogLevel.DEBUG);
        }

        @Override
        public void logData(Direction direction, ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
                            boolean endStream) {
            if (direction == Direction.INBOUND && endStream) {
                count.incrementAndGet();
            }
            super.logData(direction, ctx, streamId, data, padding, endStream);
        }
    }

    // NettyFileBody stopped a file body at its length before this check too. This keeps it so.
    @Test
    public void fileBodyThatGrowsSendsItsLengthOverHttp2() throws Exception {
        startServer(-1);
        byte[] payload = deterministicPayload(64 * 1024);
        File tmp = tempFile(payload);
        ChangeOnSend onSend = new ChangeOnSend(tmp, GROW);
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/upload"))
                    .setBody(tmp)
                    .execute(onSend)
                    .get(60, SECONDS);
            assertTrue(onSend.changed.get(), "the file did not grow");
            assertEchoed(response, payload);
            assertEquals(1, onSend.attempts.get(), "sent again");
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    // The request fails with the file's error, and the client does not end the stream as if the shorter body were
    // complete. A filter that replays a request after an IOException does not get to send the shorter file again
    // under its new length: Http2BodyWriter fails the request with the file's error before the stream closes.
    @Test
    public void fileBodyThatShrinksFailsTheRequestOverHttp2() throws Exception {
        EndedRequestStreams ended = new EndedRequestStreams();
        serverFrameLogger = ended;
        startServer(-1);
        File tmp = tempFile(deterministicPayload(64 * 1024));
        ChangeOnSend onSend = new ChangeOnSend(tmp, truncateTo(1000));
        try (AsyncHttpClient client = replayingHttp2Client()) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(httpsUrl("/upload"))
                    .setBody(tmp)
                    .execute(onSend)
                    .get(60, SECONDS));
            assertTrue(onSend.changed.get(), "the file did not shrink");
            EOFException cause = assertInstanceOf(EOFException.class, thrown.getCause());
            assertEquals("File body ended after 1000 of its 65536 bytes", cause.getMessage());
            assertEquals(0, ended.count.get(), "the client ended the stream as if the shorter body were complete");
            assertEquals(1, onSend.attempts.get(), "sent again");
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    @Test
    public void filePartThatGrowsSendsItsDeclaredLengthOverHttp2() throws Exception {
        startServer(-1);
        File tmp = tempFile(deterministicPayload(64 * 1024));
        ChangeOnSend onSend = new ChangeOnSend(tmp, GROW);
        try (AsyncHttpClient client = http2Client()) {
            Response response = client.preparePost(httpsUrl("/upload"))
                    .addBodyPart(new FilePart("file", tmp))
                    .execute(onSend)
                    .get(60, SECONDS);
            assertTrue(onSend.changed.get(), "the file did not grow");
            assertTrue(onSend.declared.get() > 64 * 1024, "announced length: " + onSend.declared.get());
            assertEquals(200, response.getStatusCode());
            assertEquals(onSend.declared.get(), parseLongHeader(response, "x-received-length"),
                    "the server must receive exactly the announced length");
            assertEquals(1, onSend.attempts.get(), "sent again");
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    // As for a file body.
    @Test
    public void filePartThatShrinksFailsTheRequestOverHttp2() throws Exception {
        EndedRequestStreams ended = new EndedRequestStreams();
        serverFrameLogger = ended;
        startServer(-1);
        File tmp = tempFile(deterministicPayload(64 * 1024));
        ChangeOnSend onSend = new ChangeOnSend(tmp, truncateTo(1000));
        try (AsyncHttpClient client = replayingHttp2Client()) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.preparePost(httpsUrl("/upload"))
                    .addBodyPart(new FilePart("file", tmp))
                    .execute(onSend)
                    .get(60, SECONDS));
            assertTrue(onSend.changed.get(), "the file did not shrink");
            EOFException cause = assertInstanceOf(EOFException.class, thrown.getCause());
            assertEquals("Multipart file part 'file' ended after 1000 of its 65536 bytes", cause.getMessage());
            assertEquals(0, ended.count.get(), "the client ended the stream as if the shorter body were complete");
            assertEquals(1, onSend.attempts.get(), "sent again");
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }
}
