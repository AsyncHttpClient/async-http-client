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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.exception.TooManyConnectionsException;
import org.asynchttpclient.exception.TooManyConnectionsPerHostException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the round-robin GOAWAY drain-permit fix (issue #2214): the per-host permit held for
 * an HTTP/2 connection's lifetime must be released when the connection starts draining, not when it finally
 * closes, and released exactly once across the GOAWAY path and the channel closeFuture. The wiring mirrors
 * {@link NettyConnectListener}'s round-robin branch and {@link ChannelManager}'s GOAWAY handler, without
 * network I/O; the end-to-end path over a real socket is covered by {@code BasicHttp2Test}.
 */
class ChannelManagerHttp2DrainPermitTest {

    private static final String BASE = "https://host:443";

    private ChannelManager channelManager;
    private Timer timer;

    @BeforeEach
    void setUp() {
        AsyncHttpClientConfig cfg = config().build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(cfg, timer);
    }

    @AfterEach
    void tearDown() {
        if (channelManager != null) {
            channelManager.close();
        }
        if (timer != null) {
            timer.stop();
        }
    }

    private static RoundRobinPartitionKey rrKey(String base, String ip) throws Exception {
        return new RoundRobinPartitionKey(base, InetAddress.getByName(ip));
    }

    /**
     * Registers an active round-robin HTTP/2 connection holding a per-host permit, wired as
     * {@link NettyConnectListener} does: permit keyed by the base key, connection registered under the
     * IP-aware key, release hook plus closeFuture listener installed.
     */
    private EmbeddedChannel registerRrConnectionHoldingPermit(ConnectionSemaphore semaphore, Object baseKey,
                                                              Object registryKey) {
        EmbeddedChannel channel = new EmbeddedChannel();
        Http2ConnectionState state = new Http2ConnectionState();
        channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).set(state);
        channelManager.registerHttp2Connection(registryKey, channel);
        // Stand in for NettyConnectListener's round-robin wiring - the drain hook from
        // registerHttp2AndManageSemaphore plus the closeFuture release from onSuccess, both funnelling
        // through one getAndSet. This fixture only pins ChannelManager's GOAWAY drain contract; that
        // NettyConnectListener actually installs this wiring is covered end-to-end by BasicHttp2Test.
        AtomicReference<Object> permit = new AtomicReference<>(baseKey);
        Runnable release = () -> {
            Object key = permit.getAndSet(null);
            if (key != null) {
                semaphore.releaseChannelLock(key);
            }
        };
        state.setPermitRelease(release);
        channel.closeFuture().addListener(f -> release.run());
        return channel;
    }

    /**
     * Available per-host permits for {@code baseKey} under a {@link PerHostConnectionSemaphore}. A pruned
     * (absent) entry means nobody holds or awaits a permit for that host, so the full capacity is free.
     */
    private static int availablePerHost(PerHostConnectionSemaphore semaphore, Object baseKey) {
        Semaphore freeConnections = semaphore.freeChannelsPerHost.get(baseKey);
        return freeConnections != null ? freeConnections.availablePermits() : semaphore.maxConnectionsPerHost;
    }

    /**
     * GOAWAY on a still-open connection must free the per-host permit at drain start, and the later
     * channel close must not release a second one.
     */
    @Test
    void goawayReleasesPerHostPermitImmediatelyAndCloseDoesNotDoubleRelease() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        RoundRobinPartitionKey registryKey = rrKey(BASE, "127.0.0.1");
        semaphore.acquireChannelLock(BASE);
        assertEquals(0, availablePerHost(semaphore, BASE), "permit is held after acquire");

        EmbeddedChannel channel = registerRrConnectionHoldingPermit(semaphore, BASE, registryKey);
        Http2ConnectionState state = channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
        // An in-flight stream keeps the connection open through the drain.
        assertTrue(state.tryAcquireStream());

        // Simulate GOAWAY exactly as ChannelManager's handler does.
        state.setDraining(0);
        channelManager.removeHttp2Connection(state.getPartitionKey(), channel);
        state.releasePermitOnce();

        assertEquals(1, availablePerHost(semaphore, BASE),
                "the per-host permit must be freed at drain start, while the channel is still open");
        assertTrue(channel.isOpen(), "the draining connection is still open (its stream is in flight)");
        assertNull(channelManager.pollHttp2SiblingConnection(BASE), "a draining connection is not offered for reuse");

        // The in-flight stream ends and the channel closes: closeFuture must NOT release a second permit.
        state.releaseStream();
        channel.close().sync();
        assertEquals(1, availablePerHost(semaphore, BASE),
                "channel close after a GOAWAY drain must not release a second permit (never exceed the cap)");
    }

    /**
     * A connection that closes without ever receiving a GOAWAY (normal drop, TCP reset) must still release
     * its permit exactly once, via the closeFuture; a stray later releasePermitOnce is a no-op.
     */
    @Test
    void closeWithoutGoawayReleasesPermitExactlyOnce() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        RoundRobinPartitionKey registryKey = rrKey(BASE, "127.0.0.1");
        semaphore.acquireChannelLock(BASE);

        EmbeddedChannel channel = registerRrConnectionHoldingPermit(semaphore, BASE, registryKey);
        Http2ConnectionState state = channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
        assertEquals(0, availablePerHost(semaphore, BASE));

        channel.close().sync();
        assertEquals(1, availablePerHost(semaphore, BASE), "close releases the permit once");

        // A late GOAWAY-style release (or any extra call) must not push the semaphore above the cap.
        state.releasePermitOnce();
        assertEquals(1, availablePerHost(semaphore, BASE), "a second release must be a no-op");
    }

    /**
     * With a per-host cap of 1, a new connection to the same host must be admissible again once the drain
     * releases the permit; without the fix the draining connection pins the only permit and a replacement
     * fails with {@link TooManyConnectionsPerHostException}.
     */
    @Test
    void replacementConnectionIsAdmissibleAfterDrainRelease() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        RoundRobinPartitionKey registryKey = rrKey(BASE, "127.0.0.1");
        semaphore.acquireChannelLock(BASE);

        EmbeddedChannel channel = registerRrConnectionHoldingPermit(semaphore, BASE, registryKey);
        Http2ConnectionState state = channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
        assertTrue(state.tryAcquireStream()); // keep it open through the drain

        // Before the fix, the permit is still held here, so this acquire fails.
        assertThrows(TooManyConnectionsPerHostException.class, () -> semaphore.acquireChannelLock(BASE),
                "sanity: the cap is exhausted while the connection holds its permit");

        // GOAWAY drain releases the permit.
        state.setDraining(0);
        channelManager.removeHttp2Connection(state.getPartitionKey(), channel);
        state.releasePermitOnce();

        // A replacement connection can now be opened immediately, without waiting for the drain to finish.
        assertDoesNotThrow(() -> semaphore.acquireChannelLock(BASE),
                "after the drain release a replacement connection must be admissible");

        state.releaseStream();
        channel.close().sync();
    }

    /**
     * With a combined limiter the round-robin permit pins both a per-host and a global slot. Releasing it
     * at drain start must free the global slot too, so unrelated hosts are not starved.
     */
    @Test
    void goawayReleasesGlobalSlotSoUnrelatedHostIsNotStarved() throws Exception {
        // Global cap 1, per-host cap 1: a single live round-robin connection saturates the global limit.
        CombinedConnectionSemaphore semaphore = new CombinedConnectionSemaphore(1, 1, 0);
        Object otherHost = "https://other:443";
        RoundRobinPartitionKey registryKey = rrKey(BASE, "127.0.0.1");
        semaphore.acquireChannelLock(BASE);

        EmbeddedChannel channel = registerRrConnectionHoldingPermit(semaphore, BASE, registryKey);
        Http2ConnectionState state = channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
        assertTrue(state.tryAcquireStream()); // keep it open through the drain

        // The global slot is pinned: even an unrelated host cannot connect.
        assertThrows(TooManyConnectionsException.class, () -> semaphore.acquireChannelLock(otherHost),
                "sanity: the global cap is exhausted while the draining connection holds its slot");

        // GOAWAY drain releases the combined (global + per-host) permit.
        state.setDraining(0);
        channelManager.removeHttp2Connection(state.getPartitionKey(), channel);
        state.releasePermitOnce();

        assertDoesNotThrow(() -> semaphore.acquireChannelLock(otherHost),
                "releasing the draining connection's permit must free the global slot for an unrelated host");

        state.releaseStream();
        channel.close().sync();
    }
}
