/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;

import com.ning.http.client.providers.netty.NettyAsyncHttpProvider.DiscardEvent;

public final class Channels {

    private Channels() {
    }

    private static ChannelHandlerContext getAHCHandlerContext(Channel channel) {
        return channel.getPipeline().getContext(NettyAsyncHttpProvider.class);
    }

    public static Object getAttachment(ChannelHandlerContext ctx) {
        return ctx.getAttachment();
    }

    public static void setAttachment(ChannelHandlerContext ctx, Object attachment) {
        ctx.setAttachment(attachment);
    }

    public static Object getAttachment(Channel channel) {
        return getAHCHandlerContext(channel).getAttachment();
    }

    public static void setAttachment(Channel channel, Object attachment) {
        setAttachment(getAHCHandlerContext(channel), attachment);
    }

    public static void setDiscard(ChannelHandlerContext ctx) {
        ctx.setAttachment(DiscardEvent.INSTANCE);
    }

    public static void setDiscard(Channel channel) {
        setAttachment(channel, DiscardEvent.INSTANCE);
    }
}
