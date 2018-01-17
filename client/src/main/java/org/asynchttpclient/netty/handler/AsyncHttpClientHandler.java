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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.exception.ChannelClosedException;
import org.asynchttpclient.netty.DiscardEvent;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.future.StackTraceInspector;
import org.asynchttpclient.netty.handler.intercept.Interceptors;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import static org.asynchttpclient.util.MiscUtils.getCause;

public abstract class AsyncHttpClientHandler extends ChannelInboundHandlerAdapter {

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  protected final AsyncHttpClientConfig config;
  protected final ChannelManager channelManager;
  protected final NettyRequestSender requestSender;
  final Interceptors interceptors;
  final boolean hasIOExceptionFilters;

  AsyncHttpClientHandler(AsyncHttpClientConfig config,
                                ChannelManager channelManager,
                                NettyRequestSender requestSender) {
    this.config = config;
    this.channelManager = channelManager;
    this.requestSender = requestSender;
    interceptors = new Interceptors(config, channelManager, requestSender);
    hasIOExceptionFilters = !config.getIoExceptionFilters().isEmpty();
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {

    Channel channel = ctx.channel();
    Object attribute = Channels.getAttribute(channel);

    try {
      if (attribute instanceof OnLastHttpContentCallback && msg instanceof LastHttpContent) {
        ((OnLastHttpContentCallback) attribute).call();

      } else if (attribute instanceof NettyResponseFuture) {
        NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
        future.touch();
        handleRead(channel, future, msg);

      } else if (attribute instanceof StreamedResponsePublisher) {
        StreamedResponsePublisher publisher = (StreamedResponsePublisher) attribute;
        publisher.future().touch();

        if (msg instanceof HttpContent) {
          ByteBuf content = ((HttpContent) msg).content();
          // Republish as a HttpResponseBodyPart
          if (content.isReadable()) {
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
            handleRead(channel, publisher.future(), msg);
          }
        } else {
          logger.info("Received unexpected message while expecting a chunk: " + msg);
          ctx.pipeline().remove(publisher);
          Channels.setDiscard(channel);
        }
      } else if (attribute != DiscardEvent.DISCARD) {
        // unhandled message
        logger.debug("Orphan channel {} with attribute {} received message {}, closing", channel, attribute, msg);
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

    Object attribute = Channels.getAttribute(channel);
    logger.debug("Channel Closed: {} with attribute {}", channel, attribute);
    if (attribute instanceof StreamedResponsePublisher) {
      // setting `attribute` to be the underlying future so that the retry
      // logic can kick-in
      attribute = ((StreamedResponsePublisher) attribute).future();
    }
    if (attribute instanceof OnLastHttpContentCallback) {
      OnLastHttpContentCallback callback = (OnLastHttpContentCallback) attribute;
      Channels.setAttribute(channel, callback.future());
      callback.call();

    } else if (attribute instanceof NettyResponseFuture<?>) {
      NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;
      future.touch();

      if (hasIOExceptionFilters && requestSender.applyIoExceptionFiltersAndReplayRequest(future, ChannelClosedException.INSTANCE, channel))
        return;

      handleChannelInactive(future);
      requestSender.handleUnexpectedClosedChannel(channel, future);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
    Throwable cause = getCause(e);

    if (cause instanceof PrematureChannelClosureException || cause instanceof ClosedChannelException)
      return;

    Channel channel = ctx.channel();
    NettyResponseFuture<?> future = null;

    logger.debug("Unexpected I/O exception on channel {}", channel, cause);

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

          // FIXME why drop the original exception and throw a new one?
          if (hasIOExceptionFilters) {
            if (!requestSender.applyIoExceptionFiltersAndReplayRequest(future, ChannelClosedException.INSTANCE, channel)) {
              // Close the channel so the recovering can occurs.
              Channels.silentlyCloseChannel(channel);
            }
            return;
          }
        }

        if (StackTraceInspector.recoverOnReadOrWriteException(cause)) {
          logger.debug("Trying to recover from dead Channel: {}", channel);
          future.pendingException = cause;
          return;
        }
      } else if (attribute instanceof OnLastHttpContentCallback) {
        future = OnLastHttpContentCallback.class.cast(attribute).future();
      }
    } catch (Throwable t) {
      cause = t;
    }

    if (future != null)
      try {
        logger.debug("Was unable to recover Future: {}", future);
        requestSender.abort(channel, future, cause);
        handleException(future, e);
      } catch (Throwable t) {
        logger.error(t.getMessage(), t);
      }

    channelManager.closeChannel(channel);
    // FIXME not really sure
    // ctx.fireChannelRead(e);
    Channels.silentlyCloseChannel(channel);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    ctx.read();
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    if (!isHandledByReactiveStreams(ctx)) {
      ctx.read();
    } else {
      ctx.fireChannelReadComplete();
    }
  }

  private boolean isHandledByReactiveStreams(ChannelHandlerContext ctx) {
    return Channels.getAttribute(ctx.channel()) instanceof StreamedResponsePublisher;
  }

  void finishUpdate(NettyResponseFuture<?> future, Channel channel, boolean close) {
    future.cancelTimeouts();

    if (close) {
      channelManager.closeChannel(channel);
    } else {
      channelManager.tryToOfferChannelToPool(channel, future.getAsyncHandler(), true, future.getPartitionKey());
    }

    try {
      future.done();
    } catch (Exception t) {
      // Never propagate exception once we know we are done.
      logger.debug(t.getMessage(), t);
    }
  }

  public abstract void handleRead(Channel channel, NettyResponseFuture<?> future, Object message) throws Exception;

  public abstract void handleException(NettyResponseFuture<?> future, Throwable error);

  public abstract void handleChannelInactive(NettyResponseFuture<?> future);
}
