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
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.DecoderResultProvider;
import io.netty.handler.codec.http.*;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.NettyResponseStatus;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * HTTP protocol handler for processing HTTP request-response cycles.
 * <p>
 * This handler manages HTTP response processing including status lines, headers,
 * body chunks, and trailer headers. It supports both buffered and streaming response
 * handling through the AsyncHandler and StreamedAsyncHandler interfaces.
 * </p>
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // The HttpHandler is automatically installed in the pipeline
 * AsyncHttpClient client = new DefaultAsyncHttpClient();
 * client.prepareGet("http://example.com")
 *     .execute(new AsyncCompletionHandler<Response>() {
 *         @Override
 *         public Response onCompleted(Response response) {
 *             // HttpHandler processed the complete response
 *             return response;
 *         }
 *     });
 * }</pre>
 */
@Sharable
public final class HttpHandler extends AsyncHttpClientHandler {

  /**
   * Constructs a new HttpHandler.
   *
   * @param config the async HTTP client configuration
   * @param channelManager the channel manager for managing channel lifecycle
   * @param requestSender the request sender for sending HTTP requests
   */
  public HttpHandler(AsyncHttpClientConfig config, ChannelManager channelManager, NettyRequestSender requestSender) {
    super(config, channelManager, requestSender);
  }

  private boolean abortAfterHandlingStatus(AsyncHandler<?> handler,
                                           NettyResponseStatus status) throws Exception {
    return handler.onStatusReceived(status) == State.ABORT;
  }

  private boolean abortAfterHandlingHeaders(AsyncHandler<?> handler,
                                            HttpHeaders responseHeaders) throws Exception {
    return !responseHeaders.isEmpty() && handler.onHeadersReceived(responseHeaders) == State.ABORT;
  }

  private boolean abortAfterHandlingReactiveStreams(Channel channel,
                                                    NettyResponseFuture<?> future,
                                                    AsyncHandler<?> handler) {
    if (handler instanceof StreamedAsyncHandler) {
      StreamedAsyncHandler<?> streamedAsyncHandler = (StreamedAsyncHandler<?>) handler;
      StreamedResponsePublisher publisher = new StreamedResponsePublisher(channel.eventLoop(), channelManager, future, channel);
      // FIXME do we really need to pass the event loop?
      // FIXME move this to ChannelManager
      channel.pipeline().addLast(channel.eventLoop(), "streamedAsyncHandler", publisher);
      Channels.setAttribute(channel, publisher);
      return streamedAsyncHandler.onStream(publisher) == State.ABORT;
    }
    return false;
  }

  private void handleHttpResponse(final HttpResponse response, final Channel channel, final NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {

    HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
    logger.debug("\n\nRequest {}\n\nResponse {}\n", httpRequest, response);

    future.setKeepAlive(config.getKeepAliveStrategy().keepAlive((InetSocketAddress) channel.remoteAddress(), future.getTargetRequest(), httpRequest, response));

    NettyResponseStatus status = new NettyResponseStatus(future.getUri(), response, channel);
    HttpHeaders responseHeaders = response.headers();

    if (!interceptors.exitAfterIntercept(channel, future, handler, response, status, responseHeaders)) {
      boolean abort = abortAfterHandlingStatus(handler, status) || //
              abortAfterHandlingHeaders(handler, responseHeaders) || //
              abortAfterHandlingReactiveStreams(channel, future, handler);

      if (abort) {
        finishUpdate(future, channel, true);
      }
    }
  }

  private void handleChunk(HttpContent chunk,
                           final Channel channel,
                           final NettyResponseFuture<?> future,
                           AsyncHandler<?> handler) throws Exception {

    boolean abort = false;
    boolean last = chunk instanceof LastHttpContent;

    // Netty 4: the last chunk is not empty
    if (last) {
      LastHttpContent lastChunk = (LastHttpContent) chunk;
      HttpHeaders trailingHeaders = lastChunk.trailingHeaders();
      if (!trailingHeaders.isEmpty()) {
        abort = handler.onTrailingHeadersReceived(trailingHeaders) == State.ABORT;
      }
    }

    ByteBuf buf = chunk.content();
    if (!abort && !(handler instanceof StreamedAsyncHandler) && (buf.isReadable() || last)) {
      HttpResponseBodyPart bodyPart = config.getResponseBodyPartFactory().newResponseBodyPart(buf, last);
      abort = handler.onBodyPartReceived(bodyPart) == State.ABORT;
    }

    if (abort || last) {
      boolean close = abort || !future.isKeepAlive();
      finishUpdate(future, channel, close);
    }
  }

  /**
   * Processes HTTP protocol messages including responses and body chunks.
   * <p>
   * This method handles:
   * <ul>
   *   <li>HttpResponse - status line and headers</li>
   *   <li>HttpContent - response body chunks and trailer headers</li>
   *   <li>DecoderResult errors from the HTTP codec</li>
   * </ul>
   * It also applies IO exception filters and retry logic when appropriate.
   * </p>
   *
   * @param channel the channel the message was read from
   * @param future the response future associated with the request
   * @param e the message to handle (HttpResponse or HttpContent)
   * @throws Exception if an error occurs during message handling
   */
  @Override
  public void handleRead(final Channel channel, final NettyResponseFuture<?> future, final Object e) throws Exception {

    // future is already done because of an exception or a timeout
    if (future.isDone()) {
      // FIXME isn't the channel already properly closed?
      channelManager.closeChannel(channel);
      return;
    }

    AsyncHandler<?> handler = future.getAsyncHandler();
    try {
      if (e instanceof DecoderResultProvider) {
        DecoderResultProvider object = (DecoderResultProvider) e;
        Throwable t = object.decoderResult().cause();
        if (t != null) {
          readFailed(channel, future, t);
          return;
        }
      }

      if (e instanceof HttpResponse) {
        handleHttpResponse((HttpResponse) e, channel, future, handler);

      } else if (e instanceof HttpContent) {
        handleChunk((HttpContent) e, channel, future, handler);
      }
    } catch (Exception t) {
      // e.g. an IOException when trying to open a connection and send the
      // next request
      if (hasIOExceptionFilters//
              && t instanceof IOException//
              && requestSender.applyIoExceptionFiltersAndReplayRequest(future, (IOException) t, channel)) {
        return;
      }

      readFailed(channel, future, t);
      throw t;
    }
  }

  private void readFailed(Channel channel, NettyResponseFuture<?> future, Throwable t) {
    try {
      requestSender.abort(channel, future, t);
    } catch (Exception abortException) {
      logger.debug("Abort failed", abortException);
    } finally {
      finishUpdate(future, channel, true);
    }
  }

  /**
   * Handles exceptions for HTTP protocol operations.
   * <p>
   * This method provides no additional exception handling beyond the base class,
   * as HTTP-specific error handling is performed in {@link #handleRead}.
   * </p>
   *
   * @param future the response future associated with the request
   * @param error the exception that occurred
   */
  @Override
  public void handleException(NettyResponseFuture<?> future, Throwable error) {
  }

  /**
   * Handles channel inactivation for HTTP protocol operations.
   * <p>
   * This method provides no additional handling beyond the base class,
   * as HTTP channel lifecycle management is performed in the base handler.
   * </p>
   *
   * @param future the response future associated with the request
   */
  @Override
  public void handleChannelInactive(NettyResponseFuture<?> future) {
  }
}
