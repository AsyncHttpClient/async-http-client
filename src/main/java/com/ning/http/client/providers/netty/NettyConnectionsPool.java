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

import com.ning.http.client.ConnectionsPool;
import org.jboss.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A simple implementation of {@link com.ning.http.client.ConnectionsPool} based on a {@link java.util.concurrent.ConcurrentHashMap}
 */
public class NettyConnectionsPool implements ConnectionsPool<String, Channel> {

    private final static Logger log = LoggerFactory.getLogger(NettyConnectionsPool.class);
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<IdleChannel>> connectionsPool = new ConcurrentHashMap<String, ConcurrentLinkedQueue<IdleChannel>>();
    private final ConcurrentHashMap<Channel, IdleChannel> channel2IdleChannel = new ConcurrentHashMap<Channel, IdleChannel>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final Timer idleConnectionDetector = new Timer(true);
    private final boolean sslConnectionPoolEnabled;
    private final int maxTotalConnections;
    private final int maxConnectionPerHost;
    private final long maxIdleTime;

    public NettyConnectionsPool(NettyAsyncHttpProvider provider) {
        this.maxTotalConnections = provider.getConfig().getMaxTotalConnections();
        this.maxConnectionPerHost = provider.getConfig().getMaxConnectionPerHost();
        this.sslConnectionPoolEnabled = provider.getConfig().isSslConnectionPoolEnabled();
        this.maxIdleTime = provider.getConfig().getIdleConnectionInPoolTimeoutInMs();
        this.idleConnectionDetector.schedule(new IdleChannelDetector(), maxIdleTime, maxIdleTime);
    }

    private static class IdleChannel {
        final String uri;
        final Channel channel;
        final long start;

        IdleChannel(String uri, Channel channel) {
            this.uri = uri;
            this.channel = channel;
            this.start = System.currentTimeMillis();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdleChannel)) return false;

            IdleChannel that = (IdleChannel) o;

            if (channel != null ? !channel.equals(that.channel) : that.channel != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return channel != null ? channel.hashCode() : 0;
        }
    }

    private class IdleChannelDetector extends TimerTask {
        @Override
        public void run() {
            try {
                if (isClosed.get()) return;

                if (log.isDebugEnabled()) {
                    Set<String> keys = connectionsPool.keySet();

                    for (String s : keys) {
                        log.debug("Entry count for : {} : {}", s, connectionsPool.get(s).size());
                    }
                }

                List<IdleChannel> channelsInTimeout = new ArrayList<IdleChannel>();
                long currentTime = System.currentTimeMillis();

                for (IdleChannel idleChannel : channel2IdleChannel.values()) {
                    long age = currentTime - idleChannel.start;
                    if (age > maxIdleTime) {

                        log.debug("Adding Candidate Idle Channel {}", idleChannel.channel);

                        // store in an unsynchronized list to minimize the impact on the ConcurrentHashMap.
                        channelsInTimeout.add(idleChannel);
                    }
                }
                long endConcurrentLoop = System.currentTimeMillis();

                for (IdleChannel idleChannel : channelsInTimeout) {
                    Object attachment = idleChannel.channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment();
                    if (attachment != null) {
                        if (NettyResponseFuture.class.isAssignableFrom(attachment.getClass())) {
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

                log.trace(String.format("%d channel open, %d idle channels closed (times: 1st-loop=%d, 2nd-loop=%d).\n",
                        connectionsPool.size(), channelsInTimeout.size(), endConcurrentLoop - currentTime, System.currentTimeMillis() - endConcurrentLoop));
            } catch (Throwable t) {
                log.error("uncaught exception!", t);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(String uri, Channel channel) {
        if (isClosed.get()) return false;

        if (!sslConnectionPoolEnabled && uri.startsWith("https")) {
            return false;
        }

        log.debug("Adding uri: {} for channel {}", uri, channel);
        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());

        ConcurrentLinkedQueue<IdleChannel> idleConnectionForHost = connectionsPool.get(uri);
        if (idleConnectionForHost == null) {
            ConcurrentLinkedQueue<IdleChannel> newPool = new ConcurrentLinkedQueue<IdleChannel>();
            idleConnectionForHost = connectionsPool.putIfAbsent(uri, newPool);
            if (idleConnectionForHost == null) idleConnectionForHost = newPool;
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
                } else if (!idleChannel.channel.isConnected() || !idleChannel.channel.isOpen()) {
                    idleChannel = null;
                    log.trace("Channel not connected or not opened!");
                }
            }
        }
        return idleChannel != null ? idleChannel.channel : null;
    }

    private boolean remove(IdleChannel pooledChannel) {
        if (pooledChannel == null || isClosed.get()) return false;

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
        return !isClosed.get() && remove(channel2IdleChannel.get(channel));
    }

    /**
     * {@inheritDoc}
     */
    public boolean canCacheConnection() {
        if (!isClosed.get() && maxTotalConnections != -1 && channel2IdleChannel.size() >= maxTotalConnections) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        if (isClosed.getAndSet(true)) return;

        // stop timer
        idleConnectionDetector.cancel();
        idleConnectionDetector.purge();

        for (Channel channel : channel2IdleChannel.keySet()) {
            close(channel);
        }
        connectionsPool.clear();
        channel2IdleChannel.clear();
    }

    private void close(Channel channel) {
        try {
            channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());
            channel.close();
        } catch (Throwable t) {
            // noop
        }
    }

    public final String toString() {
        return String.format("NettyConnectionPool: {pool-size: %d}", channel2IdleChannel.size());
    }
}
