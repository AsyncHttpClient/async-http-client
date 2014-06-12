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
package com.ning.http.client.providers.netty;

import static com.ning.http.util.DateUtil.millisTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.ConnectionsPool;

/**
 * A simple implementation of {@link com.ning.http.client.ConnectionsPool} based on a {@link java.util.concurrent.ConcurrentHashMap}
 */
public class NettyConnectionsPool implements ConnectionsPool<String, Channel> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyConnectionsPool.class);

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<IdleChannel>> connectionsPool = new ConcurrentHashMap<String, ConcurrentLinkedQueue<IdleChannel>>();
    private final ConcurrentHashMap<Channel, IdleChannel> channel2IdleChannel = new ConcurrentHashMap<Channel, IdleChannel>();
    private final ConcurrentHashMap<Channel, Long> channel2CreationDate = new ConcurrentHashMap<Channel, Long>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final Timer nettyTimer;
    private final boolean sslConnectionPoolEnabled;
    private final int maxTotalConnections;
    private final int maxConnectionPerHost;
    private final int maxConnectionLifeTimeInMs;
    private final long maxIdleTime;

    public NettyConnectionsPool(NettyAsyncHttpProvider provider, Timer hashedWheelTimer) {
        this(provider.getConfig().getMaxTotalConnections(),//
                provider.getConfig().getMaxConnectionPerHost(),//
                provider.getConfig().getIdleConnectionInPoolTimeoutInMs(),//
                provider.getConfig().getMaxConnectionLifeTimeInMs(),//
                provider.getConfig().isSslConnectionPoolEnabled(),//
                hashedWheelTimer);
    }

    public NettyConnectionsPool(int maxTotalConnections, int maxConnectionPerHost, long maxIdleTime, int maxConnectionLifeTimeInMs,
            boolean sslConnectionPoolEnabled, Timer nettyTimer) {
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
        this.nettyTimer.newTimeout(task, maxIdleTime, TimeUnit.MILLISECONDS);
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

        public void run(Timeout timeout) throws Exception {
            try {
                if (isClosed.get())
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
                    Object attachment = idleChannel.channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment();
                    if (attachment instanceof NettyResponseFuture) {
                        NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;
                        if (!future.isDone() && !future.isCancelled()) {
                            LOGGER.debug("Future not in appropriate state %s\n", future);
                            continue;
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

            scheduleNewIdleChannelDetector(timeout.getTask());
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(String uri, Channel channel) {
        if (isClosed.get() || (!sslConnectionPoolEnabled && uri.startsWith("https")))
            return false;

        Long createTime = channel2CreationDate.get(channel);
        if (createTime == null) {
            channel2CreationDate.putIfAbsent(channel, millisTime());

        } else if (maxConnectionLifeTimeInMs != -1 && (createTime + maxConnectionLifeTimeInMs) < millisTime()) {
            LOGGER.debug("Channel {} expired", channel);
            return false;
        }

        LOGGER.debug("Adding uri: {} for channel {}", uri, channel);
        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());

        ConcurrentLinkedQueue<IdleChannel> idleConnectionForHost = connectionsPool.get(uri);
        if (idleConnectionForHost == null) {
            ConcurrentLinkedQueue<IdleChannel> newPool = new ConcurrentLinkedQueue<IdleChannel>();
            idleConnectionForHost = connectionsPool.putIfAbsent(uri, newPool);
            if (idleConnectionForHost == null)
                idleConnectionForHost = newPool;
        }

        boolean added;
        int size = idleConnectionForHost.size();
        if (maxConnectionPerHost == -1 || size < maxConnectionPerHost) {
            IdleChannel idleChannel = new IdleChannel(uri, channel);
            synchronized (idleConnectionForHost) {
                added = idleConnectionForHost.add(idleChannel);

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
                if (!pooledConnectionForKey.isEmpty()) {
                    synchronized (pooledConnectionForKey) {
                        idleChannel = pooledConnectionForKey.poll();
                        if (idleChannel != null) {
                            channel2IdleChannel.remove(idleChannel.channel);
                        }
                    }
                }

                if (idleChannel == null) {
                    poolEmpty = true;
                } else if (!idleChannel.channel.isConnected() || !idleChannel.channel.isOpen()) {
                    idleChannel = null;
                    LOGGER.trace("Channel not connected or not opened!");
                }
            }
        }
        return idleChannel != null ? idleChannel.channel : null;
    }

    private boolean remove(IdleChannel pooledChannel) {
        if (pooledChannel == null || isClosed.get())
            return false;

        boolean isRemoved = false;
        ConcurrentLinkedQueue<IdleChannel> pooledConnectionForKey = connectionsPool.get(pooledChannel.key);
        if (pooledConnectionForKey != null) {
            isRemoved = pooledConnectionForKey.remove(pooledChannel);
        }
        return isRemoved |= channel2IdleChannel.remove(pooledChannel.channel) != null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAll(Channel channel) {
        channel2CreationDate.remove(channel);
        return !isClosed.get() && remove(channel2IdleChannel.get(channel));
    }

    /**
     * {@inheritDoc}
     */
    public boolean canCacheConnection() {
        return !isClosed.get() && (maxTotalConnections == -1 || channel2IdleChannel.size() < maxTotalConnections);
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        if (isClosed.getAndSet(true))
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
            channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());
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
