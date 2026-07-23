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

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.asynchttpclient.netty.channel.DefaultChannelPool.PoolLeaseStrategy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * White-box unit tests for {@link DefaultChannelPool} covering the bare-channel storage + reused
 * {@code IdleState} attribute (plan 009) and the O(1) tombstone {@code removeAll} + tombstone-aware
 * idle cleaner (plan 013). The cleaner is driven deterministically through a capturing {@link Timer}.
 */
public class DefaultChannelPoolTest {

    private static final Object KEY = "partition-key";

    private static DefaultChannelPool noReaperPool() {
        // No TTL, no idle timeout => no cleaner scheduled; removeAll is a no-op (unchanged behavior).
        return new DefaultChannelPool(Duration.ZERO, Duration.ZERO, PoolLeaseStrategy.LIFO,
                new CapturingTimer(), Duration.ofMillis(1));
    }

    private static DefaultChannelPool ttlPool(CapturingTimer timer) {
        // TTL enabled (long, so it never trips) => cleaner scheduled, removeAll tombstoning active.
        return new DefaultChannelPool(Duration.ZERO, Duration.ofHours(1), PoolLeaseStrategy.LIFO,
                timer, Duration.ofMillis(1));
    }

    private static DefaultChannelPool idlePool(CapturingTimer timer, Duration maxIdle) {
        return new DefaultChannelPool(maxIdle, Duration.ZERO, PoolLeaseStrategy.LIFO,
                timer, Duration.ofMillis(1));
    }

    // ---- plan 009: bare-channel offer/poll ----

    @Test
    public void offerThenPollReturnsSameChannelThenEmpty() {
        DefaultChannelPool pool = noReaperPool();
        Channel channel = new EmbeddedChannel();

        assertTrue(pool.offer(channel, KEY));
        assertSame(channel, pool.poll(KEY), "poll must return the offered channel");
        assertNull(pool.poll(KEY), "a polled channel is removed from the pool");

        pool.destroy();
    }

    @Test
    public void reofferingReusesTheSameIdleStateInstance() throws Exception {
        DefaultChannelPool pool = noReaperPool();
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        Object firstState = idleState(channel);
        assertSame(channel, pool.poll(KEY));

        pool.offer(channel, KEY);
        Object secondState = idleState(channel);
        // 009: the per-channel IdleState holder is allocated once and reused across checkouts.
        assertSame(firstState, secondState, "IdleState must be reused, not reallocated per offer");
        assertSame(channel, pool.poll(KEY));

        pool.destroy();
    }

    // ---- plan 013: O(1) tombstone removeAll ----

    @Test
    public void removeAllTombstonesSoChannelIsNoLongerLeasable() {
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = ttlPool(timer);
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        assertTrue(pool.removeAll(channel), "removeAll returns true for a pooled, leasable channel");
        assertNull(pool.poll(KEY), "a tombstoned channel must not be leased by poll");
        assertTrue(channel.isActive(), "removeAll must not close the channel; the caller owns the close");

        pool.destroy();
    }

    @Test
    public void removeAllReturnsFalseTheSecondTime() {
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = ttlPool(timer);
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        assertTrue(pool.removeAll(channel));
        assertFalse(pool.removeAll(channel), "a channel can only be tombstoned once");

        pool.destroy();
    }

    @Test
    public void removeAllIsNoOpWhenTtlDisabled() {
        DefaultChannelPool pool = noReaperPool();
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        assertFalse(pool.removeAll(channel), "removeAll only acts when connectionTtl is enabled");
        // The channel is still leasable (it was never tombstoned).
        assertSame(channel, pool.poll(KEY));

        pool.destroy();
    }

    // ---- plan 013: tombstone-aware cleaner ----

    @Test
    public void cleanerUnlinksTombstoneWithoutClosingIt() throws Exception {
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = ttlPool(timer);
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        pool.removeAll(channel);
        assertEquals(1, partitionSize(pool, KEY), "tombstone lingers until the cleaner ticks");

        timer.fire();

        assertEquals(0, partitionSize(pool, KEY), "cleaner must physically unlink the tombstone");
        assertTrue(channel.isActive(), "cleaner must not close a tombstoned channel");

        pool.destroy();
    }

    @Test
    public void cleanerClosesRemotelyClosedChannel() throws Exception {
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofHours(1)); // only the remote-close path trips
        EmbeddedChannel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        channel.close().await(5, TimeUnit.SECONDS);
        assertFalse(channel.isActive());

