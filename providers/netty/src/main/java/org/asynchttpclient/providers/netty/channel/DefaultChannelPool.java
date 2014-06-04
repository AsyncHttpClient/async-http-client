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

import static org.asynchttpclient.util.DateUtil.millisTime;

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

    private final static Logger log = LoggerFactory.getLogger(DefaultChannelPool.class);
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

    private static class IdleChannel {
        final String uri;
        final Channel channel;
        final long start;

        IdleChannel(String uri, Channel channel) {
            this.uri = uri;
            this.channel = channel;
            this.start = millisTime();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof IdleChannel))
                return false;

            IdleChannel that = (IdleChannel) o;

            if (channel != null ? !channel.equals(that.channel) : that.channel != null)
                return false;

            return true;
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

                if (log.isDebugEnabled()) {
                    Set<String> keys = connectionsPool.keySet();

                    for (String s : keys) {
                        log.debug("Entry count for : {} : {}", s, connectionsPool.get(s).size());
                    }
                }

                List<IdleChannel> channelsInTimeout = new ArrayList<IdleChannel>();
                long currentTime = millisTime();

                for (IdleChannel idleChannel : channel2IdleChannel.values()) {
                    long age = currentTime - idleChannel.start;
                    if (age > maxIdleTime) {

                        log.debug("Adding Candidate Idle Channel {}", idleChannel.channel);

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
                                log.debug("Future not in appropriate state %s\n", future);
                                continue;
                            }
                        }
                    }

                    if (remove(idleChannel)) {
                        log.debug("Closing Idle Channel {}", idleChannel.channel);
                        close(idleChannel.channel);
                    }
                }

                if (log.isTraceEnabled()) {
                    int openChannels = 0;
                    for (ConcurrentLinkedQueue<IdleChannel> hostChannels : connectionsPool.values()) {
                        openChannels += hostChannels.size();
                    }
                    log.trace(String.format("%d channel open, %d idle channels closed (times: 1st-loop=%d, 2nd-loop=%d).\n", openChannels,
                            channelsInTimeout.size(), endConcurrentLoop - currentTime, millisTime() - endConcurrentLoop));
                }
            } catch (Throwable t) {
                log.error("uncaught exception!", t);
            }

            scheduleNewIdleChannelDetector(timeout.task());
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(String uri, Channel channel) {
        if (closed.get())
            return false;

        if (!sslConnectionPoolEnabled && uri.startsWith("https")) {
            return false;
        }

        Long createTime = channel2CreationDate.get(channel);
        if (createTime == null) {
            channel2CreationDate.putIfAbsent(channel, millisTime());

        } else if (maxConnectionLifeTimeInMs != -1 && (createTime + maxConnectionLifeTimeInMs) < millisTime()) {
            log.debug("Channel {} expired", channel);
            return false;
        }

        log.debug("Adding uri: {} for channel {}", uri, channel);
        Channels.setDefaultAttribute(channel, DiscardEvent.INSTANCE);

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
                    log.error("Channel {} already exists in the connections pool!", channel);
                }
            }
        } else {
            log.debug("Maximum number of requests per host reached {} for {}", maxConnectionPerHost, uri);
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
        ConcurrentLinkedQueue<IdleChannel> idleConnectionForHost = connectionsPool.get(uri);
        if (idleConnectionForHost != null) {
            boolean poolEmpty = false;
            while (!poolEmpty && idleChannel == null) {
                if (idleConnectionForHost.size() > 0) {
                    synchronized (idleConnectionForHost) {
                        idleChannel = idleConnectionForHost.poll();
                        if (idleChannel != null) {
                            channel2IdleChannel.remove(idleChannel.channel);
                        }
                    }
                }

                if (idleChannel == null) {
                    poolEmpty = true;
                } else if (!idleChannel.channel.isActive() || !idleChannel.channel.isOpen()) {
                    idleChannel = null;
                    log.trace("Channel not connected or not opened!");
                }
            }
        }
        return idleChannel != null ? idleChannel.channel : null;
    }

    private boolean remove(IdleChannel pooledChannel) {
        if (pooledChannel == null || closed.get())
            return false;

        boolean isRemoved = false;
        ConcurrentLinkedQueue<IdleChannel> pooledConnectionForHost = connectionsPool.get(pooledChannel.uri);
        if (pooledConnectionForHost != null) {
            isRemoved = pooledConnectionForHost.remove(pooledChannel);
        }
        isRemoved |= channel2IdleChannel.remove(pooledChannel.channel) != null;
        return isRemoved;
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
