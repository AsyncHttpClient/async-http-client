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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.Channels;
import com.ning.http.client.providers.netty.CleanupChannelGroup;
import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import com.ning.http.client.providers.netty.NettyResponseFuture;

import java.util.concurrent.Semaphore;

public class ChannelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);

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

    public final boolean tryToOfferChannelToPool(ChannelHandlerContext ctx, boolean keepAlive, String poolKey) {
        Channel channel = ctx.getChannel();
        if (keepAlive && channel.isReadable() && channelPool.offer(poolKey, channel)) {
            LOGGER.debug("Adding key: {} for channel {}", poolKey, channel);
            Channels.setDiscard(ctx);
            return true;
        } else {
            // not offered
            closeChannel(ctx);
            return false;
        }
    }

    public Channel poll(String uri) {
        return channelPool.poll(uri);
    }

    public boolean removeAll(Channel connection) {
        return channelPool.removeAll(connection);
    }

    public boolean preemptChannel() {
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

    public void closeChannel(final ChannelHandlerContext ctx) {
        removeAll(ctx.getChannel());
        Channels.setDiscard(ctx);

        Channel channel = ctx.getChannel();

        // The channel may have already been removed if a timeout occurred, and this method may be called just after.
        if (channel != null) {
            // FIXME can the context channel really be null?
            LOGGER.debug("Closing Channel {} ", channel);
            try {
                channel.close();
            } catch (Throwable t) {
                LOGGER.debug("Error closing a connection", t);
            }
            openChannels.remove(channel);
        }
    }

    // temp
    public void abortChannelPreemption() {
        if (trackConnections)
            freeConnections.release();
    }

    public void registerOpenChannel(Channel channel) {
        openChannels.add(channel);
    }
}
