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
package com.ning.http.client.providers.netty.channel.pool;

import static com.ning.http.util.DateUtils.millisTime;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.chmv8.ConcurrentHashMapV8;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A simple implementation of {@link com.ning.http.client.providers.netty.channel.pool.ChannelPool} based on a {@link com.ning.http.client.providers.netty.chmv8.ConcurrentHashMapV8}
 */
public final class DefaultChannelPool implements ChannelPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultChannelPool.class);

    private static final ConcurrentHashMapV8.Fun<Object, ConcurrentLinkedQueue<IdleChannel>> PARTITION_COMPUTER = new ConcurrentHashMapV8.Fun<Object, ConcurrentLinkedQueue<IdleChannel>>() {
        @Override
        public ConcurrentLinkedQueue<IdleChannel> apply(Object partitionKey) {
            return new ConcurrentLinkedQueue<>();
        }
    };
    
    private final ConcurrentHashMapV8<Object, ConcurrentLinkedQueue<IdleChannel>> partitions = new ConcurrentHashMapV8<>();
    private final ConcurrentHashMapV8<Integer, ChannelCreation> channelId2Creation = new ConcurrentHashMapV8<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final Timer nettyTimer;
    private final boolean sslConnectionPoolEnabled;
    private final int maxConnectionTTL;
    private final boolean maxConnectionTTLDisabled;
    private final long maxIdleTime;
    private final boolean maxIdleTimeDisabled;
    private final long cleanerPeriod;

    public DefaultChannelPool(AsyncHttpClientConfig config, Timer hashedWheelTimer) {
        this(config.getPooledConnectionIdleTimeout(),//
                config.getConnectionTTL(),//
                config.isAllowPoolingSslConnections(),//
                hashedWheelTimer);
    }

    public DefaultChannelPool(//
            long maxIdleTime,//
            int maxConnectionTTL,//
            boolean sslConnectionPoolEnabled,//
            Timer nettyTimer) {
        this.sslConnectionPoolEnabled = sslConnectionPoolEnabled;
        this.maxIdleTime = maxIdleTime;
        this.maxConnectionTTL = maxConnectionTTL;
        maxConnectionTTLDisabled = maxConnectionTTL <= 0;
        this.nettyTimer = nettyTimer;
        maxIdleTimeDisabled = maxIdleTime <= 0;

        cleanerPeriod = Math.min(maxConnectionTTLDisabled ? Long.MAX_VALUE : maxConnectionTTL, maxIdleTimeDisabled ? Long.MAX_VALUE
                : maxIdleTime);

        if (!maxConnectionTTLDisabled || !maxIdleTimeDisabled)
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
            if (channel == null)
                throw new NullPointerException("channel");
            this.channel = channel;
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

    private boolean isTTLExpired(Channel channel, long now) {
        if (maxConnectionTTLDisabled)
            return false;

        ChannelCreation creation = channelId2Creation.get(channel.getId());
        return creation != null && now - creation.creationTime >= maxConnectionTTL;
    }

    private final class IdleChannelDetector implements TimerTask {

        private boolean isIdleTimeoutExpired(IdleChannel idleChannel, long now) {
            return !maxIdleTimeDisabled && now - idleChannel.start >= maxIdleTime;
        }

        private List<IdleChannel> expiredChannels(ConcurrentLinkedQueue<IdleChannel> partition, long now) {
            // lazy create
            List<IdleChannel> idleTimeoutChannels = null;
            for (IdleChannel idleChannel : partition) {
                if (isTTLExpired(idleChannel.channel, now) || isIdleTimeoutExpired(idleChannel, now)
                        || !Channels.isChannelValid(idleChannel.channel)) {
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
                    // first non closeable to be skipped, copy all previously skipped closeable channels
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

            try {
                if (LOGGER.isDebugEnabled()) {
                    for (Object key : partitions.keySet()) {
                        LOGGER.debug("Entry count for : {} : {}", key, partitions.get(key).size());
                    }
                }

                long start = millisTime();
                int closedCount = 0;
                int totalCount = 0;

                for (ConcurrentLinkedQueue<IdleChannel> partition : partitions.values()) {

                    // store in intermediate unsynchronized lists to minimize the impact on the ConcurrentLinkedQueue
                    if (LOGGER.isDebugEnabled())
                        totalCount += partition.size();

                    List<IdleChannel> closedChannels = closeChannels(expiredChannels(partition, start));

                    if (!closedChannels.isEmpty()) {
                        for (IdleChannel closedChannel : closedChannels)
                            channelId2Creation.remove(closedChannel.channel.getId());

                        partition.removeAll(closedChannels);
                        closedCount += closedChannels.size();
                    }
                }

                long duration = millisTime() - start;

                LOGGER.debug("Closed {} connections out of {} in {}ms", closedCount, totalCount, duration);

            } catch (Throwable t) {
                LOGGER.error("uncaught exception!", t);
            }

            scheduleNewIdleChannelDetector(timeout.getTask());
        }
    }

    public boolean offer(Channel channel, Object partitionKey) {
        if (isClosed.get() || (!sslConnectionPoolEnabled && channel.getPipeline().get(SslHandler.class) != null))
            return false;

        long now = millisTime();

        if (isTTLExpired(channel, now))
            return false;

        boolean added = partitions.computeIfAbsent(partitionKey, PARTITION_COMPUTER).add(new IdleChannel(channel, now));
        if (added)
            channelId2Creation.putIfAbsent(channel.getId(), new ChannelCreation(now, partitionKey));

        return added;
    }

    public Channel poll(Object partitionKey) {

        IdleChannel idleChannel = null;
        ConcurrentLinkedQueue<IdleChannel> partition = partitions.get(partitionKey);
        if (partition != null) {
            while (idleChannel == null) {
                idleChannel = partition.poll();

                if (idleChannel == null) {
                    // pool is empty
                    break;
                } else if (!Channels.isChannelValid(idleChannel.channel)) {
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

    @Override
    public boolean removeAll(Channel channel) {
        ChannelCreation creation = channelId2Creation.remove(channel.getId());
        return !isClosed.get() && creation != null && partitions.get(creation.partitionKey).remove(channel);
    }

    @Override
    public boolean isOpen() {
        return !isClosed.get();
    }

    @Override
    public void destroy() {
        if (isClosed.getAndSet(true))
            return;

        for (ConcurrentLinkedQueue<IdleChannel> partition : partitions.values()) {
            for (IdleChannel idleChannel : partition)
                close(idleChannel.channel);
        }

        partitions.clear();
        channelId2Creation.clear();
    }

    private void close(Channel channel) {
        // FIXME pity to have to do this here
        Channels.setDiscard(channel);
        channelId2Creation.remove(channel.getId());
        Channels.silentlyCloseChannel(channel);
    }

    private void flushPartition(Object partitionKey, ConcurrentLinkedQueue<IdleChannel> partition) {
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

        for (Map.Entry<Object, ConcurrentLinkedQueue<IdleChannel>> partitionsEntry : partitions.entrySet()) {
            Object partitionKey = partitionsEntry.getKey();
            if (selector.select(partitionKey))
                flushPartition(partitionKey, partitionsEntry.getValue());
        }
    }
}
