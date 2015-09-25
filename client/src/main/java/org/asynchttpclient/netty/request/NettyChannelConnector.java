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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.channel.ChannelConnector;
import org.asynchttpclient.proxy.ProxyServer;

public class NettyChannelConnector extends ChannelConnector {

    public NettyChannelConnector(Request request, ProxyServer proxy, boolean useProxy, AsyncHandler<?> asyncHandler) throws UnknownHostException {
        super(request, proxy, useProxy, asyncHandler);
    }

    public void connect(final Bootstrap bootstrap, final ChannelFutureListener listener) throws UnknownHostException {
        final InetSocketAddress remoteAddress = remoteAddresses[i];

        ChannelFuture future = localAddress != null ? bootstrap.connect(remoteAddress, localAddress) : bootstrap.connect(remoteAddress);

        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                boolean retry = false;
                if (future.isSuccess()) {
                    if (asyncHandlerExtensions != null)
                        asyncHandlerExtensions.onConnectionSuccess(future.channel(), remoteAddress.getAddress());
                } else {
                    if (asyncHandlerExtensions != null)
                        asyncHandlerExtensions.onConnectionFailure(remoteAddress.getAddress());
                    retry = pickNextRemoteAddress();
                }
                if (retry)
                    NettyChannelConnector.this.connect(bootstrap, listener);
                else
                    listener.operationComplete(future);
            }
        });
    }
}
