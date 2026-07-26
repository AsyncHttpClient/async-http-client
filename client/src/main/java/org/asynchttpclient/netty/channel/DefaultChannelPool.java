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
    // generation/ownership/reset CAS state) lives on the channel's IDLE_STATE_ATTRIBUTE_KEY attribute,
    // which is allocated once per physical connection and reused across every pool cycle.
    private final ConcurrentHashMap<Object, ConcurrentLinkedDeque<Channel>> partitions = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicBoolean duplicateOfferLogged = new AtomicBoolean(false);
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

        return offer0(channel, partitionKey, now);
    }

    private boolean offer0(Channel channel, Object partitionKey, long now) {
        // Reuse the channel's IdleState instead of allocating a holder per offer; reset() transfers an
        // owned generation to a new leasable generation before offerFirst publishes the channel.
        Attribute<IdleState> idleStateAttribute = channel.attr(IDLE_STATE_ATTRIBUTE_KEY);
        IdleState idleState = idleStateAttribute.get();
        if (idleState == null) {
            IdleState newIdleState = new IdleState();
            IdleState existingIdleState = idleStateAttribute.setIfAbsent(newIdleState);
            idleState = existingIdleState == null ? newIdleState : existingIdleState;
        }
        if (!idleState.reset(now)) {
            if (LOGGER.isDebugEnabled() && duplicateOfferLogged.compareAndSet(false, true)) {
                LOGGER.debug("Ignoring duplicate pool offer for channel {}", channel);
            }
            return true;
        }
        ConcurrentLinkedDeque<Channel> partition =
                partitions.computeIfAbsent(partitionKey, pk -> new ConcurrentLinkedDeque<>());
        boolean offered = partition.offerFirst(channel);
        if (connectionTtlEnabled && offered) {
            registerChannelCreation(channel, partitionKey, now);
        }
        return offered;
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
        Map<String, Long> idleChannelsPerHost = new HashMap<>();
        for (ConcurrentLinkedDeque<Channel> partition : partitions.values()) {
            for (Channel channel : partition) {
                // Skip channels that have been claimed (removeAll tombstone, or a node a concurrent
                // poll already leased) but not yet unlinked, so the count reflects leasable channels.
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
     * <p>The low bit of {@code state} is the ownership flag and the next bit marks a reset in progress;
     * the remaining bits advance by four on every offer. A successful {@code poll()} lease, a
     * {@code removeAll()} tombstone, and the cleaner's pre-close claim all own the current generation.
     * The cleaner can therefore claim only the exact generation whose expiry it evaluated, without
     * temporarily claiming a newer entry while checking whether {@code start} changed.
     *
     * <p>A generation can be reset only while owned and not already being reset. New state starts owned,
     * and reset first claims the generation's reset bit before publishing the timestamp and next leasable
     * generation. Concurrent or delayed resets therefore cannot transfer a generation they did not claim.
     */
    static final class IdleState {

        private static final long OWNED_MASK = 1L;
        private static final long RESETTING_MASK = 2L;
        private static final long GENERATION_INCREMENT = 4L;
        private static final AtomicLongFieldUpdater<IdleState> STATE_UPDATER =
                AtomicLongFieldUpdater.newUpdater(IdleState.class, "state");

        private volatile long start;
        private volatile long state = OWNED_MASK;

        long start() {
            return start;
        }

        long snapshot() {
            return state;
        }

        boolean isOwned() {
            return isOwned(state);
        }

        static boolean isOwned(long stateSnapshot) {
            return (stateSnapshot & OWNED_MASK) != 0;
        }

        private static boolean isResetting(long stateSnapshot) {
            return (stateSnapshot & RESETTING_MASK) != 0;
        }

        /** Atomically claim the current generation. */
        boolean takeOwnership() {
            return tryTakeOwnership(state);
        }

        /** Atomically claim {@code stateSnapshot}, provided it is still the current generation. */
        boolean tryTakeOwnership(long stateSnapshot) {
            return !isOwned(stateSnapshot)
                    && STATE_UPDATER.compareAndSet(this, stateSnapshot, stateSnapshot | OWNED_MASK);
        }

        /** Attempt to transfer the current owned generation to a new leasable generation. */
        boolean reset(long now) {
            return tryReset(state, now);
        }

        /** Transfer {@code stateSnapshot} if it is still owned, current, and not already being reset. */
        boolean tryReset(long stateSnapshot, long now) {
            if (!isOwned(stateSnapshot) || isResetting(stateSnapshot)
                    || !STATE_UPDATER.compareAndSet(this, stateSnapshot, stateSnapshot | RESETTING_MASK)) {
                return false;
            }
            start = now;
            state = (stateSnapshot & ~(OWNED_MASK | RESETTING_MASK)) + GENERATION_INCREMENT;
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
                    // In-deque + owned ==> a removeAll(Channel) tombstone, a node a concurrent poll()
                    // already leased and unlinked, or an old node whose generation is being reset.
                    // Either way: unlink, never close — the owner of the claim handles the channel.
                    // Unlinking an already-unlinked node through the iterator is a harmless no-op.
                    it.remove();
                    continue;
                }

                long idleStart = idleState.start();
                boolean isIdleTimeoutExpired = isIdleTimeoutExpired(idleStart, now);
                boolean isRemotelyClosed = !Channels.isChannelActive(channel);
                boolean isTtlExpired = isTtlExpired(channel, now);
                if (!isIdleTimeoutExpired && !isRemotelyClosed && !isTtlExpired) {
                    continue; // healthy idle channel, leave it for poll()
                }

                // Claim the generation evaluated above. A lease and re-offer changes the generation,
                // so this CAS cannot claim the fresh entry or interfere with a concurrent poll of it.
                if (!idleState.tryTakeOwnership(stateSnapshot)) {
                    continue;
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
