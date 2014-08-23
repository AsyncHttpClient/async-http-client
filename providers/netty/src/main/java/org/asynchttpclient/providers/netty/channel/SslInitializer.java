/*
 * Copyright 2014 AsyncHttpClient Project.
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
package org.asynchttpclient.providers.netty.channel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * On connect, replaces itself with a SslHandler that has a SSLEngine configured with the remote host and port.
 * 
 * @author slandelle
 */
public class SslInitializer extends ChannelOutboundHandlerAdapter {

    private final ChannelManager channelManager;

    public SslInitializer(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise)
            throws Exception {

        InetSocketAddress remoteInetSocketAddress = (InetSocketAddress) remoteAddress;
        String peerHost = remoteInetSocketAddress.getHostString();
        int peerPort = remoteInetSocketAddress.getPort();

        SslHandler sslHandler = channelManager.createSslHandler(peerHost, peerPort);

        ctx.pipeline().replace(ChannelManager.SSL_HANDLER, ChannelManager.SSL_HANDLER, sslHandler);

        ctx.connect(remoteAddress, localAddress, promise);
    }
}
