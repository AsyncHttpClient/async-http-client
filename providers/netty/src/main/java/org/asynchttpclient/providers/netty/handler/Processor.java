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

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.DiscardEvent;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.StackTraceInspector;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;

@Sharable
public class Processor extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(Processor.class);

    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;
    private final Channels channels;
    private final AtomicBoolean closed;
    private final Protocol protocol;

    public static Processor newHttpProcessor(AsyncHttpClientConfig config,//
            NettyAsyncHttpProviderConfig nettyConfig,//
            NettyRequestSender requestSender,//
            Channels channels,//
            AtomicBoolean isClose) {
        HttpProtocol protocol = new HttpProtocol(channels, config, nettyConfig, requestSender);
        return new Processor(config, nettyConfig, requestSender, channels, isClose, protocol);
    }

    public static Processor newWsProcessor(AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig,
            NettyRequestSender requestSender, Channels channels, AtomicBoolean isClose) {
        WebSocketProtocol protocol = new WebSocketProtocol(channels, config, nettyConfig, requestSender);
        return new Processor(config, nettyConfig, requestSender, channels, isClose, protocol);
    }

    private Processor(AsyncHttpClientConfig config,//
            NettyAsyncHttpProviderConfig nettyConfig,//
            NettyRequestSender requestSender,//
            Channels channels,//
            AtomicBoolean isClose,//
            Protocol protocol) {
        this.config = config;
        this.requestSender = requestSender;
        this.channels = channels;
        this.closed = isClose;
        this.protocol = protocol;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object e) throws Exception {

        Channel channel = ctx.channel();
        Object attribute = Channels.getDefaultAttribute(channel);

        if (attribute instanceof Callback && e instanceof LastHttpContent) {
            Callback ac = (Callback) attribute;
            ac.call();
            Channels.setDefaultAttribute(channel, DiscardEvent.INSTANCE);

        } else if (attribute instanceof NettyResponseFuture) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;

            protocol.handle(channel, future, e);

        } else if (attribute != DiscardEvent.INSTANCE) {
            try {
                LOGGER.trace("Closing an orphan channel {}", channel);
                channel.close();
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

        Channel channel = ctx.channel();
        channels.removeAll(channel);
        Object attachment = Channels.getDefaultAttribute(channel);
        LOGGER.debug("Channel Closed: {} with attachment {}", channel, attachment);

        if (attachment instanceof Callback) {
            Callback callback = (Callback) attachment;
            Channels.setDefaultAttribute(channel, callback.future());
            callback.call();

        } else if (attachment instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = NettyResponseFuture.class.cast(attachment);
            future.touch();

            if (!config.getIOExceptionFilters().isEmpty()
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(future, new IOException("Channel Closed"), channel)) {
                return;
            }

            protocol.onClose(channel);

            if (future != null && !future.isDone() && !future.isCancelled()) {
                if (!requestSender.retry(future, channel)) {
                    channels.abort(future, AsyncHttpProviderUtils.REMOTELY_CLOSED_EXCEPTION);
                }
            } else {
                channels.closeChannel(channel);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        Throwable cause = e.getCause() != null ? e.getCause() : e;

        if (cause instanceof PrematureChannelClosureException || cause instanceof ClosedChannelException) {
            return;
        }

        Channel channel = ctx.channel();
        NettyResponseFuture<?> future = null;

        LOGGER.debug("Unexpected I/O exception on channel {}", channel, cause);

        try {
            Object attribute = Channels.getDefaultAttribute(channel);
            if (attribute instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) attribute;
                future.attachChannel(null, false);
                future.touch();

                if (cause instanceof IOException) {

                    // FIXME why drop the original exception and create a new one?
                    if (!config.getIOExceptionFilters().isEmpty()) {
                        if (requestSender.applyIoExceptionFiltersAndReplayRequest(future, new IOException("Channel Closed"), channel)) {
                            return;
                        }
                    } else {
                        // Close the channel so the recovering can occur
                        try {
                            channel.close();
                        } catch (Throwable t) {
                            // Swallow.
                        }
                        return;
                    }
                }

                if (StackTraceInspector.abortOnReadOrWriteException(cause)) {
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

        protocol.onError(channel, e);

        channels.closeChannel(channel);
        // FIXME not really sure
        // ctx.fireChannelRead(e);
        ctx.close();
    }
}
