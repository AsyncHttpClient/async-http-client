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
package com.ning.http.client.providers.netty;

import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Request;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.pool.ChannelPoolPartitionSelector;
import com.ning.http.client.providers.netty.request.NettyRequestSender;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyAsyncHttpProvider extends SimpleChannelUpstreamHandler implements AsyncHttpProvider {

    static final Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);

    private final AsyncHttpClientConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ChannelManager channelManager;
    private final NettyAsyncHttpProviderConfig nettyConfig;
    private final boolean allowStopNettyTimer;
    private final Timer nettyTimer;

    private final NettyRequestSender requestSender;

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        this.config = config;
        nettyConfig = config.getAsyncHttpProviderConfig() instanceof NettyAsyncHttpProviderConfig ? //
        (NettyAsyncHttpProviderConfig) config.getAsyncHttpProviderConfig()
                : new NettyAsyncHttpProviderConfig();

        allowStopNettyTimer = nettyConfig.getNettyTimer() == null;
        nettyTimer = allowStopNettyTimer ? newNettyTimer() : nettyConfig.getNettyTimer();

        channelManager = new ChannelManager(config, nettyConfig, nettyTimer);
        requestSender = new NettyRequestSender(config, nettyConfig, channelManager, nettyTimer, closed);
        channelManager.configureBootstraps(requestSender, closed);
    }

    private Timer newNettyTimer() {
        HashedWheelTimer timer = new HashedWheelTimer();
        timer.start();
        return timer;
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                channelManager.close();

                // FIXME shouldn't close if not allowed
                config.executorService().shutdown();

                if (allowStopNettyTimer)
                    nettyTimer.stop();

            } catch (Throwable t) {
                LOGGER.warn("Unexpected error on close", t);
            }
        }
    }

    @Override
    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        return requestSender.sendRequest(request, asyncHandler, null, false);
    }

    public void flushChannelPoolPartition(String partitionId) {
        channelManager.flushPartition(partitionId);
    }

    public void flushChannelPoolPartitions(ChannelPoolPartitionSelector selector) {
        channelManager.flushPartitions(selector);
    }
}
