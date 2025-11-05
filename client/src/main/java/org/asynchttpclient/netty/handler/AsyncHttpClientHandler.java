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

/**
 * Base handler for processing async HTTP client channel events in Netty pipeline.
 * <p>
 * This abstract handler manages the lifecycle of HTTP requests and responses, handling
 * channel read operations, exceptions, and connection state transitions. It provides
 * the foundation for specific protocol handlers (HTTP, WebSocket) by defining common
 * error handling and channel management logic.
 * </p>
 */
public abstract class AsyncHttpClientHandler extends ChannelInboundHandlerAdapter {

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  protected final AsyncHttpClientConfig config;
  protected final ChannelManager channelManager;
  protected final NettyRequestSender requestSender;
  final Interceptors interceptors;
  final boolean hasIOExceptionFilters;

  /**
   * Constructs a new AsyncHttpClientHandler.
   *
   * @param config the async HTTP client configuration
   * @param channelManager the channel manager for managing channel lifecycle
   * @param requestSender the request sender for sending HTTP requests
   */
  AsyncHttpClientHandler(AsyncHttpClientConfig config,
                                ChannelManager channelManager,
                                NettyRequestSender requestSender) {
    this.config = config;
    this.channelManager = channelManager;
    this.requestSender = requestSender;
    interceptors = new Interceptors(config, channelManager, requestSender);
    hasIOExceptionFilters = !config.getIoExceptionFilters().isEmpty();
  }

  /**
   * Processes incoming channel messages and routes them to the appropriate handler.
   * <p>
   * This method handles different types of channel attributes including {@link NettyResponseFuture},
   * {@link StreamedResponsePublisher}, and {@link OnLastHttpContentCallback}. It ensures proper
   * message routing and resource cleanup via reference counting.
   * </p>
   *
   * @param ctx the channel handler context
   * @param msg the message to process
   * @throws Exception if an error occurs during message processing
   */
  @Override
  public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {

    Channel channel = ctx.channel();
    Object attribute = Channels.getAttribute(channel);

    try {
      if (attribute instanceof OnLastHttpContentCallback) {
        if (msg instanceof LastHttpContent) {
          ((OnLastHttpContentCallback) attribute).call();
        }

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

  /**
   * Handles channel inactivation by cleaning up resources and managing connection state.
   * <p>
   * When a channel becomes inactive, this method removes it from the channel manager,
   * applies IO exception filters if configured, and handles unexpected closures. It
   * ensures proper cleanup of streaming publishers and callbacks.
   * </p>
   *
   * @param ctx the channel handler context
   * @throws Exception if an error occurs during channel cleanup
   */
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

  /**
   * Handles exceptions caught during channel operations.
   * <p>
   * This method processes exceptions by attempting recovery through IO exception filters,
   * and if recovery fails, aborts the request and closes the channel. It ignores expected
   * exceptions like {@link PrematureChannelClosureException} and {@link ClosedChannelException}.
   * </p>
   *
   * @param ctx the channel handler context
   * @param e the caught exception
   */
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

  /**
   * Handles channel activation by triggering an initial read operation.
   *
   * @param ctx the channel handler context
   */
  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    ctx.read();
  }

  /**
   * Handles channel read completion by triggering the next read if not using reactive streams.
   * <p>
   * When reactive streams (StreamedResponsePublisher) is active, read management is
   * delegated to the reactive streams implementation. Otherwise, this method triggers
   * the next read operation to continue processing.
   * </p>
   *
   * @param ctx the channel handler context
   */
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

  /**
   * Completes the processing of a request-response cycle.
   * <p>
   * This method cancels any active timeouts, optionally closes the channel or returns it
   * to the connection pool, and marks the future as done.
   * </p>
   *
   * @param future the response future to complete
   * @param channel the channel used for the request
   * @param close whether to close the channel (true) or return it to the pool (false)
   */
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

  /**
   * Processes a message read from the channel for a specific protocol.
   * <p>
   * This method must be implemented by protocol-specific handlers to process
   * incoming messages such as HTTP responses, WebSocket frames, etc.
   * </p>
   *
   * @param channel the channel the message was read from
   * @param future the response future associated with the request
   * @param message the message to handle
   * @throws Exception if an error occurs during message handling
   */
  public abstract void handleRead(Channel channel, NettyResponseFuture<?> future, Object message) throws Exception;

  /**
   * Handles exceptions specific to the protocol implementation.
   * <p>
   * This method is called when an exception occurs that requires protocol-specific
   * handling, such as notifying WebSocket listeners or cleaning up HTTP state.
   * </p>
   *
   * @param future the response future associated with the request
   * @param error the exception to handle
   */
  public abstract void handleException(NettyResponseFuture<?> future, Throwable error);

  /**
   * Handles channel inactivation specific to the protocol implementation.
   * <p>
   * This method is called when a channel becomes inactive and requires protocol-specific
   * cleanup, such as notifying WebSocket close handlers or handling unexpected HTTP disconnections.
   * </p>
   *
   * @param future the response future associated with the request
   */
  public abstract void handleChannelInactive(NettyResponseFuture<?> future);
}
