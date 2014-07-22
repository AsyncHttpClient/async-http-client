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
package com.ning.http.client.providers.netty.channel;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.group.ChannelGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.channel.pool.ChannelPool;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.util.CleanupChannelGroup;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class ChannelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);

    private final ChannelPool channelPool;
    private final boolean maxTotalConnectionsEnabled;
    private final Semaphore freeChannels;
    private final ChannelGroup openChannels;
    private final int maxConnectionsPerHost;
    private final boolean maxConnectionsPerHostEnabled;
    private final ConcurrentHashMap<String, Semaphore> freeChannelsPerHost;
    private final ConcurrentHashMap<Integer, String> channelId2KeyPool;

    public ChannelManager(AsyncHttpClientConfig config, ChannelPool channelPool) {
        this.channelPool = channelPool;

        maxTotalConnectionsEnabled = config.getMaxConnections() > 0;
        
        if (maxTotalConnectionsEnabled) {
            openChannels = new CleanupChannelGroup("asyncHttpClient") {
                @Override
                public boolean remove(Object o) {
                    boolean removed = super.remove(o);
                    if (removed) {
                        freeChannels.release();
                        if (maxConnectionsPerHostEnabled) {
                            String poolKey = channelId2KeyPool.remove(Channel.class.cast(o).getId());
                            if (poolKey != null) {
                                Semaphore freeChannelsForHost = freeChannelsPerHost.get(poolKey);
                                if (freeChannelsForHost != null)
                                    freeChannelsForHost.release();
                            }
                        }
                    }
                    return removed;
                }
            };
            freeChannels = new Semaphore(config.getMaxConnections());
        } else {
            openChannels = new CleanupChannelGroup("asyncHttpClient");
            freeChannels = null;
        }

        maxConnectionsPerHost = config.getMaxConnectionsPerHost();
        maxConnectionsPerHostEnabled = config.getMaxConnectionsPerHost() > 0;
        
        if (maxConnectionsPerHostEnabled) {
            freeChannelsPerHost = new ConcurrentHashMap<String, Semaphore>();
            channelId2KeyPool = new ConcurrentHashMap<Integer, String>();
        } else {
            freeChannelsPerHost = null;
            channelId2KeyPool = null;
        }
    }

    public final void tryToOfferChannelToPool(Channel channel, boolean keepAlive, String poolKey) {
        if (keepAlive && channel.isReadable()) {
            LOGGER.debug("Adding key: {} for channel {}", poolKey, channel);
            channelPool.offer(channel, poolKey);
            if (maxConnectionsPerHostEnabled)
                channelId2KeyPool.putIfAbsent(channel.getId(), poolKey);
            Channels.setDiscard(channel);
        } else {
            // not offered
            closeChannel(channel);
        }
    }

    public Channel poll(String uri) {
        return channelPool.poll(uri);
    }

    public boolean removeAll(Channel connection) {
        return channelPool.removeAll(connection);
    }

    private boolean tryAcquireGlobal() {
        return !maxTotalConnectionsEnabled || freeChannels.tryAcquire();
    }

    private Semaphore getFreeConnectionsForHost(String poolKey) {
        Semaphore freeConnections = freeChannelsPerHost.get(poolKey);
        if (freeConnections == null) {
            // lazy create the semaphore
            Semaphore newFreeConnections = new Semaphore(maxConnectionsPerHost);
            freeConnections = freeChannelsPerHost.putIfAbsent(poolKey, newFreeConnections);
            if (freeConnections == null)
                freeConnections = newFreeConnections;
        }
        return freeConnections;
    }
    
    private boolean tryAcquirePerHost(String poolKey) {
        return !maxConnectionsPerHostEnabled || getFreeConnectionsForHost(poolKey).tryAcquire();
    }
    
    public boolean preemptChannel(String poolKey) {
        return channelPool.isOpen() && tryAcquireGlobal() && tryAcquirePerHost(poolKey);
    }

    public void destroy() {
        channelPool.destroy();
        openChannels.close();
        
        for (Channel channel : openChannels) {
            Object attachment = Channels.getAttachment(channel);
            if (attachment instanceof NettyResponseFuture<?>) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;
                future.cancelTimeouts();
            }
        }
    }

    public void closeChannel(Channel channel) {
        removeAll(channel);
        Channels.setDiscard(channel);

        // The channel may have already been removed if a timeout occurred, and this method may be called just after.
        if (channel != null) {
            LOGGER.debug("Closing Channel {} ", channel);
            try {
                channel.close();
            } catch (Throwable t) {
                LOGGER.debug("Error closing a connection", t);
            }
            openChannels.remove(channel);
        }
    }

    public void abortChannelPreemption(String poolKey) {
        if (maxTotalConnectionsEnabled)
            freeChannels.release();
        if (maxConnectionsPerHostEnabled)
            getFreeConnectionsForHost(poolKey).release();
    }

    public void registerOpenChannel(Channel channel) {
        openChannels.add(channel);
    }
}
