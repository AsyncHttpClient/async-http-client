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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Http2ConnectionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression tests for the residual HTTP/2 defects found while reviewing the Issue #2160 fix:
 * <ol>
 *   <li><b>RST_STREAM error code lost</b> — Netty delivers RST_STREAM to the stream child channel as a
 *       user event, so the (channelRead-dispatched) reset handler was dead and the caller only ever saw a
 *       generic "closed unexpectedly". {@link #rstStreamErrorCodeReachesCaller()}.</li>
 *   <li><b>Stream-slot leak on a non-IOException</b> — a {@code DecompressionException} (corrupt gzip)
 *       completes the future via {@code exceptionCaught} before {@code finishUpdate} runs, so the slot was
 *       never released and a single-slot connection wedged. {@link #corruptGzipResponseReleasesStreamSlotAndFailsCleanly()}.</li>
 *   <li><b>Request-body leak / orphan on queued requests</b> — POST bodies parked in {@code pendingOpeners}
 *       when the connection drops must fail fast and have their body released.
 *       {@link #queuedPostBodiesFailFastOnConnectionDrop()}.</li>
 * </ol>
 * Uses a real Netty HTTP/2 server, mirroring {@link Http2StreamOrphanRegressionTest}.
 */
public class Http2ResidualFixesRegressionTest {

    private NioEventLoopGroup serverGroup;
    private Channel serverChannel;
    private ChannelGroup serverChildChannels;
    private SslContext serverSslCtx;
    private int serverPort;

    @BeforeEach
    public void startServer() throws Exception {
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
        serverChildChannels = new DefaultChannelGroup("h2-residual-regression", GlobalEventExecutor.INSTANCE);
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
            serverGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync();
        }
        ReferenceCountUtil.release(serverSslCtx);
    }

    private String httpsUrl(String path) {
        return "https://localhost:" + serverPort + path;
    }

    private void startServerWithHandler(StreamHandlerFactory factory) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        serverChildChannels.add(ch);
                        ch.pipeline()
                                .addLast("ssl", serverSslCtx.newHandler(ch.alloc()))
                                .addLast(Http2FrameCodecBuilder.forServer().build())
                                .addLast(new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
                                    @Override
                                    protected void initChannel(Http2StreamChannel streamCh) {
                                        streamCh.pipeline().addLast(factory.create());
                                    }
                                }));
                    }
                });

        serverChannel = b.bind(0).sync().channel();
        serverPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @FunctionalInterface
    private interface StreamHandlerFactory {
        SimpleChannelInboundHandler<Object> create();
    }

    private static boolean isEndStream(Object msg) {
        return (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream())
                || (msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream());
    }

    /** Accepts the request but never responds — holds the stream open. */
    private static class HoldOpenHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            // hold open
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /** "/corrupt" -> 200 with content-encoding: gzip but a NON-gzip body; anything else -> empty 200. */
    private static class PathAwareHandler extends SimpleChannelInboundHandler<Object> {
        private String path = "";

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                CharSequence p = ((Http2HeadersFrame) msg).headers().path();
                if (p != null) {
                    path = p.toString();
                }
            }
            if (!isEndStream(msg)) {
                return;
            }
            if (path.contains("corrupt")) {
                ctx.write(new DefaultHttp2HeadersFrame(
                        new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false));
                ByteBuf notGzip = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
                ctx.writeAndFlush(new DefaultHttp2DataFrame(notGzip, true));
            } else {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private static String causeChainMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(" | ");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Collection<Channel> http2Connections(AsyncHttpClient client) throws Exception {
        ChannelManager cm = ((DefaultAsyncHttpClient) client).channelManager();
        java.lang.reflect.Field f = ChannelManager.class.getDeclaredField("http2Connections");
        f.setAccessible(true);
        // The registry is grouped by per-host base key (issue #2214): Map<baseKey, Map<fullKey, Channel>>.
        // Flatten the inner maps to recover the flat collection of connections this test expects.
        return ((java.util.Map<Object, ? extends java.util.Map<Object, Channel>>) f.get(cm)).values().stream()
                .flatMap(inner -> inner.values().stream())
                .collect(java.util.stream.Collectors.toList());
    }

    private static int activeStreams(AsyncHttpClient client) throws Exception {
        int total = 0;
        for (Channel ch : http2Connections(client)) {
            Http2ConnectionState st = ch.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
            if (st != null) {
                total += st.getActiveStreams();
            }
        }
        return total;
    }

    // =========================================================================
    // RST_STREAM error code must reach the caller (was dead-code: user-event dispatch)
    // =========================================================================
    @Test
    public void rstStreamErrorCodeReachesCaller() throws Exception {
        startServerWithHandler(() -> new SimpleChannelInboundHandler<Object>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                if (isEndStream(msg)) {
                    ctx.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.REFUSED_STREAM));
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                ctx.close();
            }
        });

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(30)))) {

            long expectedCode = Http2Error.REFUSED_STREAM.code();
            try {
                client.prepareGet(httpsUrl("/rst")).execute().get(10, SECONDS);
                fail("RST_STREAM request should have failed");
            } catch (ExecutionException e) {
                String msg = causeChainMessage(e);
                assertTrue(msg.contains("reset by server") && msg.contains("error code: " + expectedCode),
                        "RST_STREAM error code lost: caller saw \"" + msg + "\" but expected the server's "
                                + "REFUSED_STREAM code " + expectedCode
                                + " (the reset handler was dead before the userEventTriggered fix).");
            }
        }
    }

    // =========================================================================
    // DecompressionException must release the stream slot (not wedge the connection)
    // =========================================================================
    @Test
    public void corruptGzipResponseReleasesStreamSlotAndFailsCleanly() throws Exception {
        startServerWithHandler(PathAwareHandler::new);

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setEnableAutomaticDecompression(true)
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(10)))) {

            // Establish the (single) H2 connection.
            assertEquals(200, client.prepareGet(httpsUrl("/ok")).execute().get(10, SECONDS).getStatusCode());

            // A corrupt gzip body makes the decompressor throw a DecompressionException, which Netty routes
            // through exceptionCaught. That path aborts (completes) the future BEFORE streamFailed/finishUpdate,
            // so finishUpdate's slot release is skipped — the slot can only be released by the stream channel's
            // closeFuture. Without that binding the slot leaks (activeStreams stays > 0), accumulating one per
            // failure until the connection exhausts the server's advertised SETTINGS_MAX_CONCURRENT_STREAMS.
            try {
                client.prepareGet(httpsUrl("/corrupt")).execute().get(10, SECONDS);
                fail("corrupt gzip response should have failed the request");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof DecompressionException,
                        "expected a clean DecompressionException, got: " + expected.getCause());
            }

            // The decompression failure must NOT leak the stream slot.
            long deadline = System.currentTimeMillis() + 3000;
            while (activeStreams(client) != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(0, activeStreams(client),
                    "Stream slot leaked after a DecompressionException: exceptionCaught completed the future "
                            + "before finishUpdate, so only the stream channel's closeFuture can release the slot.");

            // ...and the connection stays usable for further requests.
            assertEquals(200, client.prepareGet(httpsUrl("/ok")).execute().get(10, SECONDS).getStatusCode());
        }
    }

    // =========================================================================
    // Queued POST(body) requests must fail fast (and release their body) on connection drop
    // =========================================================================
    @Test
    public void queuedPostBodiesFailFastOnConnectionDrop() throws Exception {
        startServerWithHandler(HoldOpenHandler::new);

        int burst = 6;
        // One caller-owned ByteBuf shared across the burst (refCnt 1). Each send takes a retained duplicate, so
        // a queued request that the connection drop must fail is also holding one outstanding reference. Once
        // every request has terminated (and the client is closed), all those duplicates must have been
        // released, returning the buffer to the caller's single reference — proving failPendingOpeners released
        // each queued body and did not leak it. Reverting that release leaves the buffer above refCnt 1.
        ByteBuf body = Unpooled.buffer().writeBytes(new byte[4096]);
        assertEquals(1, body.refCnt());

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setHttp2MaxConcurrentStreams(1)
                .setMaxConnections(1)
                .setMaxConnectionsPerHost(1)
                .setMaxRequestRetry(0)
                .setRequestTimeout(Duration.ofSeconds(30)))) {

            List<CompletableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                futures.add(client.preparePost(httpsUrl("/p" + i)).setBody(body).execute().toCompletableFuture());
            }

            // 1 active stream, the rest parked in pendingOpeners holding their request bodies.
            Thread.sleep(1000);

            long killTime = System.currentTimeMillis();
            serverChildChannels.close().sync();

            int orphaned = 0;
            for (CompletableFuture<Response> f : futures) {
                try {
                    f.get(6, SECONDS);
                } catch (ExecutionException e) {
                    // fast failure = acceptable
                } catch (TimeoutException te) {
                    orphaned++;
                }
            }
            long elapsed = System.currentTimeMillis() - killTime;
            assertEquals(0, orphaned,
                    orphaned + "/" + burst + " queued POST(body) requests hung past 6s (elapsed " + elapsed
                            + "ms) after the connection dropped — they must be failed (and their body released) "
                            + "by failPendingOpeners.");
        }

        // Client closed: every request (active + queued) has terminated, so every retained duplicate of the
        // body must have been released — back to the caller's single reference. A leaked queued body shows here.
        assertEquals(1, body.refCnt(),
                "queued POST request bodies leaked on connection drop: the caller-owned buffer should be back at "
                        + "refCnt 1 once all requests have terminated and the client has closed");
        body.release();
        assertEquals(0, body.refCnt());
    }
}
