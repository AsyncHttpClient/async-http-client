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
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import org.jboss.netty.channel.Channel;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple implementation of {@link com.ning.http.client.ConnectionsPool} based on a {@link ConcurrentHashMap}
 */
public class NettyConnectionsPool implements ConnectionsPool<String, Channel> {

    private final static Logger log = LogManager.getLogger(NettyAsyncHttpProvider.class);
    private final ConcurrentHashMap<String, Channel> connectionsPool =
            new ConcurrentHashMap<String, Channel>();
    private final ConcurrentHashMap<String, AtomicInteger> connectionsPerHost =
            new ConcurrentHashMap<String, AtomicInteger>();
    private final AsyncHttpClientConfig config;


    public NettyConnectionsPool(AsyncHttpClientConfig config) {
        this.config = config;
    }

    /**
     * {@inheritDoc}
     */
    public boolean addConnection(String uri, Channel connection) {
        if (log.isDebugEnabled()) {
            log.debug(String.format(NettyAsyncHttpProvider.currentThread() + "Adding uri: %s for channel %s", uri, connection));
        }

        AtomicInteger connectionPerHost = connectionsPerHost.get(uri);
        if (connectionPerHost == null) {
            connectionPerHost = new AtomicInteger(1);
            connectionsPerHost.put(uri, connectionPerHost);
        }

        if (config.getMaxConnectionPerHost() == -1 || connectionPerHost.get() < config.getMaxConnectionPerHost()) {
            connection.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());
            boolean added = connectionsPool.put(uri, connection) == null ? true : false;
            if (added) {
                connectionPerHost.incrementAndGet();
            }
            return added;
        } else {
            if (log.isDebugEnabled()) {
                log.warn("Maximum connections per hosts reached " + config.getMaxConnectionPerHost());
            }
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    public Channel getConnection(String uri) {
        Channel channel = connectionsPool.get(uri);
        if (channel != null && !channel.isOpen()) {
            removeConnection(uri);
            return null;
        }
        return channel;
    }

    /**
     * {@inheritDoc}
     */
    public Channel removeConnection(String uri) {
        Channel channel = connectionsPool.remove(uri);
        if (channel != null && (!channel.isConnected() || !channel.isOpen())) {
            removeAllConnections(channel);
            return null;
        }
        return channel;
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAllConnections(Channel connection) {
        boolean isRemoved = false;
        Iterator<Map.Entry<String,Channel>> i = connectionsPool.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<String,Channel> e = i.next();
            if (e != null && e.getValue().equals(connection)) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format(NettyAsyncHttpProvider.currentThread()
                            + "Removing uri: %s for channel %s", e.getKey(), e.getValue()));
                }
                i.remove();
                isRemoved = true;
            }
        }
        return isRemoved;
    }

    /**
     * {@inheritDoc}
     */
    public boolean canCacheConnection() {
        if (config.getMaxTotalConnections() != -1 && connectionsPool.size() >= config.getMaxTotalConnections()) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        try {
            Iterator<Map.Entry<String,Channel>> i = connectionsPool.entrySet().iterator();
            while (i.hasNext()) {
                Channel channel = i.next().getValue();
                removeAllConnections(channel);
                channel.close();
            }
        } finally {
            connectionsPool.clear();
        }
    }
}
