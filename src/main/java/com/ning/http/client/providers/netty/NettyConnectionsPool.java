/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.providers.netty;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionsPool;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A simple implementation of {@link com.ning.http.client.ConnectionsPool} based on a {@link ConcurrentHashMap}
 */
public class NettyConnectionsPool implements ConnectionsPool<String, Channel> {

    private final static Logger log = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Channel>> connectionsPool =
            new ConcurrentHashMap<String, ConcurrentLinkedQueue<Channel>>();
    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProvider provider;
    private final ConcurrentHashMap<Channel, Timeout> trackedIdleConnections = new ConcurrentHashMap<Channel, Timeout>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final HashedWheelTimer timer = new HashedWheelTimer();

    public NettyConnectionsPool(NettyAsyncHttpProvider provider) {
        this.provider = provider;
        this.config = provider.getConfig();
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(String uri, Channel channel) {
        if (!provider.getConfig().isSslConnectionPoolEnabled() && uri.startsWith("https") ) {
            return false;
        }

        log.debug("Adding uri: {} for channel {}", uri, channel);
        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());

        ConcurrentLinkedQueue<Channel> pooledConnectionForHost = connectionsPool.get(uri);
        if (pooledConnectionForHost == null) {
            ConcurrentLinkedQueue<Channel> newPool = new ConcurrentLinkedQueue<Channel>();
            connectionsPool.putIfAbsent(uri, newPool);
            pooledConnectionForHost = connectionsPool.get(uri);
        }

        boolean added;
        int size = pooledConnectionForHost.size();
        if (config.getMaxConnectionPerHost() == -1 || size < config.getMaxConnectionPerHost()) {
            added = pooledConnectionForHost.add(channel);
            if (added) {
                Timeout t = timer.newTimeout(new IdleRunner(channel, pooledConnectionForHost),
                        config.getIdleConnectionInPoolTimeoutInMs(), TimeUnit.MILLISECONDS);
                trackedIdleConnections.put(channel, t);
                log.debug("ConnectionsPool increment totalConnections {}", trackedIdleConnections.size());
            }
        } else {
            log.debug("Maximum connections per hosts reached {}", config.getMaxConnectionPerHost());
            added = false;
        }
        return added;
    }

    private final class IdleRunner implements TimerTask {

        private final ConcurrentLinkedQueue<Channel> activeChannels;
        private final Channel channel;

        public IdleRunner(Channel channel, ConcurrentLinkedQueue<Channel> activeChannels) {
            this.channel = channel;
            this.activeChannels = activeChannels;
        }

        public void run(Timeout timeout) {
            if (isClosed.get()) return;
            Object attachment = channel.getPipeline().getContext(NettyAsyncHttpProvider.class).getAttachment();
            if (attachment != null) {
                if (NettyResponseFuture.class.isAssignableFrom(attachment.getClass())) {
                    NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;

                    if (!future.isDone() && !future.isCancelled()) {
                        log.warn("Future not in appropriate state {}", future);
                        return;
                    }
                }
            }

            if (activeChannels.remove(channel)) {
                log.debug("Channel idle. Expiring {}", channel);
                close(channel);
            }
            timeout.cancel();
        }
    }

    /**
     * {@inheritDoc}
     */
    public Channel poll(String uri) {
        if (!provider.getConfig().isSslConnectionPoolEnabled() && uri.startsWith("https") ) {
            return null;
        }

        Channel channel = null;
        ConcurrentLinkedQueue<Channel> pooledConnectionForHost = connectionsPool.get(uri);
        if (pooledConnectionForHost != null) {
            boolean poolEmpty = false;
            while (!poolEmpty && channel == null) {
                if (pooledConnectionForHost.size() > 0) {
                    channel = pooledConnectionForHost.poll();
                }

                if (channel == null) {
                    poolEmpty = true;
                } else if (!channel.isConnected() || !channel.isOpen()) {
                    removeAll(channel);
                    channel = null;
                } else {
                    Timeout idleFuture = trackedIdleConnections.remove(channel);
                    if (idleFuture != null) {
                        idleFuture.cancel();
                    }

                    // Double checking the channel hasn't been closed in between.
                    if (!channel.isConnected() || !channel.isOpen()) {
                        channel = null;
                    }

                    log.debug("ConnectionsPool decrementAndGet totalConnections {}", trackedIdleConnections.size());
                }
            }
        }
        return channel;
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAll(Channel channel) {
        if (isClosed.get()) return false;
        
        boolean isRemoved = false;
        Iterator<Map.Entry<String, ConcurrentLinkedQueue<Channel>>> i = connectionsPool.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<String, ConcurrentLinkedQueue<Channel>> e = i.next();
            boolean removed = e.getValue().remove(channel);
            if (removed) {
                log.debug("Removing uri: {} for channel {}", e.getKey(), e.getValue());
                Timeout idleFuture = trackedIdleConnections.remove(channel);
                if (idleFuture != null) {
                    idleFuture.cancel();
                } else {
                    log.debug("ConnectionsPool decrementAndGet totalConnections {}", trackedIdleConnections.size());
                }
            }
            isRemoved |= removed;
        }
        return isRemoved;
    }

    /**
     * {@inheritDoc}
     */
    public boolean canCacheConnection() {
        if (config.getMaxTotalConnections() != -1 && trackedIdleConnections.size() >= config.getMaxTotalConnections()) {
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
        timer.stop();
         
        for(Map.Entry<Channel,Timeout> e: trackedIdleConnections.entrySet()) {
            close(e.getKey());
            e.getValue().cancel();
        }
        trackedIdleConnections.clear();

        try {
            Iterator<Map.Entry<String, ConcurrentLinkedQueue<Channel>>> i = connectionsPool.entrySet().iterator();
            while (i.hasNext()) {
                for (Channel channel: i.next().getValue()) {
                    close(channel);
                }
            }
        } finally {
            connectionsPool.clear();
        }
    }

    private void close(Channel channel) {
        try {
            channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());
            channel.close();
        } catch (Throwable t) {
            // noop
        }
    }
}
