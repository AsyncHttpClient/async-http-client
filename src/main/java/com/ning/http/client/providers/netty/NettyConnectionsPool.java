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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A simple implementation of {@link com.ning.http.client.ConnectionsPool} based on a {@link ConcurrentHashMap}
 */
public class NettyConnectionsPool implements ConnectionsPool<String, Channel> {

    private final static Logger log = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);
    private final ConcurrentHashMap<String, List<Channel>> connectionsPool =
            new ConcurrentHashMap<String, List<Channel>>();
    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProvider provider;
    private final ConcurrentHashMap<Channel, Future<?>> trackedIdleConnections = new ConcurrentHashMap<Channel, Future<?>>();

    public NettyConnectionsPool(NettyAsyncHttpProvider provider) {
        this.provider = provider;
        this.config = provider.getConfig();
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(String uri, Channel channel) {
        log.debug("Adding uri: {} for channel {}", uri, channel);
        channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());

        List<Channel> pooledConnectionForHost = connectionsPool.get(uri);
        if (pooledConnectionForHost == null) {
            List<Channel> newPool = new LinkedList<Channel>();
            connectionsPool.putIfAbsent(uri, newPool);
            pooledConnectionForHost = connectionsPool.get(uri);
        }

        boolean added;
        synchronized (pooledConnectionForHost) {
            int size = pooledConnectionForHost.size();
            if (config.getMaxConnectionPerHost() == -1 || size < config.getMaxConnectionPerHost()) {
                added = pooledConnectionForHost.add(channel);
                if (added) {
                    Future<?> idleFuture = config.reaper().schedule(new IdleRunner(channel, pooledConnectionForHost),
                            config.getIdleConnectionInPoolTimeoutInMs(), TimeUnit.MILLISECONDS);
                    trackedIdleConnections.put(channel, idleFuture);
                    log.debug("ConnectionsPool increment totalConnections {}", trackedIdleConnections.size());
                }
            } else {
                log.debug("Maximum connections per hosts reached {}", config.getMaxConnectionPerHost());
                added = false;
            }
        }
        return added;
    }

    private final class IdleRunner implements Runnable {

        private final List<Channel> activeChannels;
        private final Channel channel;

        public IdleRunner(Channel channel, List<Channel> activeChannels) {
            this.channel = channel;
            this.activeChannels = activeChannels;
        }

        public void run() {
            synchronized (activeChannels) {
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
                    try {
                        channel.close();
                    } catch (Throwable t) {
                        // Ignore
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public Channel poll(String uri) {
        Channel channel = null;
        List<Channel> pooledConnectionForHost = connectionsPool.get(uri);
        if (pooledConnectionForHost != null) {
            boolean poolEmpty = false;
            while (!poolEmpty && channel == null) {
                synchronized (pooledConnectionForHost) {
                    if (pooledConnectionForHost.size() > 0) {
                        channel = pooledConnectionForHost.remove(0);
                    }
                }
                if (channel == null) {
                    poolEmpty = true;
                } else if (!channel.isConnected() || !channel.isOpen()) {
                    removeAll(channel);
                    channel = null;
                } else {
                    Future<?> idleFuture = trackedIdleConnections.remove(channel);
                    if (idleFuture != null) {
                        idleFuture.cancel(true);
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
        boolean isRemoved = false;
        Iterator<Map.Entry<String, List<Channel>>> i = connectionsPool.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<String, List<Channel>> e = i.next();
            synchronized (e.getValue()) {
                boolean removed = e.getValue().remove(channel);
                if (removed) {
                    log.debug("Removing uri: {} for channel {}", e.getKey(), e.getValue());
                    Future<?> idleFuture = trackedIdleConnections.remove(channel);
                    if (idleFuture != null) {
                        idleFuture.cancel(true);
                    } else {
                        log.debug("ConnectionsPool decrementAndGet totalConnections {}", trackedIdleConnections.size());
                    }
                }
                isRemoved |= removed;
            }
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
        trackedIdleConnections.clear();       
        try {
            Iterator<Map.Entry<String, List<Channel>>> i = connectionsPool.entrySet().iterator();
            while (i.hasNext()) {
                List<Channel> list = i.next().getValue();
                synchronized (list) {
                    for (int j = 0; j < list.size(); j++) {
                        Channel channel = list.remove(0);
                        removeAll(channel);
                        channel.close();
                    }
                }
            }
        } finally {
            connectionsPool.clear();
        }
    }
}
