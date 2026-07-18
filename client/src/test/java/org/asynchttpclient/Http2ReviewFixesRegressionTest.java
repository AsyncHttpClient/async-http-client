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
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
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
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Http2ConnectionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression tests for the new HTTP/2 defects found in the full code review on top of the Issue #2160 fix:
 * <ol>
 *   <li><b>:authority drops virtualHost</b> — over HTTP/2 the {@code :authority} pseudo-header was built from
 *       {@code hostHeader(uri)}, silently discarding {@code setVirtualHost(...)} (which the HTTP/1.1 path
 *       honours). {@link #authorityPseudoHeaderHonoursVirtualHost()}.</li>
 *   <li><b>Connection-wide blast radius</b> — a throwing {@code AsyncHandler} callback on one stream routed
 *       through {@code finishUpdate(close=true)} and tore down the whole parent connection (and every sibling
 *       multiplexed request). {@link #throwingAsyncHandlerDoesNotCloseSharedConnection()}.</li>
 *   <li><b>Replay leaks the stream slot</b> — an IOException-filter replay on an HTTP/2 stream used the
 *       HTTP/1.1 drain-and-pool path, which waits for a {@code LastHttpContent} that never comes on H2, so the
 *       stream slot leaked. {@link #ioExceptionFilterReplayReleasesHttp2StreamSlot()}.</li>
 * </ol>
 * Uses a real Netty HTTP/2 server, mirroring {@link Http2ResidualFixesRegressionTest}.
 */
public class Http2ReviewFixesRegressionTest {

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
        serverChildChannels = new DefaultChannelGroup("h2-review-regression", GlobalEventExecutor.INSTANCE);
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

    /** Responds with an empty 200 (HEADERS, endStream) to every request. */
    private static class OkHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (isEndStream(msg)) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /** Responds 200 with a small body to every request. */
    private static class BodyOkHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (isEndStream(msg)) {
                ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                        Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /** Captures the request's :authority pseudo-header and returns it as the response body. */
    private static class AuthorityEchoHandler extends SimpleChannelInboundHandler<Object> {
        private String authority = "<none>";

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                CharSequence a = ((Http2HeadersFrame) msg).headers().authority();
                if (a != null) {
                    authority = a.toString();
                }
            }
            if (isEndStream(msg)) {
                ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                        Unpooled.copiedBuffer(authority, StandardCharsets.UTF_8), true));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
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

    private static Channel singleHttp2Connection(AsyncHttpClient client) throws Exception {
        java.util.Collection<Channel> conns = http2Connections(client);
        assertEquals(1, conns.size(), "expected exactly one pooled HTTP/2 connection, found: " + conns);
        return conns.iterator().next();
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
    // :authority must carry the configured virtualHost (was hostHeader(uri))
    // =========================================================================
    @Test
    public void authorityPseudoHeaderHonoursVirtualHost() throws Exception {
        startServerWithHandler(AuthorityEchoHandler::new);

        // Endpoint identification is disabled so the virtualHost (which drives SNI/hostname verification)
        // can differ from the cert CN=localhost without failing the handshake.
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setDisableHttpsEndpointIdentificationAlgorithm(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(10)))) {

            Response response = client.prepareGet(httpsUrl("/"))
                    .setVirtualHost("vhost.example:1234")
                    .execute().get(10, SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("vhost.example:1234", response.getResponseBody(),
                    "HTTP/2 :authority must carry the configured virtualHost (mirroring the HTTP/1.1 Host "
                            + "header), not hostHeader(uri) which drops the virtual host.");
        }
    }

    // =========================================================================
    // A throwing AsyncHandler on one stream must NOT close the shared parent connection
    // =========================================================================
    @Test
    public void throwingAsyncHandlerDoesNotCloseSharedConnection() throws Exception {
        startServerWithHandler(BodyOkHandler::new);

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setMaxConnectionsPerHost(1) // force every request onto the SAME multiplexed connection
                .setRequestTimeout(Duration.ofSeconds(10)))) {

            // Warm up the single HTTP/2 connection.
            assertEquals(200, client.prepareGet(httpsUrl("/ok")).execute().get(10, SECONDS).getStatusCode());
            Channel connection = singleHttp2Connection(client);
            assertTrue(connection.isActive());

            // A request whose AsyncHandler throws in onStatusReceived. The processing error must fail ONLY
            // this stream — not route through finishUpdate(close=true) and tear down the parent connection.
            AsyncHandler<Object> throwing = new AsyncHandler<Object>() {
                @Override
                public State onStatusReceived(HttpResponseStatus responseStatus) {
                    throw new RuntimeException("boom in onStatusReceived");
                }

                @Override
                public State onHeadersReceived(io.netty.handler.codec.http.HttpHeaders headers) {
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                    return State.CONTINUE;
                }

                @Override
                public void onThrowable(Throwable t) {
                }

                @Override
                public Object onCompleted() {
                    return null;
                }
            };

            try {
                client.prepareGet(httpsUrl("/boom")).execute(throwing).get(10, SECONDS);
                fail("request with a throwing onStatusReceived should have failed");
            } catch (ExecutionException expected) {
                // expected — the stream is failed
            }

            // The stream slot is released (stream-scoped failure, no leak)...
            long deadline = System.currentTimeMillis() + 3000;
            while (activeStreams(client) != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(0, activeStreams(client), "the failed stream must release its slot");

            // ...and the shared connection is still alive and REUSED for the next request. With the blast-radius
            // bug (finishUpdate close=true) the connection would have been closed and a new one opened here.
            assertEquals(200, client.prepareGet(httpsUrl("/ok2")).execute().get(10, SECONDS).getStatusCode());
            assertSame(connection, singleHttp2Connection(client),
                    "a throwing AsyncHandler on one stream must not close the shared HTTP/2 connection");
            assertTrue(connection.isActive(), "shared HTTP/2 connection must remain active");
        }
    }

    // =========================================================================
    // IOException-filter replay on an HTTP/2 stream must release the stream slot (not leak it)
    // =========================================================================
    @Test
    public void ioExceptionFilterReplayReleasesHttp2StreamSlot() throws Exception {
        startServerWithHandler(OkHandler::new);

        final AtomicBoolean replayed = new AtomicBoolean(false);
        IOExceptionFilter replayOnce = new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                // Replay ONLY for our synthetic IOException — not for the benign ChannelClosedException that
                // every completed single-use H2 stream fires on close (which would otherwise consume this
                // one-shot replay before the synthetic exception arrives).
                java.io.IOException io = ctx.getIOException();
                if (io != null && io.getMessage() != null && io.getMessage().contains("synthetic")
                        && replayed.compareAndSet(false, true)) {
                    return new FilterContext.FilterContextBuilder<>(ctx.getAsyncHandler(), ctx.getRequest())
                            .replayRequest(true).build();
                }
                return ctx;
            }
        };

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setMaxConnectionsPerHost(1)
                .setMaxRequestRetry(3)
                .addIOExceptionFilter(replayOnce)
                .setRequestTimeout(Duration.ofSeconds(10)))) {

            // Warm up the single HTTP/2 connection.
            assertEquals(200, client.prepareGet(httpsUrl("/ok")).execute().get(10, SECONDS).getStatusCode());

            // This handler throws an IOException the FIRST time onStatusReceived fires (before the response
            // completes, so the request is still replay-eligible). Netty routes it through
            // Http2Handler.handleRead's catch -> the IOException filter -> replayRequest on the H2 stream. The
            // replay must CLOSE that single-use stream (releasing its slot); the old code drain-and-pooled it,
            // waiting for a LastHttpContent that never arrives on H2, leaking the slot.
            final AtomicBoolean thrownOnce = new AtomicBoolean(false);
            AsyncHandler<Integer> handler = new AsyncHandler<Integer>() {
                private volatile int status;

                @Override
                public State onStatusReceived(HttpResponseStatus responseStatus) throws IOException {
                    if (thrownOnce.compareAndSet(false, true)) {
                        throw new IOException("synthetic IOException to trigger an HTTP/2 replay");
                    }
                    status = responseStatus.getStatusCode();
                    return State.CONTINUE;
                }

                @Override
                public State onHeadersReceived(io.netty.handler.codec.http.HttpHeaders headers) {
                    return State.CONTINUE;
                }

                @Override
                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
                    return State.CONTINUE;
                }

                @Override
                public void onThrowable(Throwable t) {
                }

                @Override
                public Integer onCompleted() {
                    return status;
                }
            };

            int status = client.prepareGet(httpsUrl("/replay")).execute(handler).get(10, SECONDS);
            assertEquals(200, status, "the request must succeed on the replay");
            assertTrue(replayed.get(), "the IOException filter must have triggered a replay");

            // The replayed-away (first) stream must have released its slot. With the drain-and-pool leak the
            // first stream channel is never closed, so its slot stays acquired forever (activeStreams == 1).
            long deadline = System.currentTimeMillis() + 3000;
            while (activeStreams(client) != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(0, activeStreams(client),
                    "IOException-filter replay on an HTTP/2 stream leaked the stream slot (the single-use "
                            + "stream channel was drain-and-pooled instead of closed).");
        }
    }
}
