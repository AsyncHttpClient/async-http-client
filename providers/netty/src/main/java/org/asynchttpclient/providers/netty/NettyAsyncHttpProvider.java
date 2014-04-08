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

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyAsyncHttpProvider implements AsyncHttpProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyConfig;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Channels channels;
    private final NettyRequestSender requestSender;

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        this.config = config;
        nettyConfig = config.getAsyncHttpProviderConfig() instanceof NettyAsyncHttpProviderConfig ? //
        NettyAsyncHttpProviderConfig.class.cast(config.getAsyncHttpProviderConfig())
                : new NettyAsyncHttpProviderConfig();

        channels = new Channels(config, nettyConfig);
        requestSender = new NettyRequestSender(closed, config, nettyConfig, channels);
    }

    @Override
    public String toString() {
        int availablePermits = channels.freeConnections != null ? channels.freeConnections.availablePermits() : 0;
        return String.format("NettyAsyncHttpProvider4:\n\t- maxConnections: %d\n\t- openChannels: %s\n\t- connectionPools: %s",
                config.getMaxTotalConnections() - availablePermits,//
                channels.openChannels.toString(),//
                channels.channelPool.toString());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                channels.close();
            } catch (Throwable t) {
                LOGGER.warn("Unexpected error on close", t);
            }
        }
    }

    @Override
    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        final URI uri = request.getURI();
        final String host = AsyncHttpProviderUtils.getHost(uri);
        final int port = AsyncHttpProviderUtils.getPort(uri);
        channels.configureProcessor(requestSender, closed, host, port);

        return requestSender.sendRequest(request, asyncHandler, null, false);
    }
}
