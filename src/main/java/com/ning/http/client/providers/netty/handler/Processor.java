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
package com.ning.http.client.providers.netty.handler;

import static com.ning.http.util.MiscUtils.buildStaticIOException;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.PrematureChannelClosureException;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.Callback;
import com.ning.http.client.providers.netty.DiscardEvent;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.future.StackTraceInspector;
import com.ning.http.client.providers.netty.request.NettyRequestSender;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

public class Processor extends SimpleChannelUpstreamHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Processor.class);

    public static final IOException CHANNEL_CLOSED_EXCEPTION = buildStaticIOException("Channel closed");

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
    public void messageReceived(final ChannelHandlerContext ctx, MessageEvent e) throws Exception {

        // call super to reset the read timeout
        super.messageReceived(ctx, e);

        Channel channel = ctx.getChannel();
        Object attribute = Channels.getAttribute(channel);

        if (attribute instanceof Callback) {
            Object message = e.getMessage();
            Callback ac = (Callback) attribute;
            if (message instanceof HttpChunk) {
                // the AsyncCallable is to be processed on the last chunk
                if (HttpChunk.class.cast(message).isLast())
                    // process the AsyncCallable before passing the message to the protocol
                    ac.call();
                    // FIXME remove attribute?
            } else {
                LOGGER.info("Received unexpected message while expecting a chunk: " + message);
                ac.call();
                Channels.setDiscard(channel);
            }

        } else if (attribute instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
            protocol.handle(channel, future, e.getMessage());

        } else if (attribute != DiscardEvent.INSTANCE) {
            // unhandled message
            LOGGER.debug("Orphan channel {} with attribute {} received message {}, closing", channel, attribute, e.getMessage());
            Channels.silentlyCloseChannel(channel);
        }
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

        if (requestSender.isClosed())
            return;

        Channel channel = ctx.getChannel();
        channelManager.removeAll(channel);

        try {
            super.channelClosed(ctx, e);
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
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
            future.touch();

            if (!config.getIOExceptionFilters().isEmpty()
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(future, CHANNEL_CLOSED_EXCEPTION, channel))
                return;

            protocol.onClose(future);
            requestSender.handleUnexpectedClosedChannel(channel, future);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        Channel channel = ctx.getChannel();
        Throwable cause = e.getCause();
        NettyResponseFuture<?> future = null;

        // FIXME we can't get a PrematureChannelClosureException as we create the HttpClientCodec without setting failOnMissingResponse to true
        if (cause instanceof PrematureChannelClosureException || cause instanceof ClosedChannelException)
            return;

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

                // FIXME how does recovery occur?!
                if (StackTraceInspector.recoverOnReadOrWriteException(cause)) {
                    LOGGER.debug("Trying to recover from dead Channel: {}", channel);
                    return;
                }
            } else if (attribute instanceof Callback) {
                future = ((Callback) attribute).future();
            }
        } catch (Throwable t) {
            cause = t;
        }

        if (future != null)
            try {
                LOGGER.debug("Was unable to recover Future: {}", future);
                requestSender.abort(channel, future, cause);
                protocol.onError(future, e.getCause());
            } catch (Throwable t) {
                LOGGER.error(t.getMessage(), t);
            }

        channelManager.closeChannel(channel);
        ctx.sendUpstream(e);
    }
}
