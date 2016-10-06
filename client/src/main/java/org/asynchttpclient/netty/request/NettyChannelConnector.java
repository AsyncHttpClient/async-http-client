/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request;

import static org.asynchttpclient.handler.AsyncHandlerExtensionsUtils.toAsyncHandlerExtensions;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.netty.SimpleChannelFutureListener;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.channel.NettyConnectListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyChannelConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyChannelConnector.class);

    private final AsyncHandlerExtensions asyncHandlerExtensions;
    private final InetSocketAddress localAddress;
    private final List<InetSocketAddress> remoteAddresses;
    private final AsyncHttpClientState clientState;
    private final boolean connectionTtlEnabled;
    private volatile int i = 0;

    public NettyChannelConnector(InetAddress localAddress,//
            List<InetSocketAddress> remoteAddresses,//
            AsyncHandler<?> asyncHandler,//
            AsyncHttpClientState clientState,//
            AsyncHttpClientConfig config) {
        this.localAddress = localAddress != null ? new InetSocketAddress(localAddress, 0) : null;
        this.remoteAddresses = remoteAddresses;
        this.asyncHandlerExtensions = toAsyncHandlerExtensions(asyncHandler);
        this.clientState = clientState;
        this.connectionTtlEnabled = config.getConnectionTtl() > 0;
    }

    private boolean pickNextRemoteAddress() {
        i++;
        return i < remoteAddresses.size();
    }

    public void connect(final Bootstrap bootstrap, final NettyConnectListener<?> connectListener) {
        final InetSocketAddress remoteAddress = remoteAddresses.get(i);

        if (asyncHandlerExtensions != null)
            asyncHandlerExtensions.onTcpConnectAttempt(remoteAddress);

        try {
            connect0(bootstrap, connectListener, remoteAddress);
        } catch (RejectedExecutionException e) {
            if (clientState.isClosed()) {
                connectListener.onFailure(null, e);
            } else {
                LOGGER.info("Connect crash but engine is shutting down");
            }
        }
    }

    private void connect0(Bootstrap bootstrap, final NettyConnectListener<?> connectListener, InetSocketAddress remoteAddress) {

        bootstrap.connect(remoteAddress, localAddress)//
                .addListener(new SimpleChannelFutureListener() {

                    @Override
                    public void onSuccess(Channel channel) {
                        if (asyncHandlerExtensions != null) {
                            asyncHandlerExtensions.onTcpConnectSuccess(remoteAddress, channel);
                        }
                        if (connectionTtlEnabled) {
                            Channels.initChannelId(channel);
                        }
                        connectListener.onSuccess(channel, remoteAddress);
                    }

                    @Override
                    public void onFailure(Channel channel, Throwable t) {
                        if (asyncHandlerExtensions != null)
                            asyncHandlerExtensions.onTcpConnectFailure(remoteAddress, t);
                        boolean retry = pickNextRemoteAddress();
                        if (retry)
                            NettyChannelConnector.this.connect(bootstrap, connectListener);
                        else
                            connectListener.onFailure(channel, t);
                    }
                });
    }
}