        timer.fire();

        assertEquals(0, partitionSize(pool, KEY), "remotely closed channel must be unlinked");
        assertNull(pool.poll(KEY));

        pool.destroy();
    }

    @Test
    public void cleanerClosesIdleTimeoutExpiredChannel() throws Exception {
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofMillis(1));
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        Thread.sleep(40); // make now - start >= 1ms

        timer.fire();

        assertEquals(0, partitionSize(pool, KEY), "idle-expired channel must be unlinked");
        assertFalse(channel.isActive(), "cleaner must close an idle-expired channel");
        assertNull(pool.poll(KEY));

        pool.destroy();
    }

    @Test
    public void cleanerLeavesHealthyChannelLeasable() throws Exception {
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofHours(1));
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        timer.fire();

        assertEquals(1, partitionSize(pool, KEY), "a healthy idle channel must survive the cleaner");
        assertTrue(channel.isActive());
        assertSame(channel, pool.poll(KEY), "a healthy channel stays leasable");

        pool.destroy();
    }

    @Test
    public void channelReofferedAfterExpiryIsNotReaped() throws Exception {
        // maxIdleTime must comfortably exceed the re-offer -> cleaner-fire gap below: reset() stamps a
        // fresh start, and the channel must read as fresh when the cleaner runs. A tiny timeout (e.g.
        // 1ms) is shorter than millisecond clock granularity, so the re-offered channel would re-expire
        // before fire() and be reaped — a test artifact, not a pool bug.
        final long maxIdle = 1000;
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofMillis(maxIdle));
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        Thread.sleep(maxIdle + 100); // first lifetime exceeds maxIdleTime: this channel was reapable

        // Lease and re-offer it: reset() stamps a fresh start, so the cleaner (firing immediately,
        // far inside maxIdleTime) must spare it.
        assertSame(channel, pool.poll(KEY));
        pool.offer(channel, KEY);

        timer.fire();

        assertTrue(channel.isActive(), "a re-offered (fresh) channel must not be closed by the cleaner");
        assertSame(channel, pool.poll(KEY));

        pool.destroy();
    }

    // ---- reap pass unlinks many channels in a single tick (O(n) iterator-remove) ----

    @Test
    public void cleanerReapsManyIdleExpiredChannelsInOneTick() throws Exception {
        // Exercises the reap pass unlinking MANY channels in a single tick — the O(n) iterator-remove
        // path that replaced the old collect-then-ConcurrentLinkedDeque.removeAll (which was O(n*m)).
        // All channels expire together, as they would when a load spike's connections idle out as a wave.
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofMillis(1));

        final int count = 50;
        Channel[] channels = new Channel[count];
        for (int i = 0; i < count; i++) {
            channels[i] = new EmbeddedChannel();
            pool.offer(channels[i], KEY);
        }
        assertEquals(count, partitionSize(pool, KEY));
        Thread.sleep(40); // now - start >= 1ms for every channel

        timer.fire();

        assertEquals(0, partitionSize(pool, KEY), "every idle-expired channel must be unlinked in one tick");
        for (Channel c : channels) {
            assertFalse(c.isActive(), "each idle-expired channel must be closed");
        }
        assertNull(pool.poll(KEY));

        pool.destroy();
    }

    @Test
    public void cleanerReapsExpiredButKeepsHealthyInSameTick() throws Exception {
        // A single reap pass must drop the expired channels AND keep the fresh ones leasable: the
        // iterator has to remove some nodes while continuing past the ones it keeps.
        // Use a generous idle window (mirrors channelReofferedAfterExpiryIsNotReaped): the fresh
        // channels are offered right before firing, so a GC/scheduling pause shorter than maxIdle
        // cannot age them past the timeout and get them wrongly reaped on a loaded CI box.
        final long maxIdle = 1000;
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofMillis(maxIdle));

        final int expiredCount = 6;
        Channel[] expired = new Channel[expiredCount];
        for (int i = 0; i < expiredCount; i++) {
            expired[i] = new EmbeddedChannel();
            pool.offer(expired[i], KEY);
        }
        Thread.sleep(maxIdle + 150); // these are now well past maxIdleTime

        final int healthyCount = 6;
        Channel[] healthy = new Channel[healthyCount];
        for (int i = 0; i < healthyCount; i++) {
            healthy[i] = new EmbeddedChannel();
            pool.offer(healthy[i], KEY); // fresh start, comfortably inside maxIdleTime
        }
        assertEquals(expiredCount + healthyCount, partitionSize(pool, KEY));

        timer.fire();

        assertEquals(healthyCount, partitionSize(pool, KEY), "only the fresh channels must survive the tick");
        for (Channel c : expired) {
            assertFalse(c.isActive(), "expired channels must be closed");
        }
        for (Channel c : healthy) {
            assertTrue(c.isActive(), "fresh channels must not be touched");
        }
        int leased = 0;
        while (pool.poll(KEY) != null) {
            leased++;
        }
        assertEquals(healthyCount, leased, "every surviving channel must remain leasable");

        pool.destroy();
    }

    @Test
    public void cleanerContinuesPastRemovedNodesToReachKeptNodes() throws Exception {
        // Pins the exact iterator-remove guarantee: after unlinking a node, the scan must continue to a
        // KEPT node that comes AFTER it in iteration order. Idle timeout is disabled (1h) so only the
        // remote-close path trips, and channels are closed (not aged) to decide keep-vs-reap — fully
        // deterministic, no wall-clock timing. offer() is offerFirst, so offering in reverse index order
        // puts channels[0] at the front; the iterator then visits channels[0], channels[1], ... in order.
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofHours(1));

        final int count = 8;
        EmbeddedChannel[] channels = new EmbeddedChannel[count];
        for (int i = count - 1; i >= 0; i--) {
            channels[i] = new EmbeddedChannel();
            pool.offer(channels[i], KEY);
        }
        // Close the even-indexed channels: in front->back iteration order every removed (even) node is
        // immediately followed by a kept (odd) node, so the iterator must remove then advance to a keeper.
        for (int i = 0; i < count; i += 2) {
            channels[i].close().await(5, TimeUnit.SECONDS);
            assertFalse(channels[i].isActive());
        }

        timer.fire();

        assertEquals(count / 2, partitionSize(pool, KEY), "closed nodes unlinked, kept ones survive");
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                assertFalse(channels[i].isActive(), "closed channel must be unlinked");
            } else {
                assertTrue(channels[i].isActive(), "a kept node AFTER a removed node must survive the scan");
            }
        }
        int leased = 0;
        while (pool.poll(KEY) != null) {
            leased++;
        }
        assertEquals(count / 2, leased, "every surviving channel must remain leasable");

        pool.destroy();
    }

    @Test
    public void cleanerUnlinksManyTombstonesInOneTick() throws Exception {
        // Many tombstones (from removeAll(Channel)) must all be unlinked in a single pass, none closed.
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = ttlPool(timer);

        final int count = 40;
        Channel[] channels = new Channel[count];
        for (int i = 0; i < count; i++) {
            channels[i] = new EmbeddedChannel();
            pool.offer(channels[i], KEY);
            assertTrue(pool.removeAll(channels[i]));
        }
        assertEquals(count, partitionSize(pool, KEY), "tombstones linger until the cleaner ticks");

        timer.fire();

        assertEquals(0, partitionSize(pool, KEY), "every tombstone must be unlinked in one tick");
        for (Channel c : channels) {
            assertTrue(c.isActive(), "cleaner must not close tombstoned channels");
        }

        pool.destroy();
    }

    @Test
    public void idleCountPerHostCountsOnlyLeasableChannels() {
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = ttlPool(timer);
        Channel first = channelWithRemoteAddress("example.com");
        Channel second = channelWithRemoteAddress("example.com");
        Channel otherHost = channelWithRemoteAddress("example.org");
        Channel claimed = channelWithRemoteAddress("example.com");

        pool.offer(first, KEY);
        pool.offer(second, KEY);
        pool.offer(otherHost, KEY);
        pool.offer(claimed, KEY);
        assertTrue(pool.removeAll(claimed));

        Map<String, Long> idleCounts = pool.getIdleChannelCountPerHost();

        assertEquals(2, idleCounts.size());
        assertEquals(Long.valueOf(2), idleCounts.get("example.com"));
        assertEquals(Long.valueOf(1), idleCounts.get("example.org"));

        pool.destroy();
    }

    // ---- concurrency: no leaked tombstones, never leases a claimed channel ----

    @Test
    public void concurrentOfferPollRemoveAllIsConsistent() throws Exception {
        // Real timer so the cleaner reaps tombstones concurrently with offer/poll/removeAll.
        // TTL only (idle disabled) so the cleaner never closes our shared EmbeddedChannels cross-thread.
        HashedWheelTimer timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS);
        DefaultChannelPool pool = new DefaultChannelPool(Duration.ZERO, Duration.ofHours(1),
                PoolLeaseStrategy.LIFO, timer, Duration.ofMillis(10));

        final int channelCount = 16;
        Channel[] channels = new Channel[channelCount];
        for (int i = 0; i < channelCount; i++) {
            channels[i] = new EmbeddedChannel();
        }

        final int threads = 4;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ConcurrentLinkedQueue<Channel> leasedInactive = new ConcurrentLinkedQueue<>();
        final CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            Thread worker = new Thread(() -> {
                try {
                    long x = seed + 1;
                    while (!stop.get()) {
                        x = x * 6364136223846793005L + 1442695040888963407L; // xorshift-ish LCG
                        int idx = (int) ((x >>> 33) % channelCount);
                        Channel c = channels[idx];
                        switch ((int) ((x >>> 17) & 3)) {
                            case 0:
                                pool.offer(c, KEY);
                                break;
                            case 1:
                                Channel leased = pool.poll(KEY);
                                if (leased != null && !leased.isActive()) {
                                    leasedInactive.add(leased); // poll must never hand out a dead channel
                                }
                                break;
                            default:
                                pool.removeAll(c);
                                break;
                        }
                    }
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                } finally {
                    done.countDown();
                }
            }, "pool-soak-" + t);
            worker.start();
        }

        Thread.sleep(1500);
        stop.set(true);
        assertTrue(done.await(10, TimeUnit.SECONDS), "workers must finish");

        if (failure.get() != null) {
            fail("worker threw: " + failure.get(), failure.get());
        }
        assertTrue(leasedInactive.isEmpty(), "poll must never lease an inactive channel");

        // Drain leases, then let the cleaner run a couple of ticks and confirm no tombstone leak:
        // every partition deque must collapse to at most the number of distinct channels.
        while (pool.poll(KEY) != null) {
            // drain
        }
        Thread.sleep(60); // a few cleaner ticks
        int size = partitionSize(pool, KEY);
        assertTrue(size <= channelCount, "tombstones must not accumulate unbounded, was " + size);

        pool.destroy();
        timer.stop();
    }

    // ---- helpers ----

    private static Object idleState(Channel channel) throws Exception {
        Field keyField = DefaultChannelPool.class.getDeclaredField("IDLE_STATE_ATTRIBUTE_KEY");
        keyField.setAccessible(true);
        @SuppressWarnings("unchecked")
        io.netty.util.AttributeKey<Object> key = (io.netty.util.AttributeKey<Object>) keyField.get(null);
        return channel.attr(key).get();
    }

    private static Channel channelWithRemoteAddress(String host) {
        return new EmbeddedChannel() {

            private final InetSocketAddress remoteAddress = InetSocketAddress.createUnresolved(host, 443);

            @Override
            protected SocketAddress remoteAddress0() {
                return remoteAddress;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static int partitionSize(DefaultChannelPool pool, Object key) throws Exception {
        Field partitionsField = DefaultChannelPool.class.getDeclaredField("partitions");
        partitionsField.setAccessible(true);
        ConcurrentHashMap<Object, ConcurrentLinkedDeque<Channel>> partitions =
                (ConcurrentHashMap<Object, ConcurrentLinkedDeque<Channel>>) partitionsField.get(pool);
        ConcurrentLinkedDeque<Channel> partition = partitions.get(key);
        return partition == null ? 0 : partition.size();
    }

    /**
     * A {@link Timer} that captures the last-scheduled {@link TimerTask} (the pool's idle cleaner) so a
     * test can fire it synchronously instead of waiting on wall-clock time.
     */
    private static final class CapturingTimer implements Timer {

        private volatile TimerTask task;

        @Override
        public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
            this.task = task;
            return new CapturingTimeout(this, task);
        }

        @Override
        public Set<Timeout> stop() {
            return Collections.emptySet();
        }

        void fire() {
            TimerTask current = task;
            if (current != null) {
                try {
                    current.run(new CapturingTimeout(this, current));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static final class CapturingTimeout implements Timeout {

        private final Timer timer;
        private final TimerTask task;

        CapturingTimeout(Timer timer, TimerTask task) {
            this.timer = timer;
            this.task = task;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean cancel() {
            return false;
        }
    }
}
