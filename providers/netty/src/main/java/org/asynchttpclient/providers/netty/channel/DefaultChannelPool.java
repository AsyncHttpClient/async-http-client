/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.providers.netty.channel;

import static org.asynchttpclient.util.DateUtils.millisTime;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.DiscardEvent;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.netty.util.internal.chmv8.ConcurrentHashMapV8;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A simple implementation of {@link ChannelPool} based on a {@link java.util.concurrent.ConcurrentHashMap}
 */
public class DefaultChannelPool implements ChannelPool {

    private final static Logger LOGGER = LoggerFactory.getLogger(DefaultChannelPool.class);
    private final ConcurrentHashMapV8<String, ConcurrentLinkedQueue<IdleChannel>> connectionsPool = new ConcurrentHashMapV8<String, ConcurrentLinkedQueue<IdleChannel>>();
    private final ConcurrentHashMapV8<Channel, IdleChannel> channel2IdleChannel = new ConcurrentHashMapV8<Channel, IdleChannel>();
    private final ConcurrentHashMapV8<Channel, Long> channel2CreationDate = new ConcurrentHashMapV8<Channel, Long>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Timer nettyTimer;
    private final boolean sslConnectionPoolEnabled;
    private final int maxTotalConnections;
    private final int maxConnectionPerHost;
    private final int maxConnectionLifeTimeInMs;
    private final long maxIdleTime;

    public DefaultChannelPool(AsyncHttpClientConfig config, Timer nettyTimer) {
        this(config.getMaxTotalConnections(),//
                config.getMaxConnectionPerHost(),//
                config.getIdleConnectionInPoolTimeoutInMs(),//
                config.isSslConnectionPoolEnabled(),//
                config.getMaxConnectionLifeTimeInMs(),//
                nettyTimer);
    }

    public DefaultChannelPool(//
            int maxTotalConnections,//
            int maxConnectionPerHost,//
            long maxIdleTime,//
            boolean sslConnectionPoolEnabled,//
            int maxConnectionLifeTimeInMs,//
            Timer nettyTimer) {
        this.maxTotalConnections = maxTotalConnections;
        this.maxConnectionPerHost = maxConnectionPerHost;
        this.sslConnectionPoolEnabled = sslConnectionPoolEnabled;
        this.maxIdleTime = maxIdleTime;
        this.maxConnectionLifeTimeInMs = maxConnectionLifeTimeInMs;
        this.nettyTimer = nettyTimer;
        if (maxIdleTime > 0L) {
            scheduleNewIdleChannelDetector(new IdleChannelDetector());
        }
    }

    private void scheduleNewIdleChannelDetector(TimerTask task) {
        nettyTimer.newTimeout(task, maxIdleTime, TimeUnit.MILLISECONDS);
    }

    private static final class IdleChannel {
        final String key;
        final Channel channel;
        final long start;

        IdleChannel(String key, Channel channel) {
            if (key == null)
                throw new NullPointerException("key");
            if (channel == null)
                throw new NullPointerException("channel");
            this.key = key;
            this.channel = channel;
            this.start = millisTime();
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof IdleChannel && channel.equals(IdleChannel.class.cast(o).channel));
        }

