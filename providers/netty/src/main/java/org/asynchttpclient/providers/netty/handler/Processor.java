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
package org.asynchttpclient.providers.netty.handler;

import static org.asynchttpclient.util.AsyncHttpProviderUtils.REMOTELY_CLOSED_EXCEPTION;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.DiscardEvent;
import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.StackTraceInspector;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Sharable
public class Processor extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(Processor.class);

    public static final IOException CHANNEL_CLOSED_EXCEPTION = new IOException("Channel Closed");
    static {
        CHANNEL_CLOSED_EXCEPTION.setStackTrace(new StackTraceElement[0]);
    }

    private final AsyncHttpClientConfig config;
    private final ChannelManager channelManager;
    private final NettyRequestSender requestSender;
    private final Protocol protocol;

    public Processor(AsyncHttpClientConfig config,//
            ChannelManager channelManager,//
            NettyRequestSender requestSender,//
            Protocol protocol) {
        this.config = config;
        this.channelManager = channelManager;
        this.requestSender = requestSender;
        this.protocol = protocol;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {

        Channel channel = ctx.channel();
        Object attribute = Channels.getAttribute(channel);

        if (attribute instanceof Callback && msg instanceof LastHttpContent) {
            Callback ac = (Callback) attribute;
            ac.call();
            Channels.setAttribute(channel, DiscardEvent.INSTANCE);

        } else if (attribute instanceof NettyResponseFuture) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
            protocol.handle(channel, future, msg);

        } else if (attribute != DiscardEvent.INSTANCE) {
            LOGGER.trace("Closing an orphan channel {}", channel);
            Channels.silentlyCloseChannel(channel);
        }
    }

    public void channelInactive(ChannelHandlerContext ctx) throws Exception {

        if (requestSender.isClosed())
            return;

        Channel channel = ctx.channel();
        channelManager.removeAll(channel);

        try {
            super.channelInactive(ctx);
        } catch (Exception ex) {
            LOGGER.trace("super.channelClosed", ex);
        }

        Object attribute = Channels.getAttribute(channel);
        LOGGER.debug("Channel Closed: {} with attribute {}", channel, attribute);

        if (attribute instanceof Callback) {
            Callback callback = (Callback) attribute;
            Channels.setAttribute(channel, callback.future());
            callback.call();

        } else if (attribute instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = NettyResponseFuture.class.cast(attribute);
            future.touch();

            if (!config.getIOExceptionFilters().isEmpty() && requestSender.applyIoExceptionFiltersAndReplayRequest(future, CHANNEL_CLOSED_EXCEPTION, channel))
                return;

            protocol.onClose(future);

            if (future.isDone())
                channelManager.closeChannel(channel);

            else if (!requestSender.retry(future))
                requestSender.abort(channel, future, REMOTELY_CLOSED_EXCEPTION);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        Throwable cause = e.getCause() != null ? e.getCause() : e;

        if (cause instanceof PrematureChannelClosureException || cause instanceof ClosedChannelException)
            return;

        Channel channel = ctx.channel();
        NettyResponseFuture<?> future = null;

        LOGGER.debug("Unexpected I/O exception on channel {}", channel, cause);

        try {
            Object attribute = Channels.getAttribute(channel);
            if (attribute instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) attribute;
                future.attachChannel(null, false);
                future.touch();

                if (cause instanceof IOException) {

                    // FIXME why drop the original exception and throw a new one?
                    if (!config.getIOExceptionFilters().isEmpty()) {
                        if (!requestSender.applyIoExceptionFiltersAndReplayRequest(future, CHANNEL_CLOSED_EXCEPTION, channel))
                            // Close the channel so the recovering can occurs.
                            Channels.silentlyCloseChannel(channel);
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

        if (future != null)
            try {
                LOGGER.debug("Was unable to recover Future: {}", future);
                requestSender.abort(channel, future, cause);
                protocol.onError(future, e);
            } catch (Throwable t) {
                LOGGER.error(t.getMessage(), t);
            }

        channelManager.closeChannel(channel);
        // FIXME not really sure
        // ctx.fireChannelRead(e);
        Channels.silentlyCloseChannel(channel);
    }
}
