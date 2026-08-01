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
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * White-box unit tests for {@link DefaultChannelPool} covering the bare-channel storage + reused
 * {@code IdleState} attribute (plan 009), the O(1) tombstone {@code removeAll} + tombstone-aware idle
 * cleaner (plan 013) and the generation-scoped cleaner claim (issue #2284). The cleaner is driven
 * deterministically through a capturing {@link Timer}.
 */
public class DefaultChannelPoolTest {

    private static final Object KEY = "partition-key";
    private static final Object OTHER_KEY = "other-partition-key";
    // Deliberately not "pool-..." : the stall gate keys off this prefix, and Netty's timer worker is
    // named by Executors.defaultThreadFactory() as "pool-N-thread-M". Keep the namespaces disjoint.
    private static final String RACE_WORKER_PREFIX = "race-worker-";

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

    // ---- issue #2284: the cleaner must claim exactly the generation it evaluated ----

    @Test
    public void cleanerSparesChannelReofferedWhileItWasMidDecision() throws Exception {
        // Issue #2284, end to end. The cleaner is parked inside its remote-close check, i.e. after it
        // decided the channel is idle-expired and before it claims it; the channel is then leased and
        // re-offered. A FIFO pool is what makes the lease target the stale channel: offer() is offerFirst,
        // so the untouched, fresh channel necessarily sits ahead of the stale one in the cleaner's scan,
        // and a FIFO lease takes from the back.
        final long maxIdle = 1000;
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = new DefaultChannelPool(Duration.ofMillis(maxIdle), Duration.ZERO,
                PoolLeaseStrategy.FIFO, timer, Duration.ofMillis(1));

        BlockingActiveChannel stale = new BlockingActiveChannel();
        assertTrue(pool.offer(stale, KEY));
        Thread.sleep(maxIdle + 100); // this checkout is now reapable

        Channel fresh = new EmbeddedChannel();
        assertTrue(pool.offer(fresh, KEY)); // deque is [fresh, stale], scanned in that order

        stale.armBlock();
        final AtomicReference<Throwable> cleanerFailure = new AtomicReference<>();
        Thread cleaner = new Thread(() -> {
            try {
                timer.fire();
            } catch (Throwable t) {
                cleanerFailure.set(t);
            }
        }, "idle-cleaner");
        cleaner.start();

        assertTrue(stale.awaitBlocked(30, TimeUnit.SECONDS), "cleaner must reach the blocked liveness check");
        assertSame(stale, pool.poll(KEY), "a FIFO lease takes the stale channel from the back");
        assertTrue(pool.offer(stale, KEY), "the re-offer stamps a fresh idle generation");
        stale.unblock();

        cleaner.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(cleaner.isAlive(), "the cleaner pass must finish");
        assertNull(cleanerFailure.get(), () -> "cleaner threw: " + cleanerFailure.get());

        assertTrue(stale.isActive(), "a channel re-offered mid-decision must not be closed on a stale verdict");
        assertTrue(fresh.isActive(), "the channel already scanned must not be touched");
        assertEquals(2, partitionSize(pool, KEY), "both entries must survive the tick");
        assertSame(fresh, pool.poll(KEY), "the untouched channel is still at the back for a FIFO lease");
        assertSame(stale, pool.poll(KEY), "the re-offered channel must still be leasable");
        assertNull(pool.poll(KEY));

        pool.destroy();
    }

    @Test
    public void duplicateOfferIsAcceptedAsANoOp() throws Exception {
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofHours(1));
        Channel channel = new EmbeddedChannel();

        assertTrue(pool.offer(channel, KEY));
        assertTrue(pool.offer(channel, OTHER_KEY),
                "a duplicate offer must not be reported as rejected: the caller closes a rejected channel");

        assertTrue(channel.isActive(), "a duplicate offer must not cost the caller a live keep-alive");
        assertEquals(1, partitionSize(pool, KEY), "the channel must stay pooled exactly once");
        assertFalse(hasPartition(pool, OTHER_KEY), "a rejected offer must not create an empty partition deque");
        assertSame(channel, pool.poll(KEY), "the channel must still be leasable from its original partition");
        assertNull(pool.poll(KEY));

        pool.destroy();
    }

    @Test
    public void leasedChannelCanBeOfferedToAnotherPartition() throws Exception {
        // The generation transfer must not be so strict that it blocks a legitimate re-offer: a lease
        // hands ownership to the caller, so the next offer may pool the channel under any key.
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofHours(1));
        Channel channel = new EmbeddedChannel();

        assertTrue(pool.offer(channel, KEY));
        assertSame(channel, pool.poll(KEY));
        assertTrue(pool.offer(channel, OTHER_KEY));

        assertEquals(0, partitionSize(pool, KEY), "the channel must not be left in its old partition");
        assertEquals(1, partitionSize(pool, OTHER_KEY));
        assertSame(channel, pool.poll(OTHER_KEY));

        pool.destroy();
    }

    @Test
    public void duplicateOfferDoesNotRefreshTheIdleClock() throws Exception {
        // A rejected generation transfer must leave the idle start alone. If it stamped a fresh one, an
        // offer from a thread that does not own the channel would keep an expired connection alive.
        final long maxIdle = 1000;
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = idlePool(timer, Duration.ofMillis(maxIdle));
        Channel channel = new EmbeddedChannel();

        assertTrue(pool.offer(channel, KEY));
        Thread.sleep(maxIdle + 100);
        assertTrue(pool.offer(channel, KEY), "a duplicate offer is accepted as a no-op");

        timer.fire();

        assertFalse(channel.isActive(), "a duplicate offer must not reset the idle clock");
        assertEquals(0, partitionSize(pool, KEY));

        pool.destroy();
    }

    @Test
    public void generationsSharingATimestampAreStillDistinct() {
        // unpreciseMillisTime() is millisecond-grained, so two checkouts can carry the same idle start.
        // The generation counter, not the timestamp, is what identifies a checkout.
        DefaultChannelPool.IdleState idleState = new DefaultChannelPool.IdleState();

        assertTrue(idleState.reset(1000L), "a brand new state is owned by the thread making the first offer");
        long first = idleState.snapshot();
        assertFalse(DefaultChannelPool.IdleState.isOwned(first), "an offered channel is leasable");

        assertTrue(idleState.takeOwnership(), "poll leases it");
        assertTrue(idleState.reset(1000L), "re-offered within the same millisecond");
        long second = idleState.snapshot();

        assertEquals(1000L, idleState.start());
        assertFalse(DefaultChannelPool.IdleState.isOwned(second));
        assertNotEquals(first, second, "checkouts sharing a timestamp must still be distinct generations");
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

    // ---- flushPartitions claims before closing, like the cleaner ----

    @Test
    public void flushPartitionsClosesPooledChannels() {
        DefaultChannelPool pool = noReaperPool();
        Channel flushed = new EmbeddedChannel();
        Channel other = new EmbeddedChannel();

        assertTrue(pool.offer(flushed, KEY));
        assertTrue(pool.offer(other, OTHER_KEY));

        pool.flushPartitions(KEY::equals);

        assertFalse(flushed.isActive(), "a pooled channel in a flushed partition must be closed");
        assertNull(pool.poll(KEY), "the flushed partition must be gone");
        assertTrue(other.isActive(), "a partition the predicate rejects must be untouched");
        assertSame(other, pool.poll(OTHER_KEY));

        pool.destroy();
    }

    @Test
    public void flushPartitionsSkipsAChannelClaimedBySomebodyElse() throws Exception {
        // The state flush has to respect is in-deque AND owned. The motivating case is a concurrent
        // poll(): removing the partition from the map does not stop a caller that already holds the deque
        // reference, and flush's weakly consistent iterator can still see the node that poll unlinked, so
        // closing unconditionally would kill a connection a request is already using. That interleaving
        // cannot be forced deterministically, but a removeAll tombstone reaches the same state - claimed,
        // still linked - and pins the rule: whoever holds the claim owns the close.
        CapturingTimer timer = new CapturingTimer();
        DefaultChannelPool pool = ttlPool(timer);
        Channel pooled = new EmbeddedChannel();
        Channel claimed = new EmbeddedChannel();

        assertTrue(pool.offer(pooled, KEY));
        assertTrue(pool.offer(claimed, KEY));
        assertTrue(pool.removeAll(claimed), "tombstone leaves it claimed but still linked");
        assertEquals(2, partitionSize(pool, KEY));

        pool.flushPartitions(KEY::equals);

        assertTrue(claimed.isActive(), "flush must not close a channel somebody else has claimed");
        assertFalse(pooled.isActive(), "the unclaimed pooled channel must still be closed");

        pool.destroy();
    }

    @Test
    public void flushedChannelIsNotReportedAsPooledByALaterOffer() throws Exception {
        // Flush leaves the channels it closed owned, so the pool never holds one that is closed yet still
        // reads as leasable. Were they left unowned, a later offer would take the accepted-no-op path and
        // return true without pooling anything.
        DefaultChannelPool pool = noReaperPool();
        Channel channel = new EmbeddedChannel();

        assertTrue(pool.offer(channel, KEY));
        pool.flushPartitions(KEY::equals);
        assertFalse(channel.isActive());

        assertTrue(pool.offer(channel, KEY), "the closed channel is owned, so the transfer succeeds");
        assertEquals(1, partitionSize(pool, KEY), "a true return must mean the channel really was pooled");
        assertNull(pool.poll(KEY), "poll still refuses to lease a dead channel");

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
    @org.junit.jupiter.api.Timeout(120) // io.netty.util.Timeout owns the simple name here
    public void concurrentOfferPollRemoveAllIsConsistent() throws Exception {
        // Real timer so the cleaner reaps concurrently with offer/poll/removeAll. Idle expiry is enabled
        // on top of the TTL so the cleaner exercises its claim-and-close path under contention instead of
        // only the tombstone-unlink branch. That is safe for the shared channels the workers lease: to
        // close one the cleaner has to win the claim, and a poll that wins the claim locks the cleaner
        // out for that whole checkout, so poll still never hands out a channel that is being closed.
        // The fixed channel set does decay over the run: a channel that no poll reaches within maxIdle is
        // closed, and re-offering it only puts a corpse back in the deque, so the population of leasable
        // channels shrinks. That is why leaseCount is asserted - it stops this test from silently
        // degenerating into "every poll returned null", which would make the assertions below vacuous.
        // Sustained leasing pressure against a live cleaner is covered by
        // concurrentPollAndOfferNeverStrandALiveChannel, which replaces the channels it drains.
        final long maxIdle = 500;
        HashedWheelTimer timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS);
        DefaultChannelPool pool = new DefaultChannelPool(Duration.ofMillis(maxIdle), Duration.ofHours(1),
                PoolLeaseStrategy.LIFO, timer, Duration.ofMillis(10));

        // Its own partition, never touched by the workers, so it ages out untouched: its close proves the
        // cleaner really claimed and closed an idle-expired channel while the workers were hammering it.
        EmbeddedChannel expiring = new EmbeddedChannel();
        assertTrue(pool.offer(expiring, OTHER_KEY));

        final int channelCount = 16;
        Channel[] channels = new Channel[channelCount];
        for (int i = 0; i < channelCount; i++) {
            channels[i] = new EmbeddedChannel();
        }

        final int threads = 4;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ConcurrentLinkedQueue<Channel> leasedInactive = new ConcurrentLinkedQueue<>();
        final AtomicLong leaseCount = new AtomicLong();
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
                                if (leased != null) {
                                    leaseCount.incrementAndGet();
                                    if (!leased.isActive()) {
                                        leasedInactive.add(leased); // poll must never hand out a dead channel
                                    }
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

        try {
            Thread.sleep(1500);
            stop.set(true);
            assertTrue(done.await(10, TimeUnit.SECONDS), "workers must finish");

            if (failure.get() != null) {
                fail("worker threw: " + failure.get(), failure.get());
            }
            assertTrue(leasedInactive.isEmpty(), "poll must never lease an inactive channel");
            // A healthy run leases thousands; this floor is orders of magnitude below that, but high
            // enough to fail if leasing collapses early instead of merely decaying.
            assertTrue(leaseCount.get() >= channelCount * 10L,
                    "the run must have leased real channels, otherwise the assertions above are vacuous; was " + leaseCount);
            assertTrue(expiring.closeFuture().await(30, TimeUnit.SECONDS),
                    "the cleaner must have claimed and closed the idle-expired channel");

            // Drain leases, then let the cleaner run a couple of ticks and confirm no tombstone leak:
            // every partition deque must collapse to at most the number of distinct channels.
            while (pool.poll(KEY) != null) {
                // drain
            }
            Thread.sleep(60); // a few cleaner ticks
            int size = partitionSize(pool, KEY);
            assertTrue(size <= channelCount, "tombstones must not accumulate unbounded, was " + size);
            assertEquals(0, partitionSize(pool, OTHER_KEY), "the closed channel must be unlinked");
        } finally {
            // Any assertion above throwing would otherwise leave the workers running and a 10ms-period
            // timer reaping a live pool for the rest of the fork, spraying cleaner DEBUG lines into every
            // test class that follows.
            stop.set(true);
            pool.destroy();
            timer.stop();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(120) // io.netty.util.Timeout owns the simple name here
    public void concurrentPollAndOfferNeverStrandALiveChannel() throws Exception {
        // Issue #2284 race B, detected by accounting at quiescence rather than by pausing inside the
        // window. If the cleaner ever owns a generation it never evaluated, a concurrent poll that has
        // already unlinked the node fails its own claim and drops the channel, and the cleaner then
        // releases the claim it should never have taken. The channel is left alive, unowned and in no
        // partition at all: a permanent loss that survives to quiescence, where it is countable.
        //
        // Workers only poll-then-offer (never removeAll) and always complete the cycle before exiting, so
        // the invariant is exact: at quiescence every channel ever created is either still pooled or was
        // closed by the cleaner. Anything else was stranded. Workers mint a replacement whenever the pool
        // runs dry, which keeps real leasing pressure on the cleaner for the whole run.
        // YieldingChannel stalls only the cleaner thread inside its liveness check, widening the
        // decide -> claim window; that is a test-only subclass, exactly like BlockingActiveChannel, and
        // needs no hook in production code.
        //
        // This is a soak, not a deterministic test: its failure mode is a false negative, never a false
        // positive. With the generation-scoped claim the count is 0 by construction, because
        // tryTakeOwnership cannot own a generation it did not evaluate. Against the "hoist the snapshot,
        // claim, compare, release" variant it fails in the large majority of runs (independently measured
        // 9 of 12 and 7 of 11 on a loaded 2-core box); the fix itself passed every run measured, both
        // plain and under the jacoco agent that ./mvnw verify attaches. Treat the ratio as a range, not a
        // gate: it moves with the harness and the machine.
        //
        // The stall widens the cleaner's decide -> claim window, but it is NOT a floor. A shorter stall
        // measured at least as well (100us and a bare yield both scored higher than 1ms), because the
        // cleaner is single-threaded: every stall blocks the whole pass, so race opportunities are capped
        // at wall-time / stall-duration. A re-offer landing in the same millisecond as the verdict leaves
        // start unchanged, which makes that variant close the channel rather than strand it - invisible to
        // this accounting - but that is a fraction of occurrences, not a gate on detection.
        final int seedChannels = 64;
        final int threads = 8;
        final long soakMillis = 4000;
        HashedWheelTimer timer = new HashedWheelTimer(1, TimeUnit.MILLISECONDS);
        // 1ms idle timeout and a 1ms cleaner period: every pooled channel reads as expired on every tick,
        // so the cleaner is permanently in its decide -> claim path, which is where the race lives.
        DefaultChannelPool pool = new DefaultChannelPool(Duration.ofMillis(1), Duration.ofHours(1),
                PoolLeaseStrategy.LIFO, timer, Duration.ofMillis(1));

        final Queue<Channel> created = new ConcurrentLinkedQueue<>();
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(threads);
        boolean timerStopped = false;

        // Nothing may throw between arming the stall and entering the try, or it would stay armed for the
        // rest of the class. Only YieldingChannel reads it, and this is the only test that creates one.
        YieldingChannel.stalling(true);
        try {
            for (int i = 0; i < seedChannels; i++) {
                Channel seed = new YieldingChannel();
                created.add(seed);
                assertTrue(pool.offer(seed, KEY));
            }

            for (int t = 0; t < threads; t++) {
                Thread worker = new Thread(() -> {
                    try {
                        while (!stop.get()) {
                            Channel channel = pool.poll(KEY);
                            if (channel == null) {
                                channel = new YieldingChannel();
                                created.add(channel);
                            }
                            // Always complete the cycle before re-checking stop: a channel left leased at
                            // quiescence would read as a loss and fail this test for the wrong reason.
                            pool.offer(channel, KEY);
                        }
                    } catch (Throwable th) {
                        failure.compareAndSet(null, th);
                    } finally {
                        done.countDown();
                    }
                }, RACE_WORKER_PREFIX + t);
                worker.start();
            }

            Thread.sleep(soakMillis);
            stop.set(true);
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers must finish");

            if (failure.get() != null) {
                fail("worker threw: " + failure.get(), failure.get());
            }

            // Freeze the cleaner before counting anything, otherwise a channel closed between the two
            // counting loops below lands in neither bucket and reads as a loss. HashedWheelTimer.stop()
            // interrupts and joins its worker, so once it returns no pass is in flight and the channel
            // states are frozen; that join is the barrier, so no sleep is needed here. A pass caught
            // mid-flight cannot reschedule itself, and the resulting IllegalStateException is caught and
            // logged by Netty's HashedTimeout.expire().
            YieldingChannel.stalling(false); // the accounting below runs isActive() on this thread too
            timer.stop();
            timerStopped = true;

            // Three buckets, so that the only thing left over is the race-B signature itself: a channel
            // that is alive, owned by nobody, and in no partition. Counting a claimed-but-not-yet-closed
            // channel as a loss would be a false positive - the cleaner closes inside a catch-all, so a
            // claim whose close did not take effect leaves the channel alive and owned, which is not a
            // strand. A strand cannot hide in that bucket: the variant this test exists to catch releases
            // its claim, which is precisely what makes the channel unreachable.
            long closedByCleaner = 0;
            long claimedNotClosed = 0;
            for (Channel channel : created) {
                if (!channel.isActive()) {
                    closedByCleaner++;
                } else if (((DefaultChannelPool.IdleState) idleState(channel)).isOwned()) {
                    claimedNotClosed++;
                }
            }
            long stillPooled = 0;
            while (pool.poll(KEY) != null) {
                stillPooled++;
            }

            long lost = created.size() - closedByCleaner - claimedNotClosed - stillPooled;
            assertEquals(0, lost, "a live channel is in no partition and owned by nobody: the cleaner claimed "
                    + "a generation it never evaluated and a concurrent poll dropped the node (created="
                    + created.size() + " closedByCleaner=" + closedByCleaner + " claimedNotClosed="
                    + claimedNotClosed + " stillPooled=" + stillPooled + ")");
        } finally {
            YieldingChannel.stalling(false);
            stop.set(true);
            pool.destroy();
            if (!timerStopped) {
                timer.stop();
            }
        }
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
    private static ConcurrentHashMap<Object, ConcurrentLinkedDeque<Channel>> partitions(DefaultChannelPool pool)
            throws Exception {
        Field partitionsField = DefaultChannelPool.class.getDeclaredField("partitions");
        partitionsField.setAccessible(true);
        return (ConcurrentHashMap<Object, ConcurrentLinkedDeque<Channel>>) partitionsField.get(pool);
    }

    private static int partitionSize(DefaultChannelPool pool, Object key) throws Exception {
        ConcurrentLinkedDeque<Channel> partition = partitions(pool).get(key);
        return partition == null ? 0 : partition.size();
    }

    private static boolean hasPartition(DefaultChannelPool pool, Object key) throws Exception {
        return partitions(pool).containsKey(key);
    }

    /**
     * An {@link EmbeddedChannel} whose liveness check occasionally stalls the idle cleaner, widening the
     * window between its expiry decision and its claim. Only the cleaner is stalled: worker threads are
     * recognised by their name, and the whole thing is disarmed once a run is over so the accounting pass
     * on the test thread runs at full speed.
     */
    private static final class YieldingChannel extends EmbeddedChannel {

        // Static, so the constructor's own isActive() call cannot read it before it is initialised - the
        // trap BlockingActiveChannel has to null-guard around.
        private static final AtomicBoolean STALLING = new AtomicBoolean(false);

        static void stalling(boolean enabled) {
            STALLING.set(enabled);
        }

        @Override
        public boolean isActive() {
            if (STALLING.get() && !Thread.currentThread().getName().startsWith(RACE_WORKER_PREFIX)
                    && ThreadLocalRandom.current().nextInt(8) == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.isActive();
        }
    }

    /**
     * An {@link EmbeddedChannel} whose liveness check parks once, letting a test stop the idle cleaner
     * exactly between its expiry decision and its claim.
     */
    private static final class BlockingActiveChannel extends EmbeddedChannel {

        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean isActive() {
            // EmbeddedChannel's constructor registers the channel, which calls this override before this
            // class's own final fields are assigned: the null check is load-bearing, not always-true.
            // Without it that call NPEs inside a DefaultPromise listener, which swallows the failure.
            if (armed != null && armed.compareAndSet(true, false)) {
                blocked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.isActive();
        }

        void armBlock() {
            armed.set(true);
        }

        boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException {
            return blocked.await(timeout, unit);
        }

        void unblock() {
            release.countDown();
        }
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
