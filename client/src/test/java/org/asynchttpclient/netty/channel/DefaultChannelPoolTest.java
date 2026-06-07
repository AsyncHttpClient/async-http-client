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
import java.time.Duration;
import java.util.Collections;
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
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofMillis(1));
        Channel channel = new EmbeddedChannel();

        pool.offer(channel, KEY);
        Thread.sleep(40); // channel is now idle-expired

        // Lease and re-offer it: reset() stamps a fresh start, so the next cleaner pass must spare it.
        assertSame(channel, pool.poll(KEY));
        pool.offer(channel, KEY);

        timer.fire();

        assertTrue(channel.isActive(), "a re-offered (fresh) channel must not be closed by the cleaner");
        assertSame(channel, pool.poll(KEY));

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
