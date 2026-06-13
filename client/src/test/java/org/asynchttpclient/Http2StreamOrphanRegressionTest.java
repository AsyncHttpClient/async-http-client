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
import org.asynchttpclient.netty.request.NettyRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression tests for the RESIDUAL HTTP/2 silent-orphan / blast-radius paths discovered while
 * verifying the fix for Issue #2160 — paths NOT covered by {@link Http2MultiplexBugRegressionTest}.
 * <p>
 * Each test FAILS on the first-pass fix (commit that only implemented Http2Handler.handleChannelInactive)
 * and PASSES once the follow-up fixes ship:
 * <ol>
 *   <li>A request parked in {@code Http2ConnectionState.pendingOpeners} (waiting for a stream slot)
 *       must be failed when the parent connection drops — otherwise it hangs to the request timeout.</li>
 *   <li>A single stream's RST_STREAM / inactive must NOT close the parent connection and fail its
 *       sibling multiplexed streams (RFC 7540 §6.4 — RST_STREAM is stream-scoped).</li>
 *   <li>A stream slot acquired in writeHttp2Request must be released if the post-open hooks
 *       (onRequestSend / sendHttp2Frames) throw — otherwise the slot leaks and wedges the connection.</li>
 * </ol>
 * Each test uses a request timeout LONGER than its {@code get()} timeout so a hang to the request
 * timer is observable as a {@link TimeoutException} rather than a fast failure.
 */
public class Http2StreamOrphanRegressionTest {

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
        serverChildChannels = new DefaultChannelGroup("h2-orphan-regression", GlobalEventExecutor.INSTANCE);
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

