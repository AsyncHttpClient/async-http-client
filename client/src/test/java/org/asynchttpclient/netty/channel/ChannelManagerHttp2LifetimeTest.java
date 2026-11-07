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

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.util.HashedWheelTimer;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An HTTP/2 connection is retired once it has been open for {@code connectionTtl}, give or take the tenth taken off
 * so that connections opened together do not expire together. Driven on a frozen {@link EmbeddedChannel} clock, so
 * the expiry is an exact point in time rather than something to wait for. The end-to-end path over a real socket
 * is covered by {@code BasicHttp2Test}.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ChannelManagerHttp2LifetimeTest {

    private static final String TTL_HANDLER = "http2-connection-ttl";
    private static final long TTL_MILLIS = 60_000;
    // The earliest the connection may be retired: a tenth before the configured TTL.
    private static final long EARLIEST_EXPIRY_MILLIS = TTL_MILLIS - TTL_MILLIS / 10;
    private static final String BASE = "https://host:443";

    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private ChannelManager channelManager;
    private Timer timer;

    @BeforeEach
    void setUp() {
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(config().setConnectionTtl(Duration.ofMillis(TTL_MILLIS)).build(), timer);
    }

    @AfterEach
    void tearDown() {
        for (EmbeddedChannel channel : channels) {
            channel.finishAndReleaseAll();
        }
        channelManager.close();
        timer.stop();
    }

    /**
     * An upgraded, registered HTTP/2 connection whose clock starts frozen at the upgrade, as NettyConnectListener
     * leaves it.
     */
    private EmbeddedChannel http2Connection(Object partitionKey) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        channel.freezeTime();
        channelManager.upgradePipelineToHttp2(channel.pipeline());
        channelManager.registerHttp2Connection(partitionKey, channel);
        return channel;
    }

    private static Http2ConnectionState stateOf(EmbeddedChannel channel) {
        return channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
    }

    private static void advance(EmbeddedChannel channel, long millis) {
        channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
    }

    @Test
    void noLifetimeUnlessConnectionTtlIsPositive() {
        assertNoTtlHandler(config().build());
        assertNoTtlHandler(config().setConnectionTtl(Duration.ofMillis(-1)).build());
        // Below a millisecond counts as unset, as it does for the HTTP/1.1 pool.
        assertNoTtlHandler(config().setConnectionTtl(Duration.ofNanos(500_000)).build());
    }

    private static void assertNoTtlHandler(AsyncHttpClientConfig config) {
        Timer timer = new HashedWheelTimer();
        ChannelManager manager = new ChannelManager(config, timer);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            manager.upgradePipelineToHttp2(channel.pipeline());
            assertNull(channel.pipeline().get(TTL_HANDLER), "connectionTtl " + config.getConnectionTtl());
        } finally {
            channel.finishAndReleaseAll();
            manager.close();
            timer.stop();
        }
    }

    @Test
    void connectionIsNotRetiredBeforeTheEarliestExpiry() {
        EmbeddedChannel channel = http2Connection(BASE);
        assertNotNull(channel.pipeline().get(TTL_HANDLER));

        advance(channel, EARLIEST_EXPIRY_MILLIS - 1);

        assertFalse(stateOf(channel).isRetired());
        assertSame(channel, channelManager.pollHttp2Connection(BASE), "still handed out");
        assertTrue(channel.isOpen());
    }

    @Test
    void idleConnectionIsRetiredAndClosedWithGoAwayAtConnectionTtl() {
        EmbeddedChannel channel = http2Connection(BASE);

        advance(channel, TTL_MILLIS);

        assertTrue(stateOf(channel).isRetired());
        assertNull(channelManager.pollHttp2Connection(BASE));
        assertFalse(channel.isOpen(), "nothing runs on it, so it closes at once");
        assertEquals(List.of(Http2Error.NO_ERROR.code()), goAwayErrorCodesSentBy(channel),
                "exactly one GOAWAY(NO_ERROR), sent by the close and not earlier");
    }

    @Test
    void busyConnectionIsRetiredButStaysOpenUntilItsLastStreamEnds() {
        EmbeddedChannel channel = http2Connection(BASE);
        Http2ConnectionState state = stateOf(channel);
        assertTrue(state.tryAcquireStream());

        advance(channel, TTL_MILLIS);

        assertTrue(state.isRetired());
        assertNull(channelManager.pollHttp2Connection(BASE), "no longer handed out");
        assertTrue(channel.isOpen(), "a stream in flight must be allowed to finish");
        assertTrue(goAwayErrorCodesSentBy(channel).isEmpty(), "no GOAWAY while the connection is still in use");

        // What the stream's close listener in NettyRequestSender does.
        state.releaseStream();
        assertTrue(state.closeIfRetiredAndIdle(), "the last stream ending makes it closable");
    }

    @Test
    void replacementRegisteredAfterRetirementIsTheConnectionHandedOut() {
        EmbeddedChannel retired = http2Connection(BASE);
        assertTrue(stateOf(retired).tryAcquireStream());
        advance(retired, TTL_MILLIS);

        EmbeddedChannel replacement = http2Connection(BASE);

        assertFalse(stateOf(replacement).isRedundant(),
                "a retired connection left in the registry would make every replacement redundant");
        assertSame(replacement, channelManager.pollHttp2Connection(BASE));

        retired.close();
        assertSame(replacement, channelManager.pollHttp2Connection(BASE),
                "the retired connection closing must not take its replacement out of the registry");
    }

    @Test
    void roundRobinPermitIsReleasedAtRetirementAndNotAgainOnClose() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        semaphore.acquireChannelLock(BASE);
        RoundRobinPartitionKey key = new RoundRobinPartitionKey(BASE, InetAddress.getByName("127.0.0.1"));
        EmbeddedChannel channel = http2Connection(key);
        // NettyConnectListener's round-robin wiring: a release hook on the state and on the closeFuture, sharing
        // one getAndSet.
        AtomicReference<Object> permit = new AtomicReference<>(BASE);
        Runnable release = () -> {
            Object held = permit.getAndSet(null);
            if (held != null) {
                semaphore.releaseChannelLock(held);
            }
        };
        stateOf(channel).setPermitRelease(release);
        channel.closeFuture().addListener(f -> release.run());
        assertTrue(stateOf(channel).tryAcquireStream());
        assertEquals(0, availablePerHost(semaphore));

        advance(channel, TTL_MILLIS);

        assertEquals(1, availablePerHost(semaphore), "a replacement must be admissible while the old streams finish");
        assertTrue(channel.isOpen());
        stateOf(channel).releaseStream();
        channel.close();
        assertEquals(1, availablePerHost(semaphore), "closing must not release the permit a second time");
    }

    private static int availablePerHost(PerHostConnectionSemaphore semaphore) {
        Semaphore free = semaphore.freeChannelsPerHost.get(BASE);
        return free != null ? free.availablePermits() : semaphore.maxConnectionsPerHost;
    }

    @Test
    void closingBeforeTheTtlCancelsTheExpiry() {
        EmbeddedChannel channel = http2Connection(BASE);
        Http2ConnectionState state = stateOf(channel);

        channel.close();
        advance(channel, 2 * TTL_MILLIS);

        assertFalse(state.isRetired(), "the expiry must not run for a connection that already closed");
        assertEquals(-1, channel.runScheduledPendingTasks(), "and nothing may stay scheduled for it");
    }

    /**
     * Error codes of the GOAWAY frames the connection wrote, decoded by a server-side codec fed with everything the
     * connection wrote.
     */
    private static List<Long> goAwayErrorCodesSentBy(EmbeddedChannel client) {
        List<Long> errorCodes = new ArrayList<>();
        EmbeddedChannel server = new EmbeddedChannel(Http2FrameCodecBuilder.forServer().build(),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof Http2GoAwayFrame) {
                            errorCodes.add(((Http2GoAwayFrame) msg).errorCode());
                        }
                        ReferenceCountUtil.release(msg);
                    }
                });
        try {
            Object written;
            while ((written = client.readOutbound()) != null) {
                server.writeInbound(written);
            }
        } finally {
            server.finishAndReleaseAll();
        }
        return errorCodes;
    }
}
