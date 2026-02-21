/*
 *    Copyright (c) 2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2SettingsAckFrame;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Http2ConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Http2ConnectionHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2SettingsFrame) {
            Http2SettingsFrame settings = (Http2SettingsFrame) msg;
            logger.debug("HTTP/2 SETTINGS received: {}", settings);
        } else if (msg instanceof Http2SettingsAckFrame) {
            logger.debug("HTTP/2 SETTINGS ACK received");
        } else if (msg instanceof Http2GoAwayFrame) {
            Http2GoAwayFrame goAway = (Http2GoAwayFrame) msg;
            logger.debug("HTTP/2 GOAWAY received on connection, lastStreamId={}, errorCode={}",
                    goAway.lastStreamId(), goAway.errorCode());
            // The connection is going away - streams in flight will be handled by their own handlers
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.debug("HTTP/2 connection exception on channel {}", ctx.channel(), cause);
    }
}
