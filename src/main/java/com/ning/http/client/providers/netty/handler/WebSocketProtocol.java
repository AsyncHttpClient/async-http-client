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

import static com.ning.http.client.providers.netty.ws.WebSocketUtils.getAcceptKey;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;

import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Request;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.client.providers.netty.response.NettyResponseBodyPart;
import com.ning.http.client.providers.netty.response.NettyResponseHeaders;
import com.ning.http.client.providers.netty.response.NettyResponseStatus;
import com.ning.http.client.providers.netty.ws.NettyWebSocket;
import com.ning.http.client.ws.WebSocketUpgradeHandler;

import java.io.IOException;
import java.util.Locale;

public final class WebSocketProtocol extends Protocol {

    public WebSocketProtocol(ChannelManager channelManager,//
            AsyncHttpClientConfig config,//
            NettyAsyncHttpProviderConfig nettyConfig,//
            NettyRequestSender requestSender) {
        super(channelManager, config, nettyConfig, requestSender);
    }

    // We don't need to synchronize as replacing the "ws-decoder" will
    // process using the same thread.
    private void invokeOnSucces(Channel channel, WebSocketUpgradeHandler h) {
        if (!h.touchSuccess()) {
            try {
                h.onSuccess(nettyConfig.getNettyWebSocketFactory().newNettyWebSocket(channel, nettyConfig));
            } catch (Exception ex) {
                logger.warn("onSuccess unexpected exception", ex);
            }
        }
    }

    @Override
    public void handle(Channel channel, NettyResponseFuture<?> future, Object e) throws Exception {
        WebSocketUpgradeHandler handler = WebSocketUpgradeHandler.class.cast(future.getAsyncHandler());
        Request request = future.getRequest();

        if (e instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) e;
            HttpResponseStatus status = new NettyResponseStatus(future.getUri(), config, response);
            HttpResponseHeaders responseHeaders = new NettyResponseHeaders(response.headers());

            if (exitAfterProcessingFilters(channel, future, handler, status, responseHeaders)) {
                return;
            }

            future.setHttpHeaders(response.headers());
            if (exitAfterHandlingRedirect(channel, future, response, request, response.getStatus().getCode()))
                return;

            boolean validStatus = response.getStatus().equals(SWITCHING_PROTOCOLS);
            boolean validUpgrade = response.headers().get(HttpHeaders.Names.UPGRADE) != null;
            String connection = response.headers().get(HttpHeaders.Names.CONNECTION);
            if (connection == null)
                connection = response.headers().get(HttpHeaders.Names.CONNECTION.toLowerCase(Locale.ENGLISH));
            boolean validConnection = HttpHeaders.Values.UPGRADE.equalsIgnoreCase(connection);

            status = new NettyResponseStatus(future.getUri(), config, response);
            final boolean statusReceived = handler.onStatusReceived(status) == STATE.UPGRADE;

            if (!statusReceived) {
                try {
                    handler.onCompleted();
                } finally {
                    future.done();
                }
                return;
            }

            final boolean headerOK = handler.onHeadersReceived(responseHeaders) == STATE.CONTINUE;
            if (!headerOK || !validStatus || !validUpgrade || !validConnection) {
                requestSender.abort(channel, future, new IOException("Invalid handshake response"));
                return;
            }

            String accept = response.headers().get(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT);
            String key = getAcceptKey(future.getNettyRequest().getHttpRequest().headers().get(HttpHeaders.Names.SEC_WEBSOCKET_KEY));
            if (accept == null || !accept.equals(key)) {
                requestSender.abort(channel, future, new IOException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept, key)));
            }

            channelManager.upgradePipelineForWebSockets(channel.getPipeline());

            invokeOnSucces(channel, handler);
            future.done();

        } else if (e instanceof WebSocketFrame) {

            final WebSocketFrame frame = (WebSocketFrame) e;
            NettyWebSocket webSocket = NettyWebSocket.class.cast(handler.onCompleted());
            invokeOnSucces(channel, handler);

            if (webSocket != null) {
                if (frame instanceof CloseWebSocketFrame) {
                    Channels.setDiscard(channel);
                    CloseWebSocketFrame closeFrame = CloseWebSocketFrame.class.cast(frame);
                    webSocket.onClose(closeFrame.getStatusCode(), closeFrame.getReasonText());
                    
                } else if (frame.getBinaryData() != null) {
                    HttpChunk webSocketChunk = new HttpChunk() {
                        private ChannelBuffer content = frame.getBinaryData();

                        @Override
                        public boolean isLast() {
                            return frame.isFinalFragment();
                        }

                        @Override
                        public ChannelBuffer getContent() {
                            return content;
                        }

                        @Override
                        public void setContent(ChannelBuffer content) {
                            throw new UnsupportedOperationException();
                        }
                    };

                    NettyResponseBodyPart part = new NettyResponseBodyPart(null, webSocketChunk, frame.isFinalFragment());
                    handler.onBodyPartReceived(part);

                    if (frame instanceof BinaryWebSocketFrame) {
                        webSocket.onBinaryFragment(part);
                    } else if (frame instanceof TextWebSocketFrame) {
                        webSocket.onTextFragment(part);
                    } else if (frame instanceof PingWebSocketFrame) {
                        webSocket.onPing(part);
                    } else if (frame instanceof PongWebSocketFrame) {
                        webSocket.onPong(part);
                    }
                }
            } else {
                logger.debug("UpgradeHandler returned a null NettyWebSocket ");
            }
        } else {
            logger.error("Invalid message {}", e);
        }
    }

    @Override
    public void onError(NettyResponseFuture<?> future, Throwable e) {
        logger.warn("onError {}", e);

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
    public void onClose(NettyResponseFuture<?> future) {
        logger.trace("onClose {}");

        try {
            WebSocketUpgradeHandler h = (WebSocketUpgradeHandler) future.getAsyncHandler();
            NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());

            logger.trace("Connection was closed abnormally (that is, with no close frame being sent).");
            if (webSocket != null)
                webSocket.close(1006, "Connection was closed abnormally (that is, with no close frame being sent).");
        } catch (Throwable t) {
            logger.error("onError", t);
        }
    }
}
