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
package com.ning.http.client.providers.netty.channel;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.handler.ssl.SslHandler;

import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;

import java.net.InetSocketAddress;

/**
 * On connect, replaces itself with a SslHandler that has a SSLEngine configured with the remote host and port.
 * 
 * @author slandelle
 */
public class SslInitializer extends SimpleChannelDownstreamHandler {

    private final NettyAsyncHttpProvider provider;

    public SslInitializer(NettyAsyncHttpProvider provider) {
        this.provider = provider;
    }

    public void connectRequested(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        
        InetSocketAddress remoteInetSocketAddress = (InetSocketAddress) e.getValue();
        String peerHost = remoteInetSocketAddress.getHostName();
        int peerPort = remoteInetSocketAddress.getPort();

        SslHandler sslHandler = provider.createSslHandler(peerHost, peerPort);

        ctx.getPipeline().replace(NettyAsyncHttpProvider.SSL_HANDLER, NettyAsyncHttpProvider.SSL_HANDLER, sslHandler);

        ctx.sendDownstream(e);
    }
}
