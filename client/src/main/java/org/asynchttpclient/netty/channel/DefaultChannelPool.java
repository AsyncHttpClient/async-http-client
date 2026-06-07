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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

/**
 * A simple implementation of {@link ChannelPool} based on a {@link ConcurrentHashMap}
 */
public final class DefaultChannelPool implements ChannelPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultChannelPool.class);
    private static final AttributeKey<ChannelCreation> CHANNEL_CREATION_ATTRIBUTE_KEY = AttributeKey.valueOf("channelCreation");
    private static final AttributeKey<IdleState> IDLE_STATE_ATTRIBUTE_KEY = AttributeKey.valueOf("channelIdleState");

    // The partition deques hold the bare Channel; per-checkout idle state (start timestamp + the
    // owned/tombstone CAS flag) lives on the channel's IDLE_STATE_ATTRIBUTE_KEY attribute, which is
    // allocated once per physical connection and reused across every pool cycle (no per-offer holder).
    private final ConcurrentHashMap<Object, ConcurrentLinkedDeque<Channel>> partitions = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
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

        boolean offered = offer0(channel, partitionKey, now);
        if (connectionTtlEnabled && offered) {
            registerChannelCreation(channel, partitionKey, now);
        }

        return offered;
    }

    private boolean offer0(Channel channel, Object partitionKey, long now) {
        ConcurrentLinkedDeque<Channel> partition = partitions.get(partitionKey);
        if (partition == null) {
            partition = partitions.computeIfAbsent(partitionKey, pk -> new ConcurrentLinkedDeque<>());
        }
        // Reuse the channel's IdleState instead of allocating a holder per offer; reset() stamps the
        // idle start and clears the owned flag (must happen-before offerFirst publishes the channel,
        // so any thread that observes it in the deque also observes owned == 0).
        Attribute<IdleState> idleStateAttribute = channel.attr(IDLE_STATE_ATTRIBUTE_KEY);
        IdleState idleState = idleStateAttribute.get();
        if (idleState == null) {
            idleState = new IdleState();
            idleStateAttribute.set(idleState);
        }
        idleState.reset(now);
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
                close(channel);
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
        return partitions
                .values()
                .stream()
                .flatMap(ConcurrentLinkedDeque::stream)
                // Skip channels that have been claimed (removeAll tombstone, or a node a concurrent
                // poll already leased) but not yet unlinked, so the count reflects leasable channels.
                .filter(DefaultChannelPool::isLeasable)
                .map(Channel::remoteAddress)
                .filter(a -> a.getClass() == InetSocketAddress.class)
                .map(a -> (InetSocketAddress) a)
                .map(InetSocketAddress::getHostString)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
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
     * <p>{@code owned} is a single CAS flag with two roles, both meaning "this idle entry is claimed,
     * do not lease it": a successful {@code poll()} lease, or a {@code removeAll()} tombstone. The pool
     * upholds the invariant that a channel sitting in a partition deque has {@code owned == 0} unless it
     * was tombstoned, because {@link #reset(long)} clears the flag before {@code offerFirst} publishes
     * the channel and {@code poll()} unlinks a channel from the deque before claiming it. {@code start}
     * doubles as a generation token: it changes on every offer, letting the cleaner detect a channel
     * that was leased and re-offered between its expiry check and its claim.
     */
    static final class IdleState {

        private static final AtomicIntegerFieldUpdater<IdleState> OWNED_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(IdleState.class, "owned");

        private volatile long start;
        @SuppressWarnings("unused")
        private volatile int owned;

        long start() {
            return start;
        }

        boolean isOwned() {
            return owned != 0;
        }

        /** Atomically claim this entry; returns true only for the caller that transitions 0 -> 1. */
        boolean takeOwnership() {
            return OWNED_UPDATER.getAndSet(this, 1) == 0;
        }

        /** Undo a claim taken via {@link #takeOwnership()} (used only on the cleaner re-offer race). */
        void releaseOwnership() {
            owned = 0;
        }

        /** Stamp the idle start and mark the channel leasable again. Called on every offer. */
        void reset(long now) {
            start = now;
            owned = 0;
        }
    }

    private final class IdleChannelDetector implements TimerTask {

        private boolean isIdleTimeoutExpired(IdleState idleState, long now) {
            return maxIdleTimeEnabled && now - idleState.start() >= maxIdleTime;
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

                // store in intermediate unsynchronized lists to minimize
                // the impact on the ConcurrentLinkedDeque
                if (LOGGER.isDebugEnabled()) {
                    totalCount += partition.size();
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
         * One pass over a partition. A channel is dropped from the deque when it is a removeAll
         * tombstone, remotely closed, idle-timeout expired or TTL expired. Tombstoned/concurrently
         * leased channels are only unlinked (their owner closes them); expired channels are closed
         * here, but only after this cleaner exclusively claims them, so a channel that {@code poll()}
         * is leasing concurrently is never closed. Returns the number of channels closed by this tick.
         */
        private int reapPartition(ConcurrentLinkedDeque<Channel> partition, long now) {
            List<Channel> toRemove = null;
            int closed = 0;

            for (Channel channel : partition) {
                IdleState idleState = channel.attr(IDLE_STATE_ATTRIBUTE_KEY).get();
                if (idleState == null) {
                    continue;
                }

                if (idleState.isOwned()) {
                    // In-deque + owned ==> a removeAll() tombstone, or a node a concurrent poll() has
                    // already leased and unlinked. Either way: unlink, never close — the owner of the
                    // claim is responsible for closing it. removeAll() on an already-unlinked node is a
                    // harmless no-op.
                    toRemove = lazyAdd(toRemove, channel);
                    continue;
                }

                boolean isIdleTimeoutExpired = isIdleTimeoutExpired(idleState, now);
                boolean isRemotelyClosed = !Channels.isChannelActive(channel);
                boolean isTtlExpired = isTtlExpired(channel, now);
                if (!isIdleTimeoutExpired && !isRemotelyClosed && !isTtlExpired) {
                    continue; // healthy idle channel, leave it for poll()
                }

                long startSnapshot = idleState.start();
                // Claim before closing so we never close a channel poll() is leasing concurrently.
                if (!idleState.takeOwnership()) {
                    continue; // poll() (or removeAll) won the claim; that owner now handles the channel
                }
                if (idleState.start() != startSnapshot) {
                    // The channel was leased and re-offered (fresh start) between the expiry check and
                    // the claim, so it is leasable again — release it instead of closing it.
                    idleState.releaseOwnership();
                    continue;
                }

                LOGGER.debug("Closing Idle Channel {} isIdleTimeoutExpired={} isRemotelyClosed={} isTtlExpired={}",
                        channel, isIdleTimeoutExpired, isRemotelyClosed, isTtlExpired);
                close(channel);
                closed++;
                toRemove = lazyAdd(toRemove, channel);
            }

            if (toRemove != null) {
                partition.removeAll(toRemove);
            }
            return closed;
        }

        private List<Channel> lazyAdd(List<Channel> list, Channel channel) {
            if (list == null) {
                list = new ArrayList<>(1);
            }
            list.add(channel);
            return list;
        }
    }
}