        @Override
        public int hashCode() {
            return channel != null ? channel.hashCode() : 0;
        }
    }

    private class IdleChannelDetector implements TimerTask {

        @Override
        public void run(Timeout timeout) throws Exception {
            try {
                if (closed.get())
                    return;

                if (LOGGER.isDebugEnabled()) {
                    Set<String> keys = connectionsPool.keySet();

                    for (String s : keys) {
                        LOGGER.debug("Entry count for : {} : {}", s, connectionsPool.get(s).size());
                    }
                }

                List<IdleChannel> channelsInTimeout = new ArrayList<IdleChannel>();
                long currentTime = millisTime();

                for (IdleChannel idleChannel : channel2IdleChannel.values()) {
                    long age = currentTime - idleChannel.start;
                    if (age > maxIdleTime) {

                        LOGGER.debug("Adding Candidate Idle Channel {}", idleChannel.channel);

                        // store in an unsynchronized list to minimize the impact on the ConcurrentHashMap.
                        channelsInTimeout.add(idleChannel);
                    }
                }
                long endConcurrentLoop = millisTime();

                for (IdleChannel idleChannel : channelsInTimeout) {
                    Object attachment = Channels.getDefaultAttribute(idleChannel.channel);
                    if (attachment != null) {
                        if (attachment instanceof NettyResponseFuture) {
                            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;

                            if (!future.isDone() && !future.isCancelled()) {
                                LOGGER.debug("Future not in appropriate state %s\n", future);
                                continue;
                            }
                        }
                    }

                    if (remove(idleChannel)) {
                        LOGGER.debug("Closing Idle Channel {}", idleChannel.channel);
                        close(idleChannel.channel);
                    }
                }

                if (LOGGER.isTraceEnabled()) {
                    int openChannels = 0;
                    for (ConcurrentLinkedQueue<IdleChannel> hostChannels : connectionsPool.values()) {
                        openChannels += hostChannels.size();
                    }
                    LOGGER.trace(String.format("%d channel open, %d idle channels closed (times: 1st-loop=%d, 2nd-loop=%d).\n", openChannels,
                            channelsInTimeout.size(), endConcurrentLoop - currentTime, millisTime() - endConcurrentLoop));
                }
            } catch (Throwable t) {
                LOGGER.error("uncaught exception!", t);
            }

            scheduleNewIdleChannelDetector(timeout.task());
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(String uri, Channel channel) {
        if (closed.get() || (!sslConnectionPoolEnabled && uri.startsWith("https")))
            return false;

        Long createTime = channel2CreationDate.get(channel);
        if (createTime == null) {
            channel2CreationDate.putIfAbsent(channel, millisTime());

        } else if (maxConnectionLifeTimeInMs != -1 && (createTime + maxConnectionLifeTimeInMs) < millisTime()) {
            LOGGER.debug("Channel {} expired", channel);
            return false;
        }

        LOGGER.debug("Adding uri: {} for channel {}", uri, channel);
        Channels.setDefaultAttribute(channel, DiscardEvent.INSTANCE);

        ConcurrentLinkedQueue<IdleChannel> pooledConnectionForKey = connectionsPool.get(uri);
        if (pooledConnectionForKey == null) {
            ConcurrentLinkedQueue<IdleChannel> newPool = new ConcurrentLinkedQueue<IdleChannel>();
            pooledConnectionForKey = connectionsPool.putIfAbsent(uri, newPool);
            if (pooledConnectionForKey == null)
                pooledConnectionForKey = newPool;
        }

        boolean added;
        int size = pooledConnectionForKey.size();
        if (maxConnectionPerHost == -1 || size < maxConnectionPerHost) {
            IdleChannel idleChannel = new IdleChannel(uri, channel);
            synchronized (pooledConnectionForKey) {
                added = pooledConnectionForKey.add(idleChannel);

                if (channel2IdleChannel.put(channel, idleChannel) != null) {
                    LOGGER.error("Channel {} already exists in the connections pool!", channel);
                }
            }
        } else {
            LOGGER.debug("Maximum number of requests per host reached {} for {}", maxConnectionPerHost, uri);
            added = false;
        }
        return added;
    }

    /**
     * {@inheritDoc}
     */
    public Channel poll(String uri) {
        if (!sslConnectionPoolEnabled && uri.startsWith("https")) {
            return null;
        }

        IdleChannel idleChannel = null;
        ConcurrentLinkedQueue<IdleChannel> pooledConnectionForKey = connectionsPool.get(uri);
        if (pooledConnectionForKey != null) {
            boolean poolEmpty = false;
            while (!poolEmpty && idleChannel == null) {
                if (pooledConnectionForKey.size() > 0) {
                    synchronized (pooledConnectionForKey) {
                        idleChannel = pooledConnectionForKey.poll();
                        if (idleChannel != null) {
                            channel2IdleChannel.remove(idleChannel.channel);
                        }
                    }
                }

                if (idleChannel == null) {
                    poolEmpty = true;
                } else if (!idleChannel.channel.isActive() || !idleChannel.channel.isOpen()) {
                    idleChannel = null;
                    LOGGER.trace("Channel not connected or not opened!");
                }
            }
        }
        return idleChannel != null ? idleChannel.channel : null;
    }

    private boolean remove(IdleChannel pooledChannel) {
        if (pooledChannel == null || closed.get())
            return false;

        boolean isRemoved = false;
        ConcurrentLinkedQueue<IdleChannel> pooledConnectionForHost = connectionsPool.get(pooledChannel.key);
        if (pooledConnectionForHost != null) {
            isRemoved = pooledConnectionForHost.remove(pooledChannel);
        }
        return isRemoved |= channel2IdleChannel.remove(pooledChannel.channel) != null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAll(Channel channel) {
        channel2CreationDate.remove(channel);
        return !closed.get() && remove(channel2IdleChannel.get(channel));
    }

    /**
     * {@inheritDoc}
     */
    public boolean canCacheConnection() {
        return !closed.get() && (maxTotalConnections == -1 || channel2IdleChannel.size() < maxTotalConnections);
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        if (closed.getAndSet(true))
            return;

        for (Channel channel : channel2IdleChannel.keySet()) {
            close(channel);
        }
        connectionsPool.clear();
        channel2IdleChannel.clear();
        channel2CreationDate.clear();
    }

    private void close(Channel channel) {
        try {
            Channels.setDefaultAttribute(channel, DiscardEvent.INSTANCE);
            channel2CreationDate.remove(channel);
            channel.close();
        } catch (Throwable t) {
            // noop
        }
    }

    public final String toString() {
        return String.format("NettyConnectionPool: {pool-size: %d}", channel2IdleChannel.size());
    }
}
