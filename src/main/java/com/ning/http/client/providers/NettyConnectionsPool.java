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
package com.ning.http.client.providers;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import org.jboss.netty.channel.Channel;

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
    private final AtomicInteger activeConnectionsCount = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> connectionsPerHost =
            new ConcurrentHashMap<String, AtomicInteger>();
    private final AsyncHttpClientConfig config;


    public NettyConnectionsPool(AsyncHttpClientConfig config) {
        this.config = config;
    }

    /**
     * {@inheritDoc}
     */
    public boolean addConnection(String key, Channel connection) {
        AtomicInteger connectionPerHost = connectionsPerHost.get(key);
        if (connectionPerHost == null) {
            connectionPerHost = new AtomicInteger(1);
            connectionsPerHost.put(key, connectionPerHost);
        }

        if (config.getMaxConnectionPerHost() == -1 || connectionPerHost.get() < config.getMaxConnectionPerHost()) {
            connectionsPool.put(key, connection);
            connectionPerHost.incrementAndGet();
            activeConnectionsCount.incrementAndGet();
        } else {
            log.warn("Maximum connections per hosts reached " + config.getMaxConnectionPerHost());
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    public Channel getConnection(String key) {
        return connectionsPool.get(key);
    }

    /**
     * {@inheritDoc}
     */
    public Channel removeConnection(String key) {
        return connectionsPool.remove(key);
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAllConnections(Channel connection) {
        boolean isRemoved = false;
        for (Map.Entry<String, Channel> e : connectionsPool.entrySet()) {
            if (e.getValue().equals(connection)) {
                connectionsPool.remove(e.getKey());
                if (config.getMaxTotalConnections() != -1) {
                    activeConnectionsCount.decrementAndGet();
                }
                isRemoved = true;
            }
        }
        return isRemoved;
    }

    /**
     * {@inheritDoc}
     */
    public boolean canCacheConnection() {
        if (config.getMaxTotalConnections() != -1 && activeConnectionsCount.get() >= config.getMaxTotalConnections()) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        connectionsPool.clear();
    }
}
