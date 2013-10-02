/*
 * Copyright 2010-2013 Ning, Inc.
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
package org.asynchttpclient.providers.netty;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.handler.NettyChannelHandler;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyAsyncHttpProvider implements AsyncHttpProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyConfig;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Channels channels;
    private final NettyRequestSender requestSender;
    private final NettyChannelHandler channelHandler;

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        this.config = config;
        nettyConfig = config.getAsyncHttpProviderConfig() instanceof NettyAsyncHttpProviderConfig ? //
        NettyAsyncHttpProviderConfig.class.cast(config.getAsyncHttpProviderConfig())
                : new NettyAsyncHttpProviderConfig();

        channels = new Channels(config, nettyConfig);
        requestSender = new NettyRequestSender(closed, config, channels);
        channelHandler = new NettyChannelHandler(config, nettyConfig, requestSender, channels, closed);
        channels.configure(channelHandler);
    }

    @Override
    public String toString() {
        return String.format("NettyAsyncHttpProvider4:\n\t- maxConnections: %d\n\t- openChannels: %s\n\t- connectionPools: %s", config.getMaxTotalConnections()
                - channels.freeConnections.availablePermits(), channels.openChannels.toString(), channels.connectionsPool.toString());
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            channels.close();
            config.reaper().shutdown();
        } catch (Throwable t) {
            LOGGER.warn("Unexpected error on close", t);
        }
    }

    @Override
    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        return requestSender.sendRequest(request, asyncHandler, null, nettyConfig.isAsyncConnect(), false);
    }
}
