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
package com.ning.http.client.providers.netty.pool;

import static com.ning.http.util.DateUtils.millisTime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.Channels;
import com.ning.http.client.providers.netty.NettyResponseFuture;

/**
 * A simple implementation of {@link com.ning.http.client.providers.netty.pool.ChannelPool} based on a {@link java.util.concurrent.ConcurrentHashMap}
 */
public final class DefaultChannelPool implements ChannelPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultChannelPool.class);

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<IdleChannel>> connectionsPool = new ConcurrentHashMap<String, ConcurrentLinkedQueue<IdleChannel>>();
    private final ConcurrentHashMap<Channel, ChannelCreation> channel2Creation = new ConcurrentHashMap<Channel, ChannelCreation>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final Timer nettyTimer;
    private final boolean sslConnectionPoolEnabled;
    private final int maxConnectionTTL;
    private final boolean maxConnectionTTLDisabled;
    private final long maxIdleTime;
    private final boolean maxIdleTimeDisabled;
    private final long cleanerPeriod;

    public DefaultChannelPool(AsyncHttpClientConfig config, Timer hashedWheelTimer) {
        this(config.getMaxConnectionPerHost(),//
                config.getIdleConnectionInPoolTimeoutInMs(),//
                config.getMaxConnectionLifeTimeInMs(),//
                config.isSslConnectionPoolEnabled(),//
                hashedWheelTimer);
    }

    public DefaultChannelPool(//
            int maxConnectionPerHost,//
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
        final String key;

        ChannelCreation(long creationTime, String key) {
            this.creationTime = creationTime;
            this.key = key;
        }
    }

    private static final class IdleChannel {
        final Channel channel;
        final long start;

        IdleChannel(Channel channel, long start) {
            if (channel == null)
                throw new NullPointerException("channel");
            this.channel = channel;
            this.start = start;
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

        ChannelCreation creation = channel2Creation.get(channel);
        return creation == null || now - creation.creationTime >= maxConnectionTTL;
    }

    private boolean isRemotelyClosed(Channel channel) {
        return !channel.isConnected() || !channel.isOpen();
    }

    private final class IdleChannelDetector implements TimerTask {

        private boolean isIdleTimeoutExpired(IdleChannel idleChannel, long now) {
            return !maxIdleTimeDisabled && now - idleChannel.start >= maxIdleTime;
        }

        private List<IdleChannel> expiredChannels(ConcurrentLinkedQueue<IdleChannel> pool, long now) {
            // lazy create
            List<IdleChannel> idleTimeoutChannels = null;
            for (IdleChannel idleChannel : pool) {
                if (isTTLExpired(idleChannel.channel, now) || isIdleTimeoutExpired(idleChannel, now)
                        || isRemotelyClosed(idleChannel.channel)) {
                    LOGGER.debug("Adding Candidate expired Channel {}", idleChannel.channel);
                    if (idleTimeoutChannels == null)
                        idleTimeoutChannels = new ArrayList<IdleChannel>();
                    idleTimeoutChannels.add(idleChannel);
                }
            }

            return idleTimeoutChannels != null ? idleTimeoutChannels : Collections.<IdleChannel> emptyList();
        }

        private boolean isChannelCloseable(Channel channel) {
            boolean closeable = true;
            Object attachment = Channels.getAttachment(channel);
            if (attachment instanceof NettyResponseFuture) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;
                closeable = !future.isDone() || !future.isCancelled();
                if (!closeable)
                    LOGGER.error("Future not in appropriate state %s, not closing", future);
            }
            return true;
        }

        private final List<IdleChannel> closeChannels(List<IdleChannel> candidates) {

            // lazy create, only if we have a non-closeable channel
            List<IdleChannel> closedChannels = null;
            for (int i = 0; i < candidates.size(); i++) {
                IdleChannel idleChannel = candidates.get(i);
                if (!isChannelCloseable(idleChannel.channel))
                    if (closedChannels == null) {
                        // first non closeable to be skipped, copy all previously skipped closeable channels
                        closedChannels = new ArrayList<IdleChannel>(candidates.size());
                        for (int j = 0; j < i; j++)
                            closedChannels.add(candidates.get(j));
                    } else {
                        LOGGER.debug("Closing Idle Channel {}", idleChannel.channel);
                        close(idleChannel.channel);
                        if (closedChannels != null) {
                            closedChannels.add(idleChannel);
                        }
                    }
            }

            return closedChannels != null ? closedChannels : candidates;
        }

        public void run(Timeout timeout) throws Exception {

            if (isClosed.get())
                return;

            try {
                if (LOGGER.isDebugEnabled()) {
                    for (String key : connectionsPool.keySet()) {
                        LOGGER.debug("Entry count for : {} : {}", key, connectionsPool.get(key).size());
                    }
                }

                long start = millisTime();
                int closedCount = 0;
                int totalCount = 0;

                for (ConcurrentLinkedQueue<IdleChannel> pool : connectionsPool.values()) {
                    // store in intermediate unsynchronized lists to minimize the impact on the ConcurrentLinkedQueue
                    if (LOGGER.isDebugEnabled()) {
                        totalCount += pool.size();
                    }
                    List<IdleChannel> candidateExpiredChannels = expiredChannels(pool, start);
                    List<IdleChannel> closedChannels = closeChannels(candidateExpiredChannels);
                    pool.removeAll(closedChannels);
                    int poolClosedCount = closedChannels.size();
                    closedCount += poolClosedCount;
                }

                long duration = millisTime() - start;

                LOGGER.debug("Closed {} connections out of {} in {}ms", closedCount, totalCount, duration);

            } catch (Throwable t) {
                LOGGER.error("uncaught exception!", t);
            }

            scheduleNewIdleChannelDetector(timeout.getTask());
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(String key, Channel channel) {
        if (isClosed.get() || (!sslConnectionPoolEnabled && key.startsWith("https")))
            return false;

        long now = millisTime();

        if (isTTLExpired(channel, now))
            return false;

        ConcurrentLinkedQueue<IdleChannel> idleConnectionForKey = connectionsPool.get(key);
        if (idleConnectionForKey == null) {
            ConcurrentLinkedQueue<IdleChannel> newPool = new ConcurrentLinkedQueue<IdleChannel>();
            idleConnectionForKey = connectionsPool.putIfAbsent(key, newPool);
            if (idleConnectionForKey == null)
                idleConnectionForKey = newPool;
        }

        boolean added = idleConnectionForKey.add(new IdleChannel(channel, now));
        if (added)
            channel2Creation.putIfAbsent(channel, new ChannelCreation(now, key));

        return added;
    }

    /**
     * {@inheritDoc}
     */
    public Channel poll(String key) {
        if (!sslConnectionPoolEnabled && key.startsWith("https"))
            return null;

        IdleChannel idleChannel = null;
        ConcurrentLinkedQueue<IdleChannel> pooledConnectionForKey = connectionsPool.get(key);
        if (pooledConnectionForKey != null) {
            while (idleChannel == null) {
                idleChannel = pooledConnectionForKey.poll();

                if (idleChannel == null)
                    // pool is empty
                    break;
                else if (isRemotelyClosed(idleChannel.channel)) {
                    idleChannel = null;
                    LOGGER.trace("Channel not connected or not opened, probably remotely closed!");
                }
            }
        }
        return idleChannel != null ? idleChannel.channel : null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAll(Channel channel) {
        ChannelCreation creation = channel2Creation.remove(channel);
        return !isClosed.get() && creation != null && connectionsPool.get(creation.key).remove(channel);
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

        for (ConcurrentLinkedQueue<IdleChannel> pool : connectionsPool.values()) {
            for (IdleChannel idleChannel : pool)
                close(idleChannel.channel);
        }

        connectionsPool.clear();
        channel2Creation.clear();
    }

    private void close(Channel channel) {
        try {
            // FIXME pity to have to do this here
            Channels.setDiscard(channel);
            channel2Creation.remove(channel);
            channel.close();
        } catch (Throwable t) {
            // noop
        }
    }
}
