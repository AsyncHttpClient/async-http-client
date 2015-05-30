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
package org.asynchttpclient.netty;

import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.config.AsyncHttpClientConfig;
import org.asynchttpclient.future.ListenableFuture;
import org.asynchttpclient.handler.AsyncHandler;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.pool.ChannelPoolPartitionSelector;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.NettyAsyncHttpProvider;
import org.asynchttpclient.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.request.Request;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyAsyncHttpProvider extends SimpleChannelUpstreamHandler implements AsyncHttpProvider {

    static final Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);

    private final AsyncHttpClientConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ChannelManager channelManager;
    private final boolean allowStopNettyTimer;
    private final Timer nettyTimer;

    private final NettyRequestSender requestSender;

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        this.config = config;
        NettyAsyncHttpProviderConfig nettyConfig = config.getAsyncHttpProviderConfig() instanceof NettyAsyncHttpProviderConfig ? //
        (NettyAsyncHttpProviderConfig) config.getAsyncHttpProviderConfig()
                : new NettyAsyncHttpProviderConfig();

        allowStopNettyTimer = nettyConfig.getNettyTimer() == null;
        nettyTimer = allowStopNettyTimer ? newNettyTimer() : nettyConfig.getNettyTimer();

        channelManager = new ChannelManager(config, nettyConfig, nettyTimer);
        requestSender = new NettyRequestSender(config, channelManager, nettyTimer, closed);
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
    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) {
        try {
            return requestSender.sendRequest(request, asyncHandler, null, false);
        } catch (Exception e) {
            asyncHandler.onThrowable(e);
            return new ListenableFuture.CompletedFailure<>(e);
        }
    }

    public void flushChannelPoolPartition(String partitionId) {
        channelManager.flushPartition(partitionId);
    }

    public void flushChannelPoolPartitions(ChannelPoolPartitionSelector selector) {
        channelManager.flushPartitions(selector);
    }
}
