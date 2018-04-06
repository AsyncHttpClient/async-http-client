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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.NettyResponseStatus;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.ws.NettyWebSocket;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;

import java.io.IOException;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;
import static org.asynchttpclient.ws.WebSocketUtils.getAcceptKey;

@Sharable
public final class WebSocketHandler extends AsyncHttpClientHandler {

  public WebSocketHandler(AsyncHttpClientConfig config,
                          ChannelManager channelManager,
                          NettyRequestSender requestSender) {
    super(config, channelManager, requestSender);
  }

  private static WebSocketUpgradeHandler getWebSocketUpgradeHandler(NettyResponseFuture<?> future) {
    return (WebSocketUpgradeHandler) future.getAsyncHandler();
  }

  private static NettyWebSocket getNettyWebSocket(NettyResponseFuture<?> future) throws Exception {
    return getWebSocketUpgradeHandler(future).onCompleted();
  }

  private void upgrade(Channel channel, NettyResponseFuture<?> future, WebSocketUpgradeHandler handler, HttpResponse response, HttpHeaders responseHeaders)
          throws Exception {
    boolean validStatus = response.status().equals(SWITCHING_PROTOCOLS);
    boolean validUpgrade = response.headers().get(UPGRADE) != null;
    String connection = response.headers().get(CONNECTION);
    boolean validConnection = HttpHeaderValues.UPGRADE.contentEqualsIgnoreCase(connection);
    final boolean headerOK = handler.onHeadersReceived(responseHeaders) == State.CONTINUE;
    if (!headerOK || !validStatus || !validUpgrade || !validConnection) {
      requestSender.abort(channel, future, new IOException("Invalid handshake response"));
      return;
    }

    String accept = response.headers().get(SEC_WEBSOCKET_ACCEPT);
    String key = getAcceptKey(future.getNettyRequest().getHttpRequest().headers().get(SEC_WEBSOCKET_KEY));
    if (accept == null || !accept.equals(key)) {
      requestSender.abort(channel, future, new IOException("Invalid challenge. Actual: " + accept + ". Expected: " + key));
    }

    // set back the future so the protocol gets notified of frames
    // removing the HttpClientCodec from the pipeline might trigger a read with a WebSocket message
    // if it comes in the same frame as the HTTP Upgrade response
    Channels.setAttribute(channel, future);

    handler.setWebSocket(new NettyWebSocket(channel, responseHeaders));
    channelManager.upgradePipelineForWebSockets(channel.pipeline());

    // We don't need to synchronize as replacing the "ws-decoder" will
    // process using the same thread.
    try {
      handler.onOpen();
    } catch (Exception ex) {
      logger.warn("onSuccess unexpected exception", ex);
    }
    future.done();
  }

  private void abort(Channel channel, NettyResponseFuture<?> future, WebSocketUpgradeHandler handler, HttpResponseStatus status) {
    try {
      handler.onThrowable(new IOException("Invalid Status code=" + status.getStatusCode() + " text=" + status.getStatusText()));
    } finally {
      finishUpdate(future, channel, true);
    }
  }

  @Override
  public void handleRead(Channel channel, NettyResponseFuture<?> future, Object e) throws Exception {

    if (e instanceof HttpResponse) {
      HttpResponse response = (HttpResponse) e;
      if (logger.isDebugEnabled()) {
        HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
        logger.debug("\n\nRequest {}\n\nResponse {}\n", httpRequest, response);
      }

      WebSocketUpgradeHandler handler = getWebSocketUpgradeHandler(future);
      HttpResponseStatus status = new NettyResponseStatus(future.getUri(), response, channel);
      HttpHeaders responseHeaders = response.headers();

      if (!interceptors.exitAfterIntercept(channel, future, handler, response, status, responseHeaders)) {
        switch (handler.onStatusReceived(status)) {
          case CONTINUE:
            upgrade(channel, future, handler, response, responseHeaders);
            break;
          default:
            abort(channel, future, handler, status);
        }
      }

    } else if (e instanceof WebSocketFrame) {
      WebSocketFrame frame = (WebSocketFrame) e;
      NettyWebSocket webSocket = getNettyWebSocket(future);
      // retain because we might buffer the frame
      if (webSocket.isReady()) {
        webSocket.handleFrame(frame);
      } else {
        // WebSocket hasn't been opened yet, but upgrading the pipeline triggered a read and a frame was sent along the HTTP upgrade response
        // as we want to keep sequential order (but can't notify user of open before upgrading so he doesn't to try send immediately), we have to buffer
        webSocket.bufferFrame(frame);
      }

    } else if (!(e instanceof LastHttpContent)) {
      // ignore, end of handshake response
      logger.error("Invalid message {}", e);
    }
  }

  @Override
  public void handleException(NettyResponseFuture<?> future, Throwable e) {
    logger.warn("onError", e);

    try {
      NettyWebSocket webSocket = getNettyWebSocket(future);
      if (webSocket != null) {
        webSocket.onError(e);
        webSocket.sendCloseFrame();
      }
    } catch (Throwable t) {
      logger.error("onError", t);
    }
  }

  @Override
  public void handleChannelInactive(NettyResponseFuture<?> future) {
    logger.trace("Connection was closed abnormally (that is, with no close frame being received).");

    try {
      NettyWebSocket webSocket = getNettyWebSocket(future);
      if (webSocket != null) {
        webSocket.onClose(1006, "Connection was closed abnormally (that is, with no close frame being received).");
      }
    } catch (Throwable t) {
      logger.error("onError", t);
    }
  }
}
