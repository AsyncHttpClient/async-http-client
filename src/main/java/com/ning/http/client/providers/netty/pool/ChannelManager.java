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
package com.ning.http.client.providers.netty.pool;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.group.ChannelGroup;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.Channels;
import com.ning.http.client.providers.netty.CleanupChannelGroup;
import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import com.ning.http.client.providers.netty.NettyResponseFuture;

import java.util.concurrent.Semaphore;

public class ChannelManager {

    private final ChannelPool channelPool;
    private final Semaphore freeConnections;
    private final boolean trackConnections;

    private final ChannelGroup openChannels;

    public ChannelManager(AsyncHttpClientConfig config, ChannelPool channelPool) {
        if (config.getMaxTotalConnections() != -1) {
            trackConnections = true;
            openChannels = new CleanupChannelGroup("asyncHttpClient") {
                @Override
                public boolean remove(Object o) {
                    boolean removed = super.remove(o);
                    if (removed)
                        freeConnections.release();
                    return removed;
                }
            };
            freeConnections = new Semaphore(config.getMaxTotalConnections());
        } else {
            trackConnections = false;
            openChannels = new CleanupChannelGroup("asyncHttpClient");
            freeConnections = null;
        }
        this.channelPool = channelPool;
    }

    public boolean offer(String uri, Channel connection) {
        return channelPool.offer(uri, connection);
    }

    public Channel poll(String uri) {
        return channelPool.poll(uri);
    }

    public boolean removeAll(Channel connection) {
        return channelPool.removeAll(connection);
    }

    public boolean canCacheConnection() {
        return channelPool.canCacheConnection() && (!trackConnections || freeConnections.tryAcquire());
    }

    public void destroy() {
        channelPool.destroy();
        openChannels.close();
        for (Channel channel : openChannels) {
            ChannelHandlerContext ctx = channel.getPipeline().getContext(NettyAsyncHttpProvider.class);
            Object attachment = Channels.getAttachment(ctx);
            if (attachment instanceof NettyResponseFuture<?>) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;
                future.cancelTimeouts();
            }
        }
    }
    
    // temp
    public void releaseFreeConnection() {
        freeConnections.release();
    }
    
    public void registerOpenChannel(Channel channel) {
        openChannels.add(channel);
    }
    
    public boolean unregisterOpenChannel(Channel channel) {
        return openChannels.remove(channel);
    }
}
