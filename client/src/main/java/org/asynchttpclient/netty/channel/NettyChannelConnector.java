/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.netty.SimpleChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class NettyChannelConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyChannelConnector.class);

    private static final AtomicIntegerFieldUpdater<NettyChannelConnector> I_UPDATER = AtomicIntegerFieldUpdater
            .newUpdater(NettyChannelConnector.class, "i");

    private final AsyncHandler<?> asyncHandler;
    private final InetSocketAddress localAddress;
    private final List<InetSocketAddress> remoteAddresses;
    private final AsyncHttpClientState clientState;
    private volatile int i;

    public NettyChannelConnector(InetAddress localAddress, List<InetSocketAddress> remoteAddresses, AsyncHandler<?> asyncHandler, AsyncHttpClientState clientState) {
        this.localAddress = localAddress != null ? new InetSocketAddress(localAddress, 0) : null;
        this.remoteAddresses = remoteAddresses;
        this.asyncHandler = asyncHandler;
        this.clientState = clientState;
    }

    private boolean pickNextRemoteAddress() {
        I_UPDATER.incrementAndGet(this);
        return i < remoteAddresses.size();
    }

    public void connect(final Bootstrap bootstrap, final NettyConnectListener<?> connectListener) {
        final InetSocketAddress remoteAddress = remoteAddresses.get(i);

        try {
            asyncHandler.onTcpConnectAttempt(remoteAddress);
        } catch (Exception e) {
            LOGGER.error("onTcpConnectAttempt crashed", e);
            connectListener.onFailure(null, e);
            return;
        }

        try {
            connect0(bootstrap, connectListener, remoteAddress);
        } catch (RejectedExecutionException e) {
            if (clientState.isClosed()) {
                LOGGER.info("Connect crash but engine is shutting down");
            } else {
                connectListener.onFailure(null, e);
            }
        }
    }

    private void connect0(Bootstrap bootstrap, final NettyConnectListener<?> connectListener, InetSocketAddress remoteAddress) {
        bootstrap.connect(remoteAddress, localAddress)
                .addListener(new SimpleChannelFutureListener() {
                    @Override
                    public void onSuccess(Channel channel) {
                        try {
                            asyncHandler.onTcpConnectSuccess(remoteAddress, channel);
                        } catch (Exception e) {
                            LOGGER.error("onTcpConnectSuccess crashed", e);
                            connectListener.onFailure(channel, e);
                            return;
                        }
                        connectListener.onSuccess(channel, remoteAddress);
                    }

                    @Override
                    public void onFailure(Channel channel, Throwable t) {
                        try {
                            asyncHandler.onTcpConnectFailure(remoteAddress, t);
                        } catch (Exception e) {
                            LOGGER.error("onTcpConnectFailure crashed", e);
                            connectListener.onFailure(channel, e);
                            return;
                        }
                        boolean retry = pickNextRemoteAddress();
                        if (retry) {
                            connect(bootstrap, connectListener);
                        } else {
                            connectListener.onFailure(channel, t);
                        }
                    }
                });
    }
}
