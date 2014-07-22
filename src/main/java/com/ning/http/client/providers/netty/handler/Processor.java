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
package com.ning.http.client.providers.netty.handler;

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
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.IOExceptionFilter;
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

    public static final IOException REMOTELY_CLOSED_EXCEPTION = new IOException("Remotely Closed");
    public static final IOException CHANNEL_CLOSED_EXCEPTION = new IOException("Channel Closed");
    static {
        REMOTELY_CLOSED_EXCEPTION.setStackTrace(new StackTraceElement[0]);
        CHANNEL_CLOSED_EXCEPTION.setStackTrace(new StackTraceElement[0]);
    }

    private final AsyncHttpClientConfig config;
    private final ChannelManager channelManager;
    private final NettyRequestSender nettyRequestSender;
    private final Protocol protocol;

    public Processor(AsyncHttpClientConfig config, ChannelManager channelManager, NettyRequestSender nettyRequestSender, Protocol protocol) {
        this.config = config;
        this.channelManager = channelManager;
        this.nettyRequestSender = nettyRequestSender;
        this.protocol = protocol;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, MessageEvent e) throws Exception {

        // call super to reset the read timeout
        super.messageReceived(ctx, e);

        Channel channel = ctx.getChannel();
        Object attachment = Channels.getAttachment(channel);

        if (attachment == null)
            LOGGER.debug("ChannelHandlerContext doesn't have any attachment");

        if (attachment == DiscardEvent.INSTANCE) {
            // discard

        } else if (attachment instanceof Callback) {
            Object message = e.getMessage();
            Callback ac = (Callback) attachment;
            if (message instanceof HttpChunk) {
                // the AsyncCallable is to be processed on the last chunk
                if (HttpChunk.class.cast(message).isLast())
                    // process the AsyncCallable before passing the message to the protocol
                    ac.call();
            } else {
                ac.call();
                Channels.setDiscard(channel);
            }

        } else if (attachment instanceof NettyResponseFuture<?>) {
            protocol.handle(channel, e, NettyResponseFuture.class.cast(attachment));

        } else {
            // unhandled message
            try {
                ctx.getChannel().close();
            } catch (Throwable t) {
                LOGGER.trace("Closing an orphan channel {}", ctx.getChannel());
            }
        }
    }

    private FilterContext<?> handleIoException(FilterContext<?> fc, NettyResponseFuture<?> future) {
        for (IOExceptionFilter asyncFilter : config.getIOExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            } catch (FilterException efe) {
                nettyRequestSender.abort(future, efe);
            }
        }
        return fc;
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

        if (nettyRequestSender.isClosed())
            return;

        Channel channel = ctx.getChannel();
        channelManager.removeAll(channel);

        try {
            super.channelClosed(ctx, e);
        } catch (Exception ex) {
            LOGGER.trace("super.channelClosed", ex);
        }

        Object attachment = Channels.getAttachment(channel);
        LOGGER.debug("Channel Closed: {} with attachment {}", channel, attachment);

        if (attachment instanceof Callback) {
            Callback ac = (Callback) attachment;
            Channels.setAttachment(channel, ac.future());
            ac.call();

        } else if (attachment instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;
            future.touch();

            if (!config.getIOExceptionFilters().isEmpty()) {
                FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler())
                        .request(future.getRequest()).ioException(CHANNEL_CLOSED_EXCEPTION).build();
                fc = handleIoException(fc, future);

                if (fc.replayRequest() && future.canBeReplay()) {
                    nettyRequestSender.replayRequest(future, fc, channel);
                    return;
                }
            }

            protocol.onClose(channel, e);

            if (future == null || future.isDone())
                channelManager.closeChannel(channel);

            else if (!nettyRequestSender.retry(ctx.getChannel(), future))
                nettyRequestSender.abort(future, REMOTELY_CLOSED_EXCEPTION);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        Channel channel = ctx.getChannel();
        Throwable cause = e.getCause();
        NettyResponseFuture<?> future = null;

        if (e.getCause() instanceof PrematureChannelClosureException)
            return;

        LOGGER.debug("Unexpected I/O exception on channel {}", channel, cause);

        try {
            if (cause instanceof ClosedChannelException) {
                return;
            }

            Object attachment = Channels.getAttachment(channel);
            if (attachment instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) attachment;
                future.attachChannel(null, false);
                future.touch();

                if (cause instanceof IOException) {

                    if (!config.getIOExceptionFilters().isEmpty()) {
                        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler())
                                .request(future.getRequest()).ioException(new IOException("Channel Closed")).build();
                        fc = handleIoException(fc, future);

                        if (fc.replayRequest()) {
                            nettyRequestSender.replayRequest(future, fc, channel);
                            return;
                        }
                    } else {
                        // Close the channel so the recovering can occurs.
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
            } else if (attachment instanceof Callback) {
                future = ((Callback) attachment).future();
            }
        } catch (Throwable t) {
            cause = t;
        }

        if (future != null) {
            try {
                LOGGER.debug("Was unable to recover Future: {}", future);
                nettyRequestSender.abort(future, cause);
            } catch (Throwable t) {
                LOGGER.error(t.getMessage(), t);
            }
        }

        protocol.onError(channel, e);

        channelManager.closeChannel(channel);
        ctx.sendUpstream(e);
    }
}
