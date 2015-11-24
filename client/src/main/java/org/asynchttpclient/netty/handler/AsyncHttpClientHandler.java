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
package org.asynchttpclient.netty.handler;

import static org.asynchttpclient.util.MiscUtils.getCause;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.exception.ChannelClosedException;
import org.asynchttpclient.netty.Callback;
import org.asynchttpclient.netty.DiscardEvent;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.future.StackTraceInspector;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Sharable
public class AsyncHttpClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncHttpClientHandler.class);

    private final AsyncHttpClientConfig config;
    private final ChannelManager channelManager;
    private final NettyRequestSender requestSender;
    private final Protocol protocol;

    public AsyncHttpClientHandler(AsyncHttpClientConfig config,//
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

        try {
            if (attribute instanceof Callback) {
                Callback ac = (Callback) attribute;
                if (msg instanceof LastHttpContent) {
                    ac.call();
                } else if (!(msg instanceof HttpContent)) {
                    LOGGER.info("Received unexpected message while expecting a chunk: " + msg);
                    ac.call();
                    Channels.setDiscard(channel);
                }

            } else if (attribute instanceof NettyResponseFuture) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
                protocol.handle(channel, future, msg);

            } else if (attribute instanceof StreamedResponsePublisher) {

                StreamedResponsePublisher publisher = (StreamedResponsePublisher) attribute;

                if(msg instanceof HttpContent) {
                    ByteBuf content = ((HttpContent) msg).content();
                    // Republish as a HttpResponseBodyPart
                    if (content.readableBytes() > 0) {
                        HttpResponseBodyPart part = config.getResponseBodyPartFactory().newResponseBodyPart(content, false);
                        ctx.fireChannelRead(part);
                    }
                    if (msg instanceof LastHttpContent) {
                        // Remove the handler from the pipeline, this will trigger
                        // it to finish
                        ctx.pipeline().remove(publisher);
                        // Trigger a read, just in case the last read complete
                        // triggered no new read
                        ctx.read();
                        // Send the last content on to the protocol, so that it can
                        // conclude the cleanup
                        protocol.handle(channel, publisher.future(), msg);
                    }
                } else {
                    LOGGER.info("Received unexpected message while expecting a chunk: " + msg);
                    ctx.pipeline().remove((StreamedResponsePublisher) attribute);
                    Channels.setDiscard(channel);
                }
            } else if (attribute != DiscardEvent.INSTANCE) {
                // unhandled message
                LOGGER.debug("Orphan channel {} with attribute {} received message {}, closing", channel, attribute, msg);
                Channels.silentlyCloseChannel(channel);
            }
        } finally {
            ReferenceCountUtil.release(msg);
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
        if (attribute instanceof StreamedResponsePublisher) {
            // setting `attribute` to be the underlying future so that the retry
            // logic can kick-in
            attribute = ((StreamedResponsePublisher) attribute).future();
        }
        if (attribute instanceof Callback) {
            Callback callback = (Callback) attribute;
            Channels.setAttribute(channel, callback.future());
            callback.call();

        } else if (attribute instanceof NettyResponseFuture<?>) {
            NettyResponseFuture<?> future = NettyResponseFuture.class.cast(attribute);
            future.touch();

            if (!config.getIoExceptionFilters().isEmpty() && requestSender.applyIoExceptionFiltersAndReplayRequest(future, ChannelClosedException.INSTANCE, channel))
                return;

            protocol.onClose(future);
            requestSender.handleUnexpectedClosedChannel(channel, future);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        Throwable cause = getCause(e.getCause());

        if (cause instanceof PrematureChannelClosureException || cause instanceof ClosedChannelException)
            return;

        Channel channel = ctx.channel();
        NettyResponseFuture<?> future = null;

        LOGGER.debug("Unexpected I/O exception on channel {}", channel, cause);

        try {
            Object attribute = Channels.getAttribute(channel);
            if (attribute instanceof StreamedResponsePublisher) {
                ctx.fireExceptionCaught(e);
                // setting `attribute` to be the underlying future so that the
                // retry logic can kick-in
                attribute = ((StreamedResponsePublisher) attribute).future();
            }
            if (attribute instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) attribute;
                future.attachChannel(null, false);
                future.touch();

                if (cause instanceof IOException) {

                    // FIXME why drop the original exception and throw a new
                    // one?
                    if (!config.getIoExceptionFilters().isEmpty()) {
                        if (!requestSender.applyIoExceptionFiltersAndReplayRequest(future, ChannelClosedException.INSTANCE, channel))
                            // Close the channel so the recovering can occurs.
                            Channels.silentlyCloseChannel(channel);
                        return;
                    }
                }

                if (StackTraceInspector.recoverOnReadOrWriteException(cause)) {
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

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.read();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (!isHandledByReactiveStreams(ctx)) {
            ctx.read();
        } else {
            ctx.fireChannelReadComplete();
        }
    }

    private boolean isHandledByReactiveStreams(ChannelHandlerContext ctx) {
        return Channels.getAttribute(ctx.channel()) instanceof StreamedResponsePublisher;
    }
}
