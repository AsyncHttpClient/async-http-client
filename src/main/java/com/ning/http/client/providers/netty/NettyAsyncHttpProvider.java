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
import com.ning.http.client.providers.netty.channel.pool.ChannelPool;
import com.ning.http.client.providers.netty.channel.pool.DefaultChannelPool;
import com.ning.http.client.providers.netty.channel.pool.NoopChannelPool;
import com.ning.http.client.providers.netty.handler.HttpProtocol;
import com.ning.http.client.providers.netty.handler.Processor;
import com.ning.http.client.providers.netty.handler.Protocol;
import com.ning.http.client.providers.netty.handler.WebSocketProtocol;
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

    private final NettyRequestSender nettyRequestSender;

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        this.config = config;

        if (config.getAsyncHttpProviderConfig() instanceof NettyAsyncHttpProviderConfig)
            nettyConfig = (NettyAsyncHttpProviderConfig) config.getAsyncHttpProviderConfig();
        else
            nettyConfig = new NettyAsyncHttpProviderConfig();

        allowStopNettyTimer = nettyConfig.getNettyTimer() == null;
        nettyTimer = allowStopNettyTimer ? newNettyTimer() : nettyConfig.getNettyTimer();

        ChannelPool channelPool = nettyConfig.getChannelPool();
        if (channelPool == null && config.isAllowPoolingConnections()) {
            channelPool = new DefaultChannelPool(config, nettyTimer);
        } else if (channelPool == null) {
            channelPool = new NoopChannelPool();
        }

        channelManager = new ChannelManager(config, nettyConfig, channelPool, nettyTimer);

        nettyRequestSender = new NettyRequestSender(config, nettyConfig, channelManager, nettyTimer, closed);

        Protocol webSocketProtocol = new WebSocketProtocol(channelManager, config, nettyRequestSender);
        Processor webSocketProcessor = new Processor(config, channelManager, nettyRequestSender, webSocketProtocol);

        Protocol httpProtocol = new HttpProtocol(channelManager, config, nettyRequestSender, webSocketProcessor);
        Processor httpProcessor = new Processor(config, channelManager, nettyRequestSender, httpProtocol);

        channelManager.configureBootstraps(httpProcessor, webSocketProcessor);
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
        return nettyRequestSender.doConnect(request, asyncHandler, null, true, false);
    }

    public boolean isClosed() {
        return closed.get();
    }
}
