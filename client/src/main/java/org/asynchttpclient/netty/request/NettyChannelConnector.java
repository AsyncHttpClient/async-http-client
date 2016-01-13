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
import io.netty.channel.ChannelFuture;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.netty.SimpleChannelFutureListener;
import org.asynchttpclient.netty.channel.NettyConnectListener;

public class NettyChannelConnector {

    private final AsyncHandlerExtensions asyncHandlerExtensions;
    private final InetSocketAddress localAddress;
    private final List<InetSocketAddress> remoteAddresses;
    private volatile int i = 0;

    public NettyChannelConnector(InetAddress localAddress, List<InetSocketAddress> remoteAddresses, AsyncHandler<?> asyncHandler) {
        this.localAddress = localAddress != null ? new InetSocketAddress(localAddress, 0) : null;
        this.remoteAddresses = remoteAddresses;
        this.asyncHandlerExtensions = toAsyncHandlerExtensions(asyncHandler);
    }

    private boolean pickNextRemoteAddress() {
        i++;
        return i < remoteAddresses.size();
    }

    public void connect(final Bootstrap bootstrap, final NettyConnectListener<?> connectListener) {
        final InetSocketAddress remoteAddress = remoteAddresses.get(i);
        
        if (asyncHandlerExtensions != null)
            asyncHandlerExtensions.onTcpConnectAttempt(remoteAddress);

        final ChannelFuture future = localAddress != null ? bootstrap.connect(remoteAddress, localAddress) : bootstrap.connect(remoteAddress);

        future.addListener(new SimpleChannelFutureListener() {

            @Override
            public void onSuccess(Channel channel) throws Exception {
                if (asyncHandlerExtensions != null)
                    asyncHandlerExtensions.onTcpConnectSuccess(remoteAddress, future.channel());

                connectListener.onSuccess(channel);
            }

            @Override
            public void onFailure(Channel channel, Throwable t) throws Exception {
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