    /** Responds 200 OK with an empty body to every complete request. */
    private static class SimpleOkHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            boolean endStream = (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream())
                    || (msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream());
            if (endStream) {
                Http2Headers responseHeaders = new DefaultHttp2Headers().status("200");
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders, true));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    // =========================================================================
    // BLOCKER 1: pendingOpeners orphaned on connection drop (#2160 recurrence)
    // =========================================================================
    @Test
    public void queuedRequestsFailWhenConnectionDropsWhileWaitingForStreamSlot() throws Exception {
        startServerWithHandler(HoldOpenHandler::new);

        // maxConcurrentStreams=1 => 1 active stream + (burst-1) parked in pendingOpeners.
        // Request timeout (30s) >> get() timeout (6s): a hang to the request timer is observable.
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setHttp2MaxConcurrentStreams(1)
                .setMaxConnections(1)
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(30)))) {

            int burst = 6;
            List<CompletableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                futures.add(client.prepareGet(httpsUrl("/hold")).execute().toCompletableFuture());
            }

            // Let the single active stream establish and the rest queue in pendingOpeners.
            Thread.sleep(1000);

            long killTime = System.currentTimeMillis();
            serverChildChannels.close().sync();

            int orphaned = 0;
            for (CompletableFuture<Response> f : futures) {
                try {
                    f.get(6, SECONDS);
                } catch (ExecutionException e) {
                    // failed fast = acceptable
                } catch (TimeoutException te) {
                    orphaned++; // hung past 6s -> only the request timer would rescue it = #2160
                }
            }
            long elapsed = System.currentTimeMillis() - killTime;

            assertEquals(0, orphaned,
                    "ORPHAN GAP: " + orphaned + "/" + burst + " futures queued in pendingOpeners hung after "
                            + "connection drop (elapsed " + elapsed + "ms) — they must be failed on parent close.");
        }
    }

    // =========================================================================
    // BLOCKER 2: a single RST_STREAM must not tear down sibling multiplexed streams
    // =========================================================================
    @Test
    public void rstStreamDoesNotFailSiblingStreams() throws Exception {
        // Server RSTs the FIRST stream to complete its request, holds all others open.
        AtomicInteger streamCounter = new AtomicInteger(0);
        startServerWithHandler(() -> new SimpleChannelInboundHandler<Object>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                boolean endStream = (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream())
                        || (msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream());
                if (endStream && streamCounter.incrementAndGet() == 1) {
                    ctx.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.INTERNAL_ERROR));
                }
                // other streams: hold open
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

            int numRequests = 4;
            List<CompletableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < numRequests; i++) {
                futures.add(client.prepareGet(httpsUrl("/r" + i)).execute().toCompletableFuture());
                Thread.sleep(50); // stagger so the reset stream is clearly "stream 1" server-side
            }

            // Let the RST land; the rest stay held open.
            Thread.sleep(1500);

            int stillPending = 0;
            for (CompletableFuture<Response> f : futures) {
                if (!f.isDone()) {
                    stillPending++;
                }
            }

            // RFC-correct: exactly the reset stream fails; the other 3 remain open on a live connection.
            assertEquals(numRequests - 1, stillPending,
                    "RST_STREAM BLAST RADIUS: one RST_STREAM closed the whole H2 connection — only "
                            + stillPending + "/" + numRequests + " sibling streams survived (expected "
                            + (numRequests - 1) + "). RST_STREAM must be stream-scoped (RFC 7540 §6.4).");
        }
    }

    // =========================================================================
    // BLOCKER 3: stream slot must be released if a post-open hook throws
    // =========================================================================
    @Test
    public void streamSlotReleasedWhenRequestSendHandlerCrashes() throws Exception {
        startServerWithHandler(SimpleOkHandler::new);

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setHttp2MaxConcurrentStreams(1)
                .setMaxConnections(1)
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(8)))) {

            // Establish the H2 connection.
            assertEquals(200, client.prepareGet(httpsUrl("/ok")).execute().get(10, SECONDS).getStatusCode());

            // A request whose handler throws in onRequestSend — AFTER the single stream slot is
            // acquired in writeHttp2Request — drives openHttp2Stream's catch path. Without the fix
            // the slot is never released, pinning activeStreams at max=1.
            try {
                client.prepareGet(httpsUrl("/ok"))
                        .execute(new AsyncCompletionHandlerBase() {
                            @Override
                            public void onRequestSend(NettyRequest request) {
                                throw new RuntimeException("boom in onRequestSend");
                            }
                        })
                        .get(10, SECONDS);
                fail("crashing request should have failed");
            } catch (ExecutionException expected) {
                // expected — the crashing request fails
            }

            // A subsequent normal request must still succeed. With a leaked slot, activeStreams stays
            // pinned at 1 and this request queues in pendingOpeners forever, timing out at 8s.
            Response r = client.prepareGet(httpsUrl("/ok")).execute().get(8, SECONDS);
            assertEquals(200, r.getStatusCode(),
                    "Stream slot leaked when onRequestSend threw: the connection is wedged and "
                            + "subsequent requests can never acquire a stream slot.");
        }
    }

    // =========================================================================
    // SANITY GUARD: the original fix must keep working — established streams fail fast on drop.
    // =========================================================================
    @Test
    public void establishedStreamsFailFastOnConnectionDrop() throws Exception {
        startServerWithHandler(HoldOpenHandler::new);

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setHttp2MaxConcurrentStreams(1000) // every request becomes an active stream (no queuing)
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(30)))) {

            int numRequests = 8;
            List<CompletableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < numRequests; i++) {
                futures.add(client.prepareGet(httpsUrl("/hold")).execute().toCompletableFuture());
            }

            Thread.sleep(800);
            long killTime = System.currentTimeMillis();
            serverChildChannels.close().sync();

            int orphaned = 0;
            for (CompletableFuture<Response> f : futures) {
                try {
                    f.get(6, SECONDS);
                } catch (ExecutionException e) {
                    // fast failure ok
                } catch (TimeoutException te) {
                    orphaned++;
                }
            }
            long elapsed = System.currentTimeMillis() - killTime;
            assertEquals(0, orphaned,
                    orphaned + "/" + numRequests + " established-stream futures hung past 6s (elapsed "
                            + elapsed + "ms) after connection drop.");
        }
    }
}
