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

import static io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;
import static org.asynchttpclient.ws.WebSocketUtils.getAcceptKey;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.io.IOException;
import java.util.Locale;

import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.NettyResponseStatus;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.ws.NettyWebSocket;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;

@Sharable
public final class WebSocketHandler extends AsyncHttpClientHandler {

    public WebSocketHandler(AsyncHttpClientConfig config,//
            ChannelManager channelManager,//
            NettyRequestSender requestSender) {
        super(config, channelManager, requestSender);
    }

    private class UpgradeCallback extends OnLastHttpContentCallback {

        private final Channel channel;
        private final HttpResponse response;
        private final WebSocketUpgradeHandler handler;
        private final HttpResponseStatus status;
        private final HttpResponseHeaders responseHeaders;

        public UpgradeCallback(NettyResponseFuture<?> future, Channel channel, HttpResponse response, WebSocketUpgradeHandler handler, HttpResponseStatus status,
                HttpResponseHeaders responseHeaders) {
            super(future);
            this.channel = channel;
            this.response = response;
            this.handler = handler;
            this.status = status;
            this.responseHeaders = responseHeaders;
        }

        // We don't need to synchronize as replacing the "ws-decoder" will
        // process using the same thread.
        private void invokeOnSucces(Channel channel, WebSocketUpgradeHandler h) {
            if (!h.touchSuccess()) {
                try {
                    h.onSuccess(new NettyWebSocket(channel, responseHeaders.getHeaders()));
                } catch (Exception ex) {
                    logger.warn("onSuccess unexpected exception", ex);
                }
            }
        }

        @Override
        public void call() throws Exception {

            boolean validStatus = response.getStatus().equals(SWITCHING_PROTOCOLS);
            boolean validUpgrade = response.headers().get(HttpHeaders.Names.UPGRADE) != null;
            String connection = response.headers().get(HttpHeaders.Names.CONNECTION);
            if (connection == null)
                connection = response.headers().get(HttpHeaders.Names.CONNECTION.toLowerCase(Locale.ENGLISH));
            boolean validConnection = HttpHeaders.Values.UPGRADE.equalsIgnoreCase(connection);
            boolean statusReceived = handler.onStatusReceived(status) == State.UPGRADE;

            if (!statusReceived) {
                try {
                    handler.onCompleted();
                } finally {
                    future.done();
                }
                return;
            }

            final boolean headerOK = handler.onHeadersReceived(responseHeaders) == State.CONTINUE;
            if (!headerOK || !validStatus || !validUpgrade || !validConnection) {
                requestSender.abort(channel, future, new IOException("Invalid handshake response"));
                return;
            }

            String accept = response.headers().get(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT);
            String key = getAcceptKey(future.getNettyRequest().getHttpRequest().headers().get(HttpHeaders.Names.SEC_WEBSOCKET_KEY));
            if (accept == null || !accept.equals(key)) {
                requestSender.abort(channel, future, new IOException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept, key)));
            }

            // set back the future so the protocol gets notified of frames
            // removing the HttpClientCodec from the pipeline might trigger a read with a WebSocket message
            // if it comes in the same frame as the HTTP Upgrade response
            Channels.setAttribute(channel, future);

            channelManager.upgradePipelineForWebSockets(channel.pipeline());

            invokeOnSucces(channel, handler);
            future.done();
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

            WebSocketUpgradeHandler handler = WebSocketUpgradeHandler.class.cast(future.getAsyncHandler());
            HttpResponseStatus status = new NettyResponseStatus(future.getUri(), config, response, channel);
            HttpResponseHeaders responseHeaders = new HttpResponseHeaders(response.headers());

            if (!interceptors.exitAfterIntercept(channel, future, handler, response, status, responseHeaders)) {
                Channels.setAttribute(channel, new UpgradeCallback(future, channel, response, handler, status, responseHeaders));
            }

        } else if (e instanceof WebSocketFrame) {
            final WebSocketFrame frame = (WebSocketFrame) e;
            WebSocketUpgradeHandler handler = (WebSocketUpgradeHandler) future.getAsyncHandler();
            NettyWebSocket webSocket = (NettyWebSocket) handler.onCompleted();

            if (webSocket != null) {
                handleFrame(channel, frame, handler, webSocket);
            } else {
                logger.debug("Frame received but WebSocket is not available yet, buffering frame");
                frame.retain();
                Runnable bufferedFrame = new Runnable() {
                    public void run() {
                        try {
                            // WebSocket is now not null
                            NettyWebSocket webSocket = (NettyWebSocket) handler.onCompleted();
                            handleFrame(channel, frame, handler, webSocket);
                        } catch (Exception e) {
                            logger.debug("Failure while handling buffered frame", e);
                            handler.onFailure(e);
                        } finally {
                            frame.release();
                        }
                    }
                };
                handler.bufferFrame(bufferedFrame);
            }
        } else {
            logger.error("Invalid message {}", e);
        }
    }

    private void handleFrame(Channel channel, WebSocketFrame frame, WebSocketUpgradeHandler handler, NettyWebSocket webSocket) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            webSocket.onTextFrame((TextWebSocketFrame) frame);

        } else if (frame instanceof BinaryWebSocketFrame) {
            webSocket.onBinaryFrame((BinaryWebSocketFrame) frame);

        } else if (frame instanceof CloseWebSocketFrame) {
            Channels.setDiscard(channel);
            CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
            webSocket.onClose(closeFrame.statusCode(), closeFrame.reasonText());
            Channels.silentlyCloseChannel(channel);

        } else if (frame instanceof PingWebSocketFrame) {
            webSocket.onPing((PingWebSocketFrame) frame);

        } else if (frame instanceof PongWebSocketFrame) {
            webSocket.onPong((PongWebSocketFrame) frame);
        }
    }

    @Override
    public void handleException(NettyResponseFuture<?> future, Throwable e) {
        logger.warn("onError", e);

        try {
            WebSocketUpgradeHandler h = (WebSocketUpgradeHandler) future.getAsyncHandler();

            NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());
            if (webSocket != null) {
                webSocket.onError(e.getCause());
                webSocket.close();
            }
        } catch (Throwable t) {
            logger.error("onError", t);
        }
    }

    @Override
    public void handleChannelInactive(NettyResponseFuture<?> future) {
        logger.trace("onClose");

        try {
            WebSocketUpgradeHandler h = (WebSocketUpgradeHandler) future.getAsyncHandler();
            NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());

            logger.trace("Connection was closed abnormally (that is, with no close frame being received).");
            if (webSocket != null)
                webSocket.close(1006, "Connection was closed abnormally (that is, with no close frame being received).");
        } catch (Throwable t) {
            logger.error("onError", t);
        }
    }
}
