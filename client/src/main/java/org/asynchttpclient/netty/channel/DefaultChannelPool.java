/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.channel.ChannelPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Predicate;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

/**
 * A simple implementation of {@link ChannelPool} based on a {@link ConcurrentHashMap}
 */
public final class DefaultChannelPool implements ChannelPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultChannelPool.class);
    private static final AttributeKey<ChannelCreation> CHANNEL_CREATION_ATTRIBUTE_KEY = AttributeKey.valueOf("channelCreation");
    private static final AttributeKey<IdleState> IDLE_STATE_ATTRIBUTE_KEY = AttributeKey.valueOf("channelIdleState");

    // The partition deques hold the bare Channel; per-checkout idle state (start timestamp + the
    // ownership/generation word) lives on the channel's IDLE_STATE_ATTRIBUTE_KEY attribute, which is
    // allocated once per physical connection and reused across every pool cycle (no per-offer holder).
    private final ConcurrentHashMap<Object, ConcurrentLinkedDeque<Channel>> partitions = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicBoolean rejectedOfferLogged = new AtomicBoolean(false);
    private final Timer nettyTimer;
    private final long connectionTtl;
    private final boolean connectionTtlEnabled;
    private final long maxIdleTime;
    private final boolean maxIdleTimeEnabled;
    private final long cleanerPeriod;
    private final PoolLeaseStrategy poolLeaseStrategy;

    public DefaultChannelPool(AsyncHttpClientConfig config, Timer hashedWheelTimer) {
        this(config.getPooledConnectionIdleTimeout(),
                config.getConnectionTtl(),
                hashedWheelTimer,
                config.getConnectionPoolCleanerPeriod());
    }

    public DefaultChannelPool(Duration maxIdleTime, Duration connectionTtl, Timer nettyTimer, Duration cleanerPeriod) {
        this(maxIdleTime, connectionTtl, PoolLeaseStrategy.LIFO, nettyTimer, cleanerPeriod);
    }

    public DefaultChannelPool(Duration maxIdleTime, Duration connectionTtl, PoolLeaseStrategy poolLeaseStrategy, Timer nettyTimer, Duration cleanerPeriod) {
        final long maxIdleTimeInMs = maxIdleTime.toMillis();
        final long connectionTtlInMs = connectionTtl.toMillis();
        final long cleanerPeriodInMs = cleanerPeriod.toMillis();
        this.maxIdleTime = maxIdleTimeInMs;
        this.connectionTtl = connectionTtlInMs;
        connectionTtlEnabled = connectionTtlInMs > 0;
        this.nettyTimer = nettyTimer;
        maxIdleTimeEnabled = maxIdleTimeInMs > 0;
        this.poolLeaseStrategy = poolLeaseStrategy;

        this.cleanerPeriod = Math.min(cleanerPeriodInMs, Math.min(connectionTtlEnabled ? connectionTtlInMs : Integer.MAX_VALUE,
                maxIdleTimeEnabled ? maxIdleTimeInMs : Integer.MAX_VALUE));

        if (connectionTtlEnabled || maxIdleTimeEnabled) {
            scheduleNewIdleChannelDetector(new IdleChannelDetector());
        }
    }

    private void scheduleNewIdleChannelDetector(TimerTask task) {
        nettyTimer.newTimeout(task, cleanerPeriod, TimeUnit.MILLISECONDS);
    }

    private boolean isTtlExpired(Channel channel, long now) {
        if (!connectionTtlEnabled) {
            return false;
        }

        ChannelCreation creation = channel.attr(CHANNEL_CREATION_ATTRIBUTE_KEY).get();
        return creation != null && now - creation.creationTime >= connectionTtl;
    }

    @Override
    public boolean offer(Channel channel, Object partitionKey) {
        if (isClosed.get()) {
            return false;
        }

        long now = unpreciseMillisTime();

        if (isTtlExpired(channel, now)) {
            return false;
        }

        // "accepted", not "offered": offer0 also returns true for a channel that was already pooled, so
        // this flag means the pool took responsibility for the channel, not that this call linked it.
        // registerChannelCreation is safe under that weaker meaning because it only sets when absent.
        boolean accepted = offer0(channel, partitionKey, now);
        if (connectionTtlEnabled && accepted) {
            registerChannelCreation(channel, partitionKey, now);
        }

        return accepted;
    }

    private boolean offer0(Channel channel, Object partitionKey, long now) {
        // Reuse the channel's IdleState instead of allocating a holder per offer; reset() stamps the
        // idle start and publishes the next, leasable generation (which happens-before offerFirst
        // publishes the channel, so any thread that observes it in the deque also observes it unowned).
        // setIfAbsent, not set: two concurrent first offers of the same channel would otherwise each
        // install their own IdleState, the second clobbering the first, after which both would transfer
        // their own generation and link the channel twice. Both must end up on one shared state so that
        // exactly one of them wins the transfer below.
        Attribute<IdleState> idleStateAttribute = channel.attr(IDLE_STATE_ATTRIBUTE_KEY);
        IdleState idleState = idleStateAttribute.get();
        if (idleState == null) {
            IdleState created = new IdleState();
            IdleState raced = idleStateAttribute.setIfAbsent(created);
            idleState = raced == null ? created : raced;
        }

        if (!idleState.reset(now)) {
            // No generation to transfer: the channel is already pooled (so this is a duplicate offer), or
            // a concurrent offer of the same channel won the reservation. Adding it would double-link it
            // in a deque, so treat this as an accepted no-op and leave the existing generation and
            // placement alone. Returning false would be worse than useless: ChannelManager closes a
            // rejected channel, so a duplicate offer would kill a live pooled keep-alive.
            if (LOGGER.isDebugEnabled() && rejectedOfferLogged.compareAndSet(false, true)) {
                LOGGER.debug("Ignoring offer of channel {} for partition {}: it is already pooled, or a " +
                        "concurrent offer is pooling it. Further occurrences are not logged.", channel, partitionKey);
            }
            return true;
        }

        // Resolve the partition only once the generation transfer succeeded, so a rejected offer cannot
        // leave an empty deque behind for the cleaner to walk on every tick. Keep the plain get() fast
        // path: computeIfAbsent only returns lock-free when the key is its bin's head node, and otherwise
        // locks the bin (or joins a resize), which get() never does.
        ConcurrentLinkedDeque<Channel> partition = partitions.get(partitionKey);
        if (partition == null) {
            partition = partitions.computeIfAbsent(partitionKey, pk -> new ConcurrentLinkedDeque<>());
        }
        return partition.offerFirst(channel);
    }

    private static void registerChannelCreation(Channel channel, Object partitionKey, long now) {
        Attribute<ChannelCreation> channelCreationAttribute = channel.attr(CHANNEL_CREATION_ATTRIBUTE_KEY);
        if (channelCreationAttribute.get() == null) {
            channelCreationAttribute.set(new ChannelCreation(now, partitionKey));
        }
    }

    @Override
    public Channel poll(Object partitionKey) {
        ConcurrentLinkedDeque<Channel> partition = partitions.get(partitionKey);
        if (partition == null) {
            return null;
        }

        for (; ; ) {
            Channel channel = poolLeaseStrategy.lease(partition);
            if (channel == null) {
                // pool is empty
                return null;
            }

            if (!Channels.isChannelActive(channel)) {
                LOGGER.trace("Channel is inactive, probably remotely closed!");
                continue;
            }

            IdleState idleState = channel.attr(IDLE_STATE_ATTRIBUTE_KEY).get();
            if (idleState == null || !idleState.takeOwnership()) {
                LOGGER.trace("Couldn't take ownership of channel, probably in the process of being expired!");
                continue;
            }

            return channel;
        }
    }

    @Override
    public boolean removeAll(Channel channel) {
        if (isClosed.get() || !connectionTtlEnabled) {
            return false;
        }

        // O(1) tombstone instead of an O(n) ConcurrentLinkedDeque value scan: claim the channel's
        // IdleState. A claimed channel is skipped by poll() (its takeOwnership fails) and physically
        // unlinked by the idle cleaner on its next tick. removeAll only acts when connectionTtlEnabled,
        // which guarantees the cleaner is scheduled (see constructor), so a tombstone is never orphaned.
        // Returns true only when this call transitions an idle, leasable channel to claimed — matching
        // the old "the channel was present in the pool" contract.
        IdleState idleState = channel.attr(IDLE_STATE_ATTRIBUTE_KEY).get();
        return idleState != null && idleState.takeOwnership();
    }

    @Override
    public boolean isOpen() {
        return !isClosed.get();
    }

    @Override
    public void destroy() {
        if (isClosed.getAndSet(true)) {
            return;
        }

        partitions.clear();
    }

    private static void close(Channel channel) {
        // FIXME pity to have to do this here
        Channels.setDiscard(channel);
        Channels.silentlyCloseChannel(channel);
    }

    private void flushPartition(Object partitionKey, ConcurrentLinkedDeque<Channel> partition) {
        if (partition != null) {
            partitions.remove(partitionKey);
            for (Channel channel : partition) {
                // Claim before closing, the same rule the idle cleaner follows. Removing the partition
                // from the map does not stop a concurrent poll(): a caller that read the deque reference
                // first can still lease out of it, and this iterator can still see a node that poll has
                // unlinked. Closing unconditionally would then kill a connection a request is already
                // using. Losing the claim means somebody else owns the channel -- a lessee, a removeAll
                // tombstone, or the cleaner -- and closing it is their responsibility, not ours.
                // Winning the claim also leaves the channel owned, so the pool never holds a channel that
                // is closed yet still reads as leasable.
                IdleState idleState = channel.attr(IDLE_STATE_ATTRIBUTE_KEY).get();
                if (idleState == null || idleState.takeOwnership()) {
                    close(channel);
                }
            }
        }
    }

    @Override
    public void flushPartitions(Predicate<Object> predicate) {
        for (Map.Entry<Object, ConcurrentLinkedDeque<Channel>> partitionsEntry : partitions.entrySet()) {
            Object partitionKey = partitionsEntry.getKey();
            if (predicate.test(partitionKey)) {
                flushPartition(partitionKey, partitionsEntry.getValue());
            }
        }
    }

    @Override
    public Map<String, Long> getIdleChannelCountPerHost() {
        Map<String, Long> idleChannelsPerHost = new HashMap<>();
        for (ConcurrentLinkedDeque<Channel> partition : partitions.values()) {
            for (Channel channel : partition) {
                // Skip channels that have been claimed (removeAll tombstone, a node a concurrent poll
                // already leased, or an offer mid-transfer) but not yet unlinked, so the count reflects
                // leasable channels.
                if (isLeasable(channel)) {
                    SocketAddress remoteAddress = channel.remoteAddress();
                    if (remoteAddress.getClass() == InetSocketAddress.class) {
                        String host = ((InetSocketAddress) remoteAddress).getHostString();
                        Long currentCount = idleChannelsPerHost.get(host);
                        idleChannelsPerHost.put(host, currentCount == null ? 1L : currentCount + 1L);
                    }
                }
            }
        }
        return idleChannelsPerHost;
    }

    private static boolean isLeasable(Channel channel) {
        IdleState idleState = channel.attr(IDLE_STATE_ATTRIBUTE_KEY).get();
        return idleState != null && !idleState.isOwned();
    }

    public enum PoolLeaseStrategy {
        LIFO {
            @Override
            public <E> E lease(Deque<E> d) {
                return d.pollFirst();
            }
        },
        FIFO {
            @Override
            public <E> E lease(Deque<E> d) {
                return d.pollLast();
            }
        };

        abstract <E> E lease(Deque<E> d);
    }

    private static final class ChannelCreation {
        final long creationTime;
        final Object partitionKey;

        ChannelCreation(long creationTime, Object partitionKey) {
            this.creationTime = creationTime;
            this.partitionKey = partitionKey;
        }
    }

    /**
     * Per-channel idle bookkeeping. Allocated once and stashed on the channel's
     * {@link #IDLE_STATE_ATTRIBUTE_KEY} attribute, then reused across every pool checkout so no holder
     * is allocated per offer.
     *
     * <p>{@code state} packs an ownership flag ({@link #OWNED}, bit 0), a transfer-in-progress flag
     * ({@link #RESETTING}, bit 1) and a generation counter (all remaining bits). Because the two flags sit
     * in the low bits, the counter advances by {@link #GENERATION_INCREMENT} = 4 on every offer rather
     * than by one. A generation identifies one idle checkout and {@code start} is the millisecond
     * timestamp at which that checkout became idle. The counter, not the timestamp, is the identity:
     * {@code unpreciseMillisTime()} is millisecond-grained, so two consecutive checkouts can carry the
     * same {@code start}.
     *
     * <p>Owned means "claimed, do not lease". It has five producers: the initial state (the thread about
     * to make the first offer owns the channel, so that offer is a legal transfer), a successful
     * {@code poll()} lease, a {@code removeAll(Channel)} tombstone, the idle cleaner's pre-close claim,
     * and a transfer in progress ({@link #RESETTING} is only ever set on top of {@link #OWNED}, so a
     * mid-reset entry reads as owned and is therefore neither leased, counted as idle, nor closed). The
     * pool upholds the invariant that a channel sitting in a partition deque is unowned unless it was
     * tombstoned, is being leased by a concurrent {@code poll()}, or is being closed by the cleaner:
     * {@link #reset(long)} publishes the next, unowned generation before {@code offerFirst} publishes the
     * channel, and {@code poll()} unlinks a channel from the deque before claiming it.
     */
    static final class IdleState {

        private static final long OWNED = 1L;
        private static final long RESETTING = 1L << 1;
        private static final long GENERATION_INCREMENT = 1L << 2;

        private static final AtomicLongFieldUpdater<IdleState> STATE_UPDATER =
                AtomicLongFieldUpdater.newUpdater(IdleState.class, "state");

        private volatile long start;
        private volatile long state = OWNED;

        long start() {
            return start;
        }

        /** The current state word, to be passed back to {@link #tryTakeOwnership(long)}. */
        long snapshot() {
            return state;
        }

        static boolean isOwned(long stateSnapshot) {
            return (stateSnapshot & OWNED) != 0;
        }

        boolean isOwned() {
            return isOwned(state);
        }

        /**
         * Claim whichever generation is current, for a caller that already holds the channel (a
         * {@code poll()} that unlinked it) or wants to tombstone it ({@code removeAll}). Returns true
         * only for the caller that transitions an unowned generation to owned; a lost CAS is reported as
         * failure rather than retried, since the only transition out of an unowned generation is to
         * owned, so a lost CAS means somebody else claimed it.
         *
         * <p>Reading {@code state} at call time can observe a generation published by a re-offer, which
         * has linked a fresh node. That is benign rather than a double lease: a {@code poll()} claim has
         * already unlinked its own node, so the only claimer that can be followed by a re-offer is
         * {@code removeAll}, and {@link #reset(long)} refuses a second transfer of the same generation.
         * The worst case is a channel leased while a stale node lingers, which the next {@code poll()} or
         * cleaner tick unlinks.
         */
        boolean takeOwnership() {
            return tryTakeOwnership(state);
        }

        /**
         * Claim exactly the generation {@code stateSnapshot} was taken from, in one atomic step. Fails,
         * without ever owning the channel even transiently, if the channel was claimed or leased and
         * re-offered since the snapshot was taken.
         */
        boolean tryTakeOwnership(long stateSnapshot) {
            return !isOwned(stateSnapshot) && STATE_UPDATER.compareAndSet(this, stateSnapshot, stateSnapshot | OWNED);
        }

        /**
         * Stamp a fresh idle start and publish the next generation, transferring ownership to the pool.
         * Called on every offer. The guard is on the generation, not on the caller's identity: an unowned
         * generation is already pooled, so it is refused rather than stamped with a second idle start and
         * linked into a deque twice. A generation owned by somebody else is still transferable, which is
         * what lets a lessee hand the channel back. Reserving the transfer with {@link #RESETTING} keeps the entry unleasable while
         * {@code start} is written and makes a second transfer of the same generation fail rather than
         * rewind the idle clock. Publishing {@code state} last is sufficient for a reader that observes
         * the new generation to also observe the new {@code start}, since volatile accesses are totally
         * ordered in the synchronization order (JLS 17.4.4, 17.4.7).
         *
         * @return false if there was no owned generation to transfer, or a concurrent transfer of it won
         * the reservation, in which case nothing changed
         */
        boolean reset(long now) {
            long stateSnapshot = state;
            if (!isOwned(stateSnapshot) || (stateSnapshot & RESETTING) != 0
                    || !STATE_UPDATER.compareAndSet(this, stateSnapshot, stateSnapshot | RESETTING)) {
                return false;
            }
            start = now;
            state = (stateSnapshot & ~(OWNED | RESETTING)) + GENERATION_INCREMENT;
            return true;
        }
    }

    private final class IdleChannelDetector implements TimerTask {

        private boolean isIdleTimeoutExpired(long idleStart, long now) {
            return maxIdleTimeEnabled && now - idleStart >= maxIdleTime;
        }

        @Override
        public void run(Timeout timeout) {

            if (isClosed.get()) {
                return;
            }

            if (LOGGER.isDebugEnabled()) {
                for (Map.Entry<Object, ConcurrentLinkedDeque<Channel>> entry : partitions.entrySet()) {
                    int size = entry.getValue().size();
                    if (size > 0) {
                        LOGGER.debug("Entry count for : {} : {}", entry.getKey(), size);
                    }
                }
            }

            long start = unpreciseMillisTime();
            int closedCount = 0;
            int totalCount = 0;

            for (ConcurrentLinkedDeque<Channel> partition : partitions.values()) {
                if (LOGGER.isDebugEnabled()) {
                    totalCount += partition.size();
                }

                if (partition.isEmpty()) {
                    continue;
                }

                closedCount += reapPartition(partition, start);
            }

            if (LOGGER.isDebugEnabled()) {
                long duration = unpreciseMillisTime() - start;
                if (closedCount > 0) {
                    LOGGER.debug("Closed {} connections out of {} in {} ms", closedCount, totalCount, duration);
                }
            }

            scheduleNewIdleChannelDetector(timeout.task());
        }

        /**
         * One pass over a partition. A channel is dropped from the deque when it is a
         * {@code removeAll(Channel)} tombstone, remotely closed, idle-timeout expired or TTL expired.
         * Tombstoned/concurrently leased channels are only unlinked (their owner closes them); expired
         * channels are closed here, but only after this cleaner exclusively claims them, so a channel that
         * {@code poll()} is leasing concurrently is never closed. Returns the number of channels closed by
         * this tick.
         *
         * <p>Drop-worthy channels are unlinked in place through the iterator (O(1) amortized each) as the
         * scan reaches them. The earlier approach collected them into a list and called
         * {@link java.util.concurrent.ConcurrentLinkedDeque#removeAll(java.util.Collection) removeAll} after
         * the scan, which re-walks every node doing an O(m) list {@code contains()} per node — O(n*m),
         * degenerating toward O(n^2) when a whole partition is dropped in one tick (a load spike's
         * connections idling out together, or a peer dropping many keep-alives at once). Unlinking via the
         * iterator keeps the whole pass O(n).
         */
        private int reapPartition(ConcurrentLinkedDeque<Channel> partition, long now) {
            int closed = 0;

            Iterator<Channel> it = partition.iterator();
            while (it.hasNext()) {
                Channel channel = it.next();
                IdleState idleState = channel.attr(IDLE_STATE_ATTRIBUTE_KEY).get();
                if (idleState == null) {
                    continue;
                }

                long stateSnapshot = idleState.snapshot();
                if (IdleState.isOwned(stateSnapshot)) {
                    // In-deque + owned ==> a removeAll(Channel) tombstone, a node a concurrent poll() has
                    // already leased and unlinked, or an offer whose transfer is in progress. Either way:
                    // unlink, never close: whoever holds the claim is responsible for the channel.
                    // Unlinking an already-unlinked node through the iterator is a harmless no-op.
                    it.remove();
                    continue;
                }

                boolean isIdleTimeoutExpired = isIdleTimeoutExpired(idleState.start(), now);
                boolean isRemotelyClosed = !Channels.isChannelActive(channel);
                boolean isTtlExpired = isTtlExpired(channel, now);
                if (!isIdleTimeoutExpired && !isRemotelyClosed && !isTtlExpired) {
                    continue; // healthy idle channel, leave it for poll()
                }

                // Claim exactly the generation this verdict was computed from, in a single atomic step:
                // the channel is never closed on a verdict that a lease + re-offer has invalidated, and a
                // generation this cleaner did not evaluate is never even transiently owned (which would
                // starve a concurrent poll() into dropping a live channel). The counter only ever
                // advances, so a successful claim also proves the start read above belongs to it.
                if (!idleState.tryTakeOwnership(stateSnapshot)) {
                    continue; // leased, tombstoned or re-offered meanwhile; that owner now handles it
                }

                LOGGER.debug("Closing Idle Channel {} isIdleTimeoutExpired={} isRemotelyClosed={} isTtlExpired={}",
                        channel, isIdleTimeoutExpired, isRemotelyClosed, isTtlExpired);
                close(channel);
                closed++;
                it.remove();
            }

            return closed;
        }
    }
}
