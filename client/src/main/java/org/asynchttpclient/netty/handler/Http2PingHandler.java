/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends HTTP/2 PING frames when the connection is idle and closes the connection
 * if no PING ACK is received within the timeout period.
 */
public class Http2PingHandler extends ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Http2PingHandler.class);
    private static final long PING_ACK_TIMEOUT_MS = 5000;

    private boolean waitingForPingAck;
    private ScheduledFuture<?> pingAckTimeoutFuture;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleEvent = (IdleStateEvent) evt;
            if (idleEvent.state() == IdleState.ALL_IDLE && !waitingForPingAck) {
                waitingForPingAck = true;
                LOGGER.debug("Sending HTTP/2 PING on idle connection {}", ctx.channel());
                ctx.writeAndFlush(new DefaultHttp2PingFrame(System.nanoTime(), false));

                pingAckTimeoutFuture = ctx.executor().schedule(() -> {
                    if (waitingForPingAck) {
                        LOGGER.debug("PING ACK timeout on connection {}, closing", ctx.channel());
                        ctx.close();
                    }
                }, PING_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2PingFrame) {
            Http2PingFrame pingFrame = (Http2PingFrame) msg;
            if (pingFrame.ack()) {
                waitingForPingAck = false;
                if (pingAckTimeoutFuture != null) {
                    pingAckTimeoutFuture.cancel(false);
                    pingAckTimeoutFuture = null;
                }
                LOGGER.debug("Received PING ACK on connection {}", ctx.channel());
                return; // consume the PING ACK
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (pingAckTimeoutFuture != null) {
            pingAckTimeoutFuture.cancel(false);
            pingAckTimeoutFuture = null;
        }
    }
}
