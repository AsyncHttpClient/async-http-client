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

import java.net.InetSocketAddress;

/**
 * On connect, replaces itself with a SslHandler that has a SSLEngine configured with the remote host and port.
 * 
 * @author slandelle
 */
public class SslInitializer extends SimpleChannelDownstreamHandler {

    private final ChannelManager channelManager;

    public SslInitializer(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    public void connectRequested(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

        InetSocketAddress remoteInetSocketAddress = (InetSocketAddress) e.getValue();
        String peerHost = remoteInetSocketAddress.getHostString();
        int peerPort = remoteInetSocketAddress.getPort();

        SslHandler sslHandler = channelManager.createSslHandler(peerHost, peerPort);

        ctx.getPipeline().replace(ChannelManager.SSL_HANDLER, ChannelManager.SSL_HANDLER, sslHandler);

        ctx.sendDownstream(e);
    }
}
