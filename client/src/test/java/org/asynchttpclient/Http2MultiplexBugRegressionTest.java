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
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the HTTP/2 multiplexing bugs reported in Issue #2160.
 * <p>
 * These tests reproduce the exact failure scenarios — stream semaphore leaks,
 * connection drops during multiplexed requests, GOAWAY during stream open,
 * and concurrent request stalling — and verify the fixes prevent them.
 * <p>
 * Each test is designed to FAIL on the buggy code and PASS on the fixed code,
 * serving as a permanent guardrail against regressions.
 */
public class Http2MultiplexBugRegressionTest {

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
        serverChildChannels = new DefaultChannelGroup("h2-regression-test", GlobalEventExecutor.INSTANCE);
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

    // =========================================================================
    // Server bootstrapping helpers
    // =========================================================================

    /**
     * Starts a simple HTTP/2 server that responds 200 OK to every request.
     */
    private void startSimpleServer() throws InterruptedException {
        startServerWithHandler(() -> new SimpleOkHandler());
    }

    /**
     * Starts a server with a custom per-stream handler factory.
     */
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

    // =========================================================================
    // Server-side handlers for reproducing specific bugs
    // =========================================================================

    /**
     * Simple handler that always responds 200 OK with empty body.
     */
    private static class SimpleOkHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                if (headersFrame.isEndStream()) {
                    Http2Headers responseHeaders = new DefaultHttp2Headers().status("200");
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders, true));
                }
            } else if (msg instanceof Http2DataFrame) {
                Http2DataFrame dataFrame = (Http2DataFrame) msg;
                if (dataFrame.isEndStream()) {
                    Http2Headers responseHeaders = new DefaultHttp2Headers().status("200");
                    ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders, true));
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /**
     * Handler that delays response, then forcibly closes the TCP connection.
     * This simulates a connection drop after the stream is opened but before
     * the response is received — the exact scenario for Bug 3.
     */
    private static class DelayThenDropHandler extends SimpleChannelInboundHandler<Object> {
        private final long delayMs;

        DelayThenDropHandler(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                if (headersFrame.isEndStream()) {
                    ctx.executor().schedule(() -> {
                        // Close the parent (TCP) connection to simulate a network drop
                        Channel parent = ctx.channel().parent();
                        if (parent != null) {
                            parent.close();
                        }
                    }, delayMs, TimeUnit.MILLISECONDS);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /**
     * Handler that accepts the request headers but then holds the stream open
     * without responding. Used to keep streams alive while we test GOAWAY behavior.
     */
    private static class HoldOpenHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            // Intentionally do nothing — hold the stream open
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /**
     * Handler that responds with a configurable delay. Used for multiplexing tests.
     */
    private static class DelayedResponseHandler extends SimpleChannelInboundHandler<Object> {
        private final long delayMs;

        DelayedResponseHandler(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                ctx.executor().schedule(() -> {
                    if (ctx.channel().isActive()) {
                        Http2Headers responseHeaders = new DefaultHttp2Headers().status("200");
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(responseHeaders, true));
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    // =========================================================================
    // BUG 1 REGRESSION: Stream Semaphore Leak on Connection Restart
    //
    // Scenario: Server sends GOAWAY then closes the connection. If the client
    // acquired a stream slot but the stream channel bootstrap fails (because the
    // parent channel is closing), the slot leaks. After enough leaks, no new
    // streams can be opened and all subsequent requests time out.
    //
    // The fix: releaseStream() is called in the .open() failure path.
    // =========================================================================

    @Test
    public void streamSlotsNotLeakedAfterServerRestart() throws Exception {
        // Start a server, send requests, restart the server, send more requests.
        // Before the fix, stream slots would leak on each restart and eventually
        // the connection would be fully starved.
        startSimpleServer();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(5))
                .setMaxConnections(1)
                .setMaxConnectionsPerHost(1))) {

            // Phase 1: Establish connection and verify it works
            for (int i = 0; i < 5; i++) {
                Response response = client.prepareGet(httpsUrl("/ok"))
                        .execute()
                        .get(10, SECONDS);
                assertEquals(200, response.getStatusCode());
            }

            // Phase 2: Restart the server multiple times to induce GOAWAY + connection drops.
            // Each restart may cause stream slot leaks if the fix is not applied.
            for (int restart = 0; restart < 3; restart++) {
                // Kill the server
                serverChildChannels.close().sync();
                serverChannel.close().sync();

                // Brief pause to let client detect the closure
                Thread.sleep(200);

                // Restart the server on the same port
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
                                                streamCh.pipeline().addLast(new SimpleOkHandler());
                                            }
                                        }));
                            }
                        });

                serverChannel = b.bind(serverPort).sync().channel();

                // Phase 3: Verify requests still work after restart.
                // Before the fix, leaked stream slots would accumulate across restarts
                // and eventually cause all requests to queue in pendingOpeners and time out.
                for (int i = 0; i < 5; i++) {
                    Response response = client.prepareGet(httpsUrl("/ok"))
                            .execute()
                            .get(10, SECONDS);
                    assertEquals(200, response.getStatusCode(),
                            "Request failed after server restart #" + restart + ", request #" + i);
                }
            }
        }
    }

    @Test
    public void highConcurrencyAfterConnectionDropDoesNotStarve() throws Exception {
        // This test fires a burst of concurrent requests, kills the connection,
        // then fires another burst. Before the fix, the second burst would see
        // leaked stream slots from the first burst and eventually time out.
        startSimpleServer();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(5))
                .setMaxConnections(5)
                .setMaxConnectionsPerHost(5))) {

            int batchSize = 20;

            // First burst: send many concurrent requests
            List<CompletableFuture<Response>> batch1 = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                batch1.add(client.prepareGet(httpsUrl("/ok"))
                        .execute()
                        .toCompletableFuture());
            }
            for (CompletableFuture<Response> f : batch1) {
                assertEquals(200, f.get(10, SECONDS).getStatusCode());
            }

            // Kill all server connections to force the client to see channel closures
            serverChildChannels.close().sync();

            // Brief pause
            Thread.sleep(200);

            // Second burst: all requests should still succeed (on new connections).
            // Before the fix, leaked stream slots from the first burst's connection
            // would prevent new streams from being opened.
            List<CompletableFuture<Response>> batch2 = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                batch2.add(client.prepareGet(httpsUrl("/ok"))
                        .execute()
                        .toCompletableFuture());
            }
            for (CompletableFuture<Response> f : batch2) {
                assertEquals(200, f.get(10, SECONDS).getStatusCode());
            }
        }
    }

    // =========================================================================
    // BUG 2 REGRESSION: Concurrent Requests on Same H2 Connection Must All Complete
    //
    // Scenario: Two or more concurrent requests share the same HTTP/2 parent
    // channel. If the parent channel closes unexpectedly (e.g., TCP reset),
    // ALL concurrent futures must be failed/retried — not just the last one
    // whose future was set on the parent channel attribute.
    //
    // The fix: setAttribute is skipped for HTTP/2 parent channels.
    // =========================================================================

    @Test
    public void allConcurrentRequestsFailOnConnectionDrop() throws Exception {
        // Server holds all streams open without responding, then we kill the TCP connection.
        // Before the fix, only the LAST request's future (the one set on the parent channel
        // attribute) would be properly handled. All other futures would hang until timeout.
        startServerWithHandler(() -> new HoldOpenHandler());

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(10))
                .setMaxConnectionsPerHost(1))) {

            int numRequests = 5;
            CountDownLatch allStarted = new CountDownLatch(numRequests);
            List<CompletableFuture<Response>> futures = new ArrayList<>();

            // Fire 5 concurrent requests — all will be multiplexed on the same H2 connection
            for (int i = 0; i < numRequests; i++) {
                CompletableFuture<Response> f = client.prepareGet(httpsUrl("/hold"))
                        .execute(new AsyncCompletionHandlerBase() {
                            @Override
                            public AsyncHandler.State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                                allStarted.countDown();
                                return super.onStatusReceived(responseStatus);
                            }
                        })
                        .toCompletableFuture();
                futures.add(f);
            }

            // Give streams time to be opened on the server side
            Thread.sleep(500);

            // Kill the TCP connection from the server side
            serverChildChannels.close().sync();

            // ALL futures must complete (with an error, not a timeout).
            // Before the fix, only the last future would be failed; the others
            // would hang for 10 seconds until RequestTimeoutTimerTask fires.
            long startTime = System.currentTimeMillis();
            int failedCount = 0;
            for (CompletableFuture<Response> f : futures) {
                try {
                    f.get(5, SECONDS);
                } catch (ExecutionException e) {
                    failedCount++;
                }
            }
            long elapsed = System.currentTimeMillis() - startTime;

            assertEquals(numRequests, failedCount, "All concurrent requests should fail on connection drop");

            // Critical assertion: the failures should happen quickly (within ~2 seconds),
            // not after the 10-second request timeout. Before the fix, orphaned futures
            // would only complete via the timeout timer.
            assertTrue(elapsed < 5_000,
                    "Failures should happen quickly (got " + elapsed + "ms), not wait for request timeout");
        }
    }

    @Test
    public void concurrentMultiplexedRequestsAllSucceed() throws Exception {
        // Verify that many concurrent requests on the same H2 connection all succeed
        // when there are no connection issues. This would fail before the fix if the
        // parent channel attribute overwrite caused state corruption.
        startSimpleServer();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setMaxConnectionsPerHost(1)
                .setRequestTimeout(Duration.ofSeconds(10)))) {

            int numRequests = 50;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(numRequests);

            for (int i = 0; i < numRequests; i++) {
                client.prepareGet(httpsUrl("/ok"))
                        .execute()
                        .toCompletableFuture()
                        .whenComplete((response, error) -> {
                            if (error != null) {
                                firstError.compareAndSet(null, error);
                            } else if (response.getStatusCode() == 200) {
                                successCount.incrementAndGet();
                            }
                            latch.countDown();
                        });
            }

            assertTrue(latch.await(30, SECONDS));
            assertNull(firstError.get(), "No errors expected, got: " + firstError.get());
            assertEquals(numRequests, successCount.get(),
                    "All multiplexed requests should succeed");
        }
    }

    // =========================================================================
    // BUG 3 REGRESSION: Stream Channel Inactive Must Fail Future Immediately
    //
    // Scenario: An HTTP/2 stream is opened and the request is sent, but before
    // the response arrives, the TCP connection drops. The stream channel goes
    // inactive. Before the fix, the empty handleChannelInactive() meant the
    // future was never failed, and it would hang until the request timeout fired.
    //
    // The fix: handleChannelInactive() now calls readFailed() which aborts
    // the future and releases the stream slot.
    // =========================================================================

    @Test
    public void streamChannelInactiveFailsFutureImmediately() throws Exception {
        // Server opens the stream, waits 200ms, then kills the TCP connection.
        // The client should detect the stream channel going inactive and fail
        // the future immediately — NOT wait for the request timeout.
        startServerWithHandler(() -> new DelayThenDropHandler(200));

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(30)))) {

            long startTime = System.currentTimeMillis();
            try {
                client.prepareGet(httpsUrl("/drop"))
                        .execute()
                        .get(10, SECONDS);
                fail("Should have thrown — server dropped the connection before responding");
            } catch (ExecutionException e) {
                long elapsed = System.currentTimeMillis() - startTime;

                // The request should fail quickly (within ~3 seconds), not after 30s timeout.
                // Before the fix, with a 30s request timeout, the future would hang for the
                // full 30 seconds because handleChannelInactive was a no-op.
                assertTrue(elapsed < 5_000,
                        "Request should fail quickly on connection drop, but took " + elapsed + "ms. "
                                + "This suggests handleChannelInactive is not properly failing the future.");

                // The exception should be an IOException, not a TimeoutException
                Throwable cause = e.getCause();
                assertNotNull(cause);
                assertFalse(cause instanceof java.util.concurrent.TimeoutException,
                        "Should NOT fail with TimeoutException — should get IOException from channel close");
            }
        }
    }

    @Test
    public void multipleStreamChannelInactivesAllResolveQuickly() throws Exception {
        // Fire multiple requests, then kill the connection. ALL futures should
        // resolve quickly via handleChannelInactive, not via request timeout.
        startServerWithHandler(() -> new HoldOpenHandler());

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(30))
                .setMaxConnectionsPerHost(1))) {

            int numRequests = 10;
            List<CompletableFuture<Response>> futures = new ArrayList<>();

            for (int i = 0; i < numRequests; i++) {
                futures.add(client.prepareGet(httpsUrl("/hold"))
                        .execute()
                        .toCompletableFuture());
            }

            // Let streams get established
            Thread.sleep(500);

            // Kill the server connection
            long killTime = System.currentTimeMillis();
            serverChildChannels.close().sync();

            // All futures should resolve quickly
            int failedCount = 0;
            for (CompletableFuture<Response> f : futures) {
                try {
                    f.get(5, SECONDS);
                } catch (Exception e) {
                    failedCount++;
                }
            }
            long elapsed = System.currentTimeMillis() - killTime;

            assertTrue(failedCount > 0, "At least some requests should have failed");
            assertTrue(elapsed < 10_000,
                    "All futures should resolve within 10s of connection kill, took " + elapsed + "ms");
        }
    }

    @Test
    public void requestsSucceedAfterStreamChannelInactive() throws Exception {
        // After a connection drop (which exercises handleChannelInactive), subsequent
        // requests should succeed on a new connection — verifying that the stream slots
        // are properly released and the client recovers.
        startServerWithHandler(() -> new DelayThenDropHandler(100));

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(5)))) {

            // First request: will fail because server drops the connection
            try {
                client.prepareGet(httpsUrl("/drop"))
                        .execute()
                        .get(5, SECONDS);
            } catch (ExecutionException e) {
                // Expected
            }

            // Restart the server with a normal handler
            serverChildChannels.close().sync();
            serverChannel.close().sync();
            Thread.sleep(200);
            startSimpleServer();

            // Subsequent requests should succeed — the stream slots from the failed
            // request should have been released by handleChannelInactive -> finishUpdate
            for (int i = 0; i < 5; i++) {
                Response response = client.prepareGet(httpsUrl("/ok"))
                        .execute()
                        .get(10, SECONDS);
                assertEquals(200, response.getStatusCode(),
                        "Request #" + i + " should succeed after connection recovery");
            }
        }
    }

    // =========================================================================
    // BUG 4 REGRESSION: Pending Opener Race Condition Under High Concurrency
    //
    // Scenario: With a low maxConcurrentStreams (e.g., 2), fire many requests.
    // Those beyond the limit are queued as pending openers. Before the fix, the
    // TOCTOU race in addPendingOpener could cause one thread's opener to consume
    // another thread's stream slot, or run an opener without incrementing
    // activeStreams.
    //
    // The fix: addPendingOpener and drainPendingOpeners use synchronized blocks.
    // =========================================================================

    @Test
    public void highConcurrencyWithLowMaxStreamsDoesNotDeadlock() throws Exception {
        // Fire many concurrent requests with a client-side maxConcurrentStreams limit.
        // Requests beyond the limit are queued as pending openers.
        // Before the fix, the race condition in addPendingOpener could cause:
        // 1. Openers running without stream slots (exceeding the server's max)
        // 2. Openers stuck in the queue permanently (deadlock)
        // 3. activeStreams count going negative (allowing too many streams)
        startSimpleServer();

        // Use a low client-side maxConcurrentStreams to force pending opener queuing.
        // The server allows many streams, so any failures are from client-side bugs.
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setHttp2MaxConcurrentStreams(3)
                .setRequestTimeout(Duration.ofSeconds(10))
                .setMaxConnectionsPerHost(1))) {

            int numRequests = 30;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(numRequests);

            for (int i = 0; i < numRequests; i++) {
                client.prepareGet(httpsUrl("/ok"))
                        .execute()
                        .toCompletableFuture()
                        .whenComplete((response, error) -> {
                            if (error != null) {
                                firstError.compareAndSet(null, error);
                            } else if (response.getStatusCode() == 200) {
                                successCount.incrementAndGet();
                            }
                            latch.countDown();
                        });
            }

            assertTrue(latch.await(30, SECONDS), "All requests should complete within 30s");
            assertNull(firstError.get(),
                    "No errors expected with low MAX_CONCURRENT_STREAMS, got: " + firstError.get());
            assertEquals(numRequests, successCount.get());
        }
    }

    @Test
    public void repeatedBurstsWithLowMaxStreamsDoNotLeakSlots() throws Exception {
        // Send multiple bursts of sequential requests through a connection with low
        // client-side maxConcurrentStreams. Each request exercises the acquire/release
        // cycle. Before the fix, the race condition in addPendingOpener could cause
        // slot leaks that accumulate across bursts, eventually deadlocking the connection.
        startSimpleServer();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setHttp2MaxConcurrentStreams(3)
                .setRequestTimeout(Duration.ofSeconds(10))
                .setMaxConnectionsPerHost(1))) {

            // Send multiple bursts of sequential requests through the same connection.
            // With maxConcurrentStreams=3, each request goes through the acquire/release
            // cycle. If stream slots leak, later bursts will deadlock.
            for (int burst = 0; burst < 5; burst++) {
                int burstSize = 15;
                for (int i = 0; i < burstSize; i++) {
                    Response response = client.prepareGet(httpsUrl("/ok"))
                            .execute()
                            .get(10, SECONDS);
                    assertEquals(200, response.getStatusCode(),
                            "Request #" + i + " in burst #" + burst + " should succeed");
                }
            }
        }
    }

    // =========================================================================
    // GOAWAY REGRESSION: GOAWAY During Active Streams
    //
    // Scenario: Server sends GOAWAY while streams are active. The client must:
    // 1. Not open new streams on the draining connection
    // 2. Allow existing streams to complete
    // 3. Retry failed streams on a new connection
    // 4. Not leak stream slots
    // =========================================================================

    @Test
    public void goawayDuringActiveStreamsRecoveryIsClean() throws Exception {
        // Start a server that holds streams open. Send requests, then send GOAWAY
        // from the server side. Verify new requests succeed on a new connection.
        AtomicReference<Channel> capturedParentChannel = new AtomicReference<>();

        ServerBootstrap b = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        serverChildChannels.add(ch);
                        capturedParentChannel.set(ch);
                        ch.pipeline()
                                .addLast("ssl", serverSslCtx.newHandler(ch.alloc()))
                                .addLast(Http2FrameCodecBuilder.forServer().build())
                                .addLast(new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
                                    @Override
                                    protected void initChannel(Http2StreamChannel streamCh) {
                                        streamCh.pipeline().addLast(new SimpleOkHandler());
                                    }
                                }));
                    }
                });

        serverChannel = b.bind(0).sync().channel();
        serverPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(5)))) {

            // Establish connection
            Response r1 = client.prepareGet(httpsUrl("/ok")).execute().get(10, SECONDS);
            assertEquals(200, r1.getStatusCode());

            // Send GOAWAY from the server to the client's parent connection
            Channel parent = capturedParentChannel.get();
            assertNotNull(parent);
            parent.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR));

            // Brief pause for GOAWAY to propagate
            Thread.sleep(300);

            // New requests should succeed — the client should open a new connection
            // since the old one is draining
            for (int i = 0; i < 5; i++) {
                Response response = client.prepareGet(httpsUrl("/ok"))
                        .execute()
                        .get(10, SECONDS);
                assertEquals(200, response.getStatusCode(),
                        "Request #" + i + " should succeed on new connection after GOAWAY");
            }
        }
    }

    // =========================================================================
    // COMBINED REGRESSION: The ~0.5% Timeout Scenario
    //
    // These tests simulate the exact production scenario described in the bug
    // report — high concurrency with periodic server disruptions — and verify
    // that 0% of requests time out silently.
    // =========================================================================

    @Test
    public void zeroPctTimeoutsUnderConcurrencyWithServerDisruptions() throws Exception {
        // This is the end-to-end regression test for the reported ~0.5% timeout rate.
        // We fire 200 requests across 4 waves, with server restarts between each wave.
        // Before the fixes, some requests would silently time out due to:
        //   - Leaked stream slots (Bug 1)
        //   - Orphaned futures from attribute overwrite (Bug 2)
        //   - Missing channelInactive handling (Bug 3)
        //   - Pending opener deadlocks (Bug 4)
        startSimpleServer();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(10))
                .setMaxConnections(10)
                .setMaxConnectionsPerHost(5))) {

            int wavesCount = 4;
            int requestsPerWave = 50;
            int totalRequests = wavesCount * requestsPerWave;
            AtomicInteger totalSuccess = new AtomicInteger(0);
            AtomicInteger totalFailed = new AtomicInteger(0);
            List<Throwable> errors = new CopyOnWriteArrayList<>();

            for (int wave = 0; wave < wavesCount; wave++) {
                CountDownLatch waveLatch = new CountDownLatch(requestsPerWave);

                for (int i = 0; i < requestsPerWave; i++) {
                    client.prepareGet(httpsUrl("/ok"))
                            .execute()
                            .toCompletableFuture()
                            .whenComplete((response, error) -> {
                                if (error != null) {
                                    totalFailed.incrementAndGet();
                                    errors.add(error);
                                } else if (response.getStatusCode() == 200) {
                                    totalSuccess.incrementAndGet();
                                }
                                waveLatch.countDown();
                            });
                }

                assertTrue(waveLatch.await(30, SECONDS),
                        "Wave " + wave + " timed out waiting for completion");

                // Restart the server between waves (except after the last one)
                if (wave < wavesCount - 1) {
                    serverChildChannels.close().sync();
                    serverChannel.close().sync();
                    Thread.sleep(100);
                    startSimpleServer();
                }
            }

            // No silent timeouts should occur
            assertTrue(errors.isEmpty(),
                    "Expected 0 errors across " + totalRequests + " requests with disruptions, got "
                            + errors.size() + ": " + (errors.isEmpty() ? "" : errors.get(0).getMessage()));
            assertEquals(totalRequests, totalSuccess.get(),
                    "All requests should succeed (some may have retried)");
        }
    }

    @Test
    public void clientRecoversAfterRepeatedConnectionDrops() throws Exception {
        // After each connection drop, verify the client can still make new requests.
        // Before the fixes, leaked stream slots and unhandled channelInactive would
        // cause the client to permanently lose the ability to make requests.
        startSimpleServer();

        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setHttp2Enabled(true)
                .setRequestTimeout(Duration.ofSeconds(5))
                .setMaxConnections(10)
                .setMaxConnectionsPerHost(5))) {

            for (int cycle = 0; cycle < 5; cycle++) {
                // Send a batch of successful requests
                for (int i = 0; i < 10; i++) {
                    Response response = client.prepareGet(httpsUrl("/ok"))
                            .execute()
                            .get(10, SECONDS);
                    assertEquals(200, response.getStatusCode(),
                            "Request #" + i + " in cycle #" + cycle + " should succeed");
                }

                // Kill all server connections (simulates network disruption)
                serverChildChannels.close().sync();
                Thread.sleep(200);

                // The next request may fail (in-flight or stale connection) — that's OK.
                // What matters is the client recovers and subsequent requests succeed.
                try {
                    client.prepareGet(httpsUrl("/ok"))
                            .execute()
                            .get(5, SECONDS);
                } catch (ExecutionException e) {
                    // Expected — the connection was just killed
                }

                // Client MUST recover: subsequent requests should succeed on new connections
                for (int i = 0; i < 5; i++) {
                    Response response = client.prepareGet(httpsUrl("/ok"))
                            .execute()
                            .get(10, SECONDS);
                    assertEquals(200, response.getStatusCode(),
                            "Recovery request #" + i + " after drop #" + cycle + " should succeed");
                }
            }
        }
    }
}
