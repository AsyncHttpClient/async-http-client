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
import org.asynchttpclient.channel.NameResolution;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;

public class NettyChannelConnector {
    
    private final AsyncHandlerExtensions asyncHandlerExtensions;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress[] remoteAddresses;
    private volatile int i = 0;

    public NettyChannelConnector(Request request, ProxyServer proxy, AsyncHandler<?> asyncHandler) throws UnknownHostException {

        this.asyncHandlerExtensions = asyncHandler instanceof AsyncHandlerExtensions ? (AsyncHandlerExtensions) asyncHandler : null;
        NameResolution[] resolutions;
        Uri uri = request.getUri();
        int port = uri.getExplicitPort();

        if (request.getAddress() != null) {
            resolutions = new NameResolution[] { new NameResolution(request.getAddress()) };

        } else if (proxy != null && !proxy.isIgnoredForHost(uri.getHost())) {
            resolutions = request.getNameResolver().resolve(proxy.getHost());
            port = uri.isSecured() ? proxy.getSecuredPort(): proxy.getPort();

        } else {
            resolutions = request.getNameResolver().resolve(uri.getHost());
        }

        if (asyncHandlerExtensions != null)
            asyncHandlerExtensions.onDnsResolved(resolutions);
        
        remoteAddresses = new InetSocketAddress[resolutions.length];
        for (int i = 0; i < resolutions.length; i ++) {
            remoteAddresses[i] = new InetSocketAddress(resolutions[i].address, port);
        }
        
        if (request.getLocalAddress() != null) {
            localAddress = new InetSocketAddress(request.getLocalAddress(), 0);
                    
        } else {
            localAddress = null;
        }
    }

    private boolean pickNextRemoteAddress() {
        i++;
        return i < remoteAddresses.length;
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
