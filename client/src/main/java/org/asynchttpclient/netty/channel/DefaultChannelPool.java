/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.channel;

import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.channel.ChannelPool;
import org.asynchttpclient.channel.ChannelPoolPartitionSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple implementation of {@link ChannelPool} based on a {@link java.util.concurrent.ConcurrentHashMap}
 */
public final class DefaultChannelPool implements ChannelPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultChannelPool.class);

    private final ConcurrentHashMap<Object, ConcurrentLinkedDeque<IdleChannel>> partitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChannelId, ChannelCreation> channelId2Creation;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final Timer nettyTimer;
    private final int connectionTtl;
    private final boolean connectionTtlEnabled;
    private final int maxIdleTime;
    private final boolean maxIdleTimeEnabled;
    private final long cleanerPeriod;
    private final PoolLeaseStrategy poolLeaseStrategy;

    public DefaultChannelPool(AsyncHttpClientConfig config, Timer hashedWheelTimer) {
        this(config.getPooledConnectionIdleTimeout(),//
                config.getConnectionTtl(),//
                hashedWheelTimer);
    }

    private ChannelId channelId(Channel channel) {
        return Channels.getChannelId(channel);
    }

    public DefaultChannelPool(int maxIdleTime,//
            int connectionTtl,//
            Timer nettyTimer) {
        this(maxIdleTime,//
                connectionTtl,//
                PoolLeaseStrategy.LIFO,//
                nettyTimer);
    }

    public DefaultChannelPool(int maxIdleTime,//
            int connectionTtl,//
            PoolLeaseStrategy poolLeaseStrategy,//
            Timer nettyTimer) {
        this.maxIdleTime = (int) maxIdleTime;
        this.connectionTtl = connectionTtl;
        connectionTtlEnabled = connectionTtl > 0;
        channelId2Creation = connectionTtlEnabled ? new ConcurrentHashMap<>() : null;
        this.nettyTimer = nettyTimer;
        maxIdleTimeEnabled = maxIdleTime > 0;
        this.poolLeaseStrategy = poolLeaseStrategy;

        cleanerPeriod = Math.min(connectionTtlEnabled ? connectionTtl : Integer.MAX_VALUE, maxIdleTimeEnabled ? maxIdleTime : Long.MAX_VALUE);

        if (connectionTtlEnabled || maxIdleTimeEnabled)
            scheduleNewIdleChannelDetector(new IdleChannelDetector());
    }

    private void scheduleNewIdleChannelDetector(TimerTask task) {
        nettyTimer.newTimeout(task, cleanerPeriod, TimeUnit.MILLISECONDS);
    }

    private static final class ChannelCreation {
        final long creationTime;
        final Object partitionKey;

        ChannelCreation(long creationTime, Object partitionKey) {
            this.creationTime = creationTime;
            this.partitionKey = partitionKey;
        }
    }

    private static final class IdleChannel {
        final Channel channel;
        final long start;
        final AtomicBoolean owned = new AtomicBoolean(false);

        IdleChannel(Channel channel, long start) {
            this.channel = assertNotNull(channel, "channel");
            this.start = start;
        }

        public boolean takeOwnership() {
            return owned.compareAndSet(false, true);
        }

        @Override
        // only depends on channel
        public boolean equals(Object o) {
            return this == o || (o instanceof IdleChannel && channel.equals(IdleChannel.class.cast(o).channel));
        }

        @Override
        public int hashCode() {
            return channel.hashCode();
        }
    }

    private boolean isTtlExpired(Channel channel, long now) {
        if (!connectionTtlEnabled)
            return false;

        ChannelCreation creation = channelId2Creation.get(channelId(channel));
        return creation != null && now - creation.creationTime >= connectionTtl;
    }

    private boolean isRemotelyClosed(Channel channel) {
        return !channel.isActive();
    }

    private final class IdleChannelDetector implements TimerTask {

        private boolean isIdleTimeoutExpired(IdleChannel idleChannel, long now) {
            return maxIdleTimeEnabled && now - idleChannel.start >= maxIdleTime;
        }

        private List<IdleChannel> expiredChannels(ConcurrentLinkedDeque<IdleChannel> partition, long now) {
            // lazy create
            List<IdleChannel> idleTimeoutChannels = null;
            for (IdleChannel idleChannel : partition) {
                if (isIdleTimeoutExpired(idleChannel, now) || isRemotelyClosed(idleChannel.channel) || isTtlExpired(idleChannel.channel, now)) {
                    LOGGER.debug("Adding Candidate expired Channel {}", idleChannel.channel);
                    if (idleTimeoutChannels == null)
                        idleTimeoutChannels = new ArrayList<>();
                    idleTimeoutChannels.add(idleChannel);
                }
            }

            return idleTimeoutChannels != null ? idleTimeoutChannels : Collections.<IdleChannel> emptyList();
        }

        private final List<IdleChannel> closeChannels(List<IdleChannel> candidates) {

            // lazy create, only if we have a non-closeable channel
            List<IdleChannel> closedChannels = null;
            for (int i = 0; i < candidates.size(); i++) {
                // We call takeOwnership here to avoid closing a channel that has just been taken out
                // of the pool, otherwise we risk closing an active connection.
                IdleChannel idleChannel = candidates.get(i);
                if (idleChannel.takeOwnership()) {
                    LOGGER.debug("Closing Idle Channel {}", idleChannel.channel);
                    close(idleChannel.channel);
                    if (closedChannels != null) {
                        closedChannels.add(idleChannel);
                    }

                } else if (closedChannels == null) {
                    // first non closeable to be skipped, copy all
                    // previously skipped closeable channels
                    closedChannels = new ArrayList<>(candidates.size());
                    for (int j = 0; j < i; j++)
                        closedChannels.add(candidates.get(j));
                }
            }

            return closedChannels != null ? closedChannels : candidates;
        }

        public void run(Timeout timeout) throws Exception {

            if (isClosed.get())
                return;

            if (LOGGER.isDebugEnabled())
                for (Object key : partitions.keySet()) {
                    LOGGER.debug("Entry count for : {} : {}", key, partitions.get(key).size());
                }

            long start = unpreciseMillisTime();
            int closedCount = 0;
            int totalCount = 0;

            for (ConcurrentLinkedDeque<IdleChannel> partition : partitions.values()) {

                // store in intermediate unsynchronized lists to minimize
                // the impact on the ConcurrentLinkedDeque
                if (LOGGER.isDebugEnabled())
                    totalCount += partition.size();

                List<IdleChannel> closedChannels = closeChannels(expiredChannels(partition, start));

                if (!closedChannels.isEmpty()) {
                    if (connectionTtlEnabled) {
                        for (IdleChannel closedChannel : closedChannels)
                            channelId2Creation.remove(channelId(closedChannel.channel));
                    }

                    partition.removeAll(closedChannels);
                    closedCount += closedChannels.size();
                }
            }

            if (LOGGER.isDebugEnabled()) {
                long duration = unpreciseMillisTime() - start;
                LOGGER.debug("Closed {} connections out of {} in {}ms", closedCount, totalCount, duration);
            }

            scheduleNewIdleChannelDetector(timeout.task());
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(Channel channel, Object partitionKey) {
        if (isClosed.get())
            return false;

        long now = unpreciseMillisTime();

        if (isTtlExpired(channel, now))
            return false;

        boolean offered = offer0(channel, partitionKey, now);
        if (connectionTtlEnabled && offered) {
            registerChannelCreation(channel, partitionKey, now);
        }

        return offered;
    }

    private boolean offer0(Channel channel, Object partitionKey, long now) {
        ConcurrentLinkedDeque<IdleChannel> partition = partitions.get(partitionKey);
        if (partition == null) {
            partition = partitions.computeIfAbsent(partitionKey, pk -> new ConcurrentLinkedDeque<>());
        }
        return partition.offerFirst(new IdleChannel(channel, now));
    }

    private void registerChannelCreation(Channel channel, Object partitionKey, long now) {
        ChannelId id = channelId(channel);
        if (!channelId2Creation.containsKey(id)) {
            channelId2Creation.putIfAbsent(id, new ChannelCreation(now, partitionKey));
        }
    }

    /**
     * {@inheritDoc}
     */
    public Channel poll(Object partitionKey) {

        IdleChannel idleChannel = null;
        ConcurrentLinkedDeque<IdleChannel> partition = partitions.get(partitionKey);
        if (partition != null) {
            while (idleChannel == null) {
                idleChannel = poolLeaseStrategy.lease(partition);

                if (idleChannel == null)
                    // pool is empty
                    break;
                else if (isRemotelyClosed(idleChannel.channel)) {
                    idleChannel = null;
                    LOGGER.trace("Channel not connected or not opened, probably remotely closed!");
                } else if (!idleChannel.takeOwnership()) {
                    idleChannel = null;
                    LOGGER.trace("Couldn't take ownership of channel, probably in the process of being expired!");
                }
            }
        }
        return idleChannel != null ? idleChannel.channel : null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAll(Channel channel) {
        ChannelCreation creation = connectionTtlEnabled ? channelId2Creation.remove(channelId(channel)) : null;
        return !isClosed.get() && creation != null && partitions.get(creation.partitionKey).remove(channel);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isOpen() {
        return !isClosed.get();
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        if (isClosed.getAndSet(true))
            return;

        partitions.clear();
        if (connectionTtlEnabled) {
            channelId2Creation.clear();
        }
    }

    private void close(Channel channel) {
        // FIXME pity to have to do this here
        Channels.setDiscard(channel);
        if (connectionTtlEnabled) {
            channelId2Creation.remove(channelId(channel));
        }
        Channels.silentlyCloseChannel(channel);
    }

    private void flushPartition(Object partitionKey, ConcurrentLinkedDeque<IdleChannel> partition) {
        if (partition != null) {
            partitions.remove(partitionKey);
            for (IdleChannel idleChannel : partition)
                close(idleChannel.channel);
        }
    }

    @Override
    public void flushPartition(Object partitionKey) {
        flushPartition(partitionKey, partitions.get(partitionKey));
    }

    @Override
    public void flushPartitions(ChannelPoolPartitionSelector selector) {

        for (Map.Entry<Object, ConcurrentLinkedDeque<IdleChannel>> partitionsEntry : partitions.entrySet()) {
            Object partitionKey = partitionsEntry.getKey();
            if (selector.select(partitionKey))
                flushPartition(partitionKey, partitionsEntry.getValue());
        }
    }

    public enum PoolLeaseStrategy {
        LIFO {
            public <E> E lease(Deque<E> d) {
                return d.pollFirst();
            }
        },
        FIFO {
            public <E> E lease(Deque<E> d) {
                return d.pollLast();
            }
        };

        abstract <E> E lease(Deque<E> d);
    }
}
