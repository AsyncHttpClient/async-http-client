/*
 * Copyright 2010-2013 Ning, Inc.
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
package org.asynchttpclient.providers.netty.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.DiscardEvent;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.NettyResponseFutures;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Sharable
public class NettyChannelHandler extends ChannelInboundHandlerAdapter {

    static final Logger LOGGER = LoggerFactory.getLogger(NettyChannelHandler.class);

    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;
    private final Channels channels;
    private final AtomicBoolean closed;
    private final Protocol httpProtocol;
    private final Protocol webSocketProtocol;

    public NettyChannelHandler(AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, NettyRequestSender requestSender, Channels channels, AtomicBoolean isClose) {
        this.config = config;
        this.requestSender = requestSender;
        this.channels = channels;
        this.closed = isClose;
        httpProtocol = new HttpProtocol(channels, config, nettyConfig, requestSender);
        webSocketProtocol = new WebSocketProtocol(channels, config, nettyConfig, requestSender);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object e) throws Exception {

        Object attribute = Channels.getDefaultAttribute(ctx);

        // FIXME is || !(e instanceof HttpContent) necessary?
        if (attribute instanceof Callback && (e instanceof LastHttpContent /* || !(e instanceof HttpContent) */)) {
            Callback ac = (Callback) attribute;
            ac.call();
            Channels.setDefaultAttribute(ctx, DiscardEvent.INSTANCE);

        } else if (attribute instanceof NettyResponseFuture) {
            Protocol p = (ctx.pipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;

            p.handle(ctx, future, e);

        } else if (attribute != DiscardEvent.INSTANCE) {
            try {
                LOGGER.trace("Closing an orphan channel {}", ctx.channel());
                ctx.channel().close();
            } catch (Throwable t) {
            }
        }
    }

    public void channelInactive(ChannelHandlerContext ctx) throws Exception {

        if (closed.get()) {
            return;
        }

        try {
            super.channelInactive(ctx);
        } catch (Exception ex) {
            LOGGER.trace("super.channelClosed", ex);
        }

        channels.removeFromPool(ctx);
        Object attachment = Channels.getDefaultAttribute(ctx);
        LOGGER.debug("Channel Closed: {} with attachment {}", ctx.channel(), attachment);

        if (attachment instanceof Callback) {
            Callback callback = (Callback) attachment;
            Channels.setDefaultAttribute(ctx, callback.future());
            callback.call();

        } else if (attachment instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = NettyResponseFuture.class.cast(attachment);
            future.touch();

            if (!config.getIOExceptionFilters().isEmpty() && requestSender.applyIoExceptionFiltersAndReplayRequest(ctx, future, new IOException("Channel Closed"))) {
                return;
            }

            Protocol p = (ctx.pipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
            p.onClose(ctx);

            if (future != null && !future.isDone() && !future.isCancelled()) {
                if (!requestSender.retry(ctx.channel(), future)) {
                    channels.abort(future, new IOException("Remotely Closed"));
                }
            } else {
                channels.closeChannel(ctx);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        Channel channel = ctx.channel();
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        NettyResponseFuture<?> future = null;

        if (cause instanceof PrematureChannelClosureException) {
            return;
        }

        LOGGER.debug("Unexpected I/O exception on channel {}", channel, cause);

        try {
            if (cause instanceof ClosedChannelException) {
                return;
            }

            Object attribute = Channels.getDefaultAttribute(ctx);
            if (attribute instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) attribute;
                future.attachChannel(null, false);
                future.touch();

                if (cause instanceof IOException) {

                    // FIXME why drop the original exception and create a new
                    // one?
                    if (!config.getIOExceptionFilters().isEmpty()) {
                        if (requestSender.applyIoExceptionFiltersAndReplayRequest(ctx, future, new IOException("Channel Closed"))) {
                            return;
                        }
                    } else {
                        // Close the channel so the recovering can occurs.
                        try {
                            ctx.channel().close();
                        } catch (Throwable t) {
                            // Swallow.
                        }
                        return;
                    }
                }

                if (NettyResponseFutures.abortOnReadCloseException(cause) || NettyResponseFutures.abortOnWriteCloseException(cause)) {
                    LOGGER.debug("Trying to recover from dead Channel: {}", channel);
                    return;
                }
            } else if (attribute instanceof Callback) {
                future = Callback.class.cast(attribute).future();
            }
        } catch (Throwable t) {
            cause = t;
        }

        if (future != null) {
            try {
                LOGGER.debug("Was unable to recover Future: {}", future);
                channels.abort(future, cause);
            } catch (Throwable t) {
                LOGGER.error(t.getMessage(), t);
            }
        }

        Protocol protocol = ctx.pipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol;
        protocol.onError(ctx, e);

        channels.closeChannel(ctx);
        // FIXME not really sure
        // ctx.fireChannelRead(e);
        ctx.close();
    }
}
