/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
import java.util.Collections;
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

import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

/**
 * A simple implementation of {@link ChannelPool} based on a {@link ConcurrentHashMap}
 */
public final class DefaultChannelPool implements ChannelPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultChannelPool.class);
    private static final AttributeKey<ChannelCreation> CHANNEL_CREATION_ATTRIBUTE_KEY = AttributeKey.valueOf("channelCreation");

    private final ConcurrentHashMap<Object, ConcurrentLinkedDeque<IdleChannel>> partitions = new ConcurrentHashMap<>();
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
        ConcurrentLinkedDeque<IdleChannel> partition = partitions.get(partitionKey);
        if (partition == null) {
            partition = partitions.computeIfAbsent(partitionKey, pk -> new ConcurrentLinkedDeque<>());
        }
        return partition.offerFirst(new IdleChannel(channel, now));
    }

    private static void registerChannelCreation(Channel channel, Object partitionKey, long now) {
        Attribute<ChannelCreation> channelCreationAttribute = channel.attr(CHANNEL_CREATION_ATTRIBUTE_KEY);
        if (channelCreationAttribute.get() == null) {
            channelCreationAttribute.set(new ChannelCreation(now, partitionKey));
        }
    }

    @Override
    public Channel poll(Object partitionKey) {
        IdleChannel idleChannel = null;
        ConcurrentLinkedDeque<IdleChannel> partition = partitions.get(partitionKey);
        if (partition != null) {
            while (idleChannel == null) {
                idleChannel = poolLeaseStrategy.lease(partition);

                if (idleChannel == null)
                // pool is empty
                {
                    break;
                } else if (!Channels.isChannelActive(idleChannel.channel)) {
                    idleChannel = null;
                    LOGGER.trace("Channel is inactive, probably remotely closed!");
                } else if (!idleChannel.takeOwnership()) {
                    idleChannel = null;
                    LOGGER.trace("Couldn't take ownership of channel, probably in the process of being expired!");
                }
            }
        }
        return idleChannel != null ? idleChannel.channel : null;
    }

    @Override
    public boolean removeAll(Channel channel) {
        ChannelCreation creation = connectionTtlEnabled ? channel.attr(CHANNEL_CREATION_ATTRIBUTE_KEY).get() : null;
        return !isClosed.get() && creation != null && partitions.get(creation.partitionKey).remove(new IdleChannel(channel, Long.MIN_VALUE));
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

    private void flushPartition(Object partitionKey, ConcurrentLinkedDeque<IdleChannel> partition) {
        if (partition != null) {
            partitions.remove(partitionKey);
            for (IdleChannel idleChannel : partition) {
                close(idleChannel.channel);
            }
        }
    }

    @Override
    public void flushPartitions(Predicate<Object> predicate) {
        for (Map.Entry<Object, ConcurrentLinkedDeque<IdleChannel>> partitionsEntry : partitions.entrySet()) {
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
                .map(idle -> idle.getChannel().remoteAddress())
                .filter(a -> a.getClass() == InetSocketAddress.class)
                .map(a -> (InetSocketAddress) a)
                .map(InetSocketAddress::getHostString)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
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

    private static final class IdleChannel {

        private static final AtomicIntegerFieldUpdater<IdleChannel> ownedField = AtomicIntegerFieldUpdater.newUpdater(IdleChannel.class, "owned");

        final Channel channel;
        final long start;
        @SuppressWarnings("unused")
        private volatile int owned;

        IdleChannel(Channel channel, long start) {
            this.channel = assertNotNull(channel, "channel");
            this.start = start;
        }

        public boolean takeOwnership() {
            return ownedField.getAndSet(this, 1) == 0;
        }

        public Channel getChannel() {
            return channel;
        }

        @Override
        // only depends on channel
        public boolean equals(Object o) {
            return this == o || o instanceof IdleChannel && channel.equals(((IdleChannel) o).channel);
        }

        @Override
        public int hashCode() {
            return channel.hashCode();
        }
    }

    private final class IdleChannelDetector implements TimerTask {

        private boolean isIdleTimeoutExpired(IdleChannel idleChannel, long now) {
            return maxIdleTimeEnabled && now - idleChannel.start >= maxIdleTime;
        }

        private List<IdleChannel> expiredChannels(ConcurrentLinkedDeque<IdleChannel> partition, long now) {
            // lazy create
            List<IdleChannel> idleTimeoutChannels = null;
            for (IdleChannel idleChannel : partition) {
                boolean isIdleTimeoutExpired = isIdleTimeoutExpired(idleChannel, now);
                boolean isRemotelyClosed = !Channels.isChannelActive(idleChannel.channel);
                boolean isTtlExpired = isTtlExpired(idleChannel.channel, now);
                if (isIdleTimeoutExpired || isRemotelyClosed || isTtlExpired) {

                    LOGGER.debug("Adding Candidate expired Channel {} isIdleTimeoutExpired={} isRemotelyClosed={} isTtlExpired={}",
                            idleChannel.channel, isIdleTimeoutExpired, isRemotelyClosed, isTtlExpired);

                    if (idleTimeoutChannels == null) {
                        idleTimeoutChannels = new ArrayList<>(1);
                    }
                    idleTimeoutChannels.add(idleChannel);
                }
            }

            return idleTimeoutChannels != null ? idleTimeoutChannels : Collections.emptyList();
        }

        private List<IdleChannel> closeChannels(List<IdleChannel> candidates) {
            // lazy create, only if we hit a non-closeable channel
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
                    // first non-closeable to be skipped, copy all
                    // previously skipped closeable channels
                    closedChannels = new ArrayList<>(candidates.size());
                    for (int j = 0; j < i; j++) {
                        closedChannels.add(candidates.get(j));
                    }
                }
            }

            return closedChannels != null ? closedChannels : candidates;
        }

        @Override
        public void run(Timeout timeout) {

            if (isClosed.get()) {
                return;
            }

            if (LOGGER.isDebugEnabled()) {
                for (Map.Entry<Object, ConcurrentLinkedDeque<IdleChannel>> entry : partitions.entrySet()) {
                    int size = entry.getValue().size();
                    if (size > 0) {
                        LOGGER.debug("Entry count for : {} : {}", entry.getKey(), size);
                    }
                }
            }

            long start = unpreciseMillisTime();
            int closedCount = 0;
            int totalCount = 0;

            for (ConcurrentLinkedDeque<IdleChannel> partition : partitions.values()) {

                // store in intermediate unsynchronized lists to minimize
                // the impact on the ConcurrentLinkedDeque
                if (LOGGER.isDebugEnabled()) {
                    totalCount += partition.size();
                }

                List<IdleChannel> closedChannels = closeChannels(expiredChannels(partition, start));

                if (!closedChannels.isEmpty()) {
                    partition.removeAll(closedChannels);
                    closedCount += closedChannels.size();
                }
            }

            if (LOGGER.isDebugEnabled()) {
                long duration = unpreciseMillisTime() - start;
                if (closedCount > 0) {
                    LOGGER.debug("Closed {} connections out of {} in {} ms", closedCount, totalCount, duration);
                }
            }

            scheduleNewIdleChannelDetector(timeout.task());
        }
    }
}
