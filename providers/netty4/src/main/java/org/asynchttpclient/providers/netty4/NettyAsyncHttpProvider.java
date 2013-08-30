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
package org.asynchttpclient.providers.netty4;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyAsyncHttpProvider implements AsyncHttpProvider {

    static final Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProvider.class);

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig asyncHttpProviderConfig;
    private final AtomicBoolean isClose = new AtomicBoolean(false);
    private final Channels channels;
    private final NettyRequestSender requestSender;
    private final NettyChannelHandler channelHandler;
    private final boolean executeConnectAsync;

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {

        this.config = config;
        if (config.getAsyncHttpProviderConfig() instanceof NettyAsyncHttpProviderConfig) {
            asyncHttpProviderConfig = NettyAsyncHttpProviderConfig.class.cast(config.getAsyncHttpProviderConfig());
        } else {
            asyncHttpProviderConfig = new NettyAsyncHttpProviderConfig();
        }

        channels = new Channels(config, asyncHttpProviderConfig);
        requestSender = new NettyRequestSender(isClose, config, channels);
        channelHandler = new NettyChannelHandler(config, requestSender, channels, isClose);
        channels.configure(channelHandler);

        executeConnectAsync = asyncHttpProviderConfig.isAsyncConnect();
        // FIXME
        // if (!executeConnectAsync) {
        // DefaultChannelFuture.setUseDeadLockChecker(true);
        // }
    }

    @Override
    public String toString() {
        return String.format("NettyAsyncHttpProvider4:\n\t- maxConnections: %d\n\t- openChannels: %s\n\t- connectionPools: %s", config.getMaxTotalConnections()
                - channels.freeConnections.availablePermits(), channels.openChannels.toString(), channels.connectionsPool.toString());
    }

    @Override
    public void close() {
        isClose.set(true);
        try {
            channels.close();
            config.executorService().shutdown();
            config.reaper().shutdown();
        } catch (Throwable t) {
            LOGGER.warn("Unexpected error on close", t);
        }
    }

    @Override
    public Response prepareResponse(final HttpResponseStatus status, final HttpResponseHeaders headers, final List<HttpResponseBodyPart> bodyParts) {
        throw new UnsupportedOperationException("Mocked, should be refactored");
    }

    @Override
    public <T> ListenableFuture<T> execute(Request request, final AsyncHandler<T> asyncHandler) throws IOException {
        return requestSender.doConnect(request, asyncHandler, null, true, executeConnectAsync, false);
    }
}
