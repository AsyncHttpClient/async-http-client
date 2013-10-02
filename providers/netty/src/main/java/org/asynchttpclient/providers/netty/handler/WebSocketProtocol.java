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
package org.asynchttpclient.providers.netty.handler;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.io.IOException;

import org.asynchttpclient.AsyncHandler.STATE;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Request;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.providers.netty.Constants;
import org.asynchttpclient.providers.netty.DiscardEvent;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.providers.netty.response.ResponseBodyPart;
import org.asynchttpclient.providers.netty.response.ResponseHeaders;
import org.asynchttpclient.providers.netty.response.ResponseStatus;
import org.asynchttpclient.providers.netty.ws.NettyWebSocket;
import org.asynchttpclient.providers.netty.ws.WebSocketUtil;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;

final class WebSocketProtocol extends Protocol {

    private static final byte OPCODE_TEXT = 0x1;
    private static final byte OPCODE_BINARY = 0x2;
    private static final byte OPCODE_UNKNOWN = -1;
    protected byte pendingOpcode = OPCODE_UNKNOWN;

    public WebSocketProtocol(Channels channels, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, NettyRequestSender requestSender) {
        super(channels, config, nettyConfig, requestSender);
    }

    // We don't need to synchronize as replacing the "ws-decoder" will
    // process using the same thread.
    private void invokeOnSucces(ChannelHandlerContext ctx, WebSocketUpgradeHandler h) {
        if (!h.touchSuccess()) {
            try {
                h.onSuccess(new NettyWebSocket(ctx.channel()));
            } catch (Exception ex) {
                NettyChannelHandler.LOGGER.warn("onSuccess unexpected exception", ex);
            }
        }
    }

    @Override
    public void handle(ChannelHandlerContext ctx, NettyResponseFuture future, Object e) throws Exception {
        WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(future.getAsyncHandler());
        Request request = future.getRequest();

        if (e instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) e;

            HttpResponseStatus s = new ResponseStatus(future.getURI(), response, config);
            HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response.headers());

            // FIXME there's a method for that IIRC
            FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(h).request(request).responseStatus(s).responseHeaders(responseHeaders).build();
            for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                try {
                    fc = asyncFilter.filter(fc);
                    if (fc == null) {
                        throw new NullPointerException("FilterContext is null");
                    }
                } catch (FilterException efe) {
                    channels.abort(future, efe);
                }
            }

            // The handler may have been wrapped.
            future.setAsyncHandler(fc.getAsyncHandler());

            // The request has changed
            if (fc.replayRequest()) {
                requestSender.replayRequest(future, fc, ctx);
                return;
            }

            future.setHttpResponse(response);
            if (redirect(request, future, response, ctx))
                return;

            boolean validStatus = response.getStatus().equals(SWITCHING_PROTOCOLS);
            boolean validUpgrade = response.headers().get(HttpHeaders.Names.UPGRADE) != null;
            String c = response.headers().get(HttpHeaders.Names.CONNECTION);
            if (c == null) {
                c = response.headers().get(HttpHeaders.Names.CONNECTION.toLowerCase());
            }

            boolean validConnection = c == null ? false : c.equalsIgnoreCase(HttpHeaders.Values.UPGRADE);

            s = new ResponseStatus(future.getURI(), response, config);
            final boolean statusReceived = h.onStatusReceived(s) == STATE.UPGRADE;

            final boolean headerOK = h.onHeadersReceived(responseHeaders) == STATE.CONTINUE;
            if (!headerOK || !validStatus || !validUpgrade || !validConnection || !statusReceived) {
                channels.abort(future, new IOException("Invalid handshake response"));
                return;
            }

            String accept = response.headers().get(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT);
            String key = WebSocketUtil.getAcceptKey(future.getNettyRequest().headers().get(HttpHeaders.Names.SEC_WEBSOCKET_KEY));
            if (accept == null || !accept.equals(key)) {
                throw new IOException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept, key));
            }

            Channels.upgradePipelineForWebSockets(ctx);

            invokeOnSucces(ctx, h);
            future.done();

        } else if (e instanceof WebSocketFrame) {

            final WebSocketFrame frame = (WebSocketFrame) e;
            NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());
            invokeOnSucces(ctx, h);

            if (webSocket != null) {
                if (frame instanceof CloseWebSocketFrame) {
                    Channels.setDefaultAttribute(ctx, DiscardEvent.INSTANCE);
                    CloseWebSocketFrame closeFrame = CloseWebSocketFrame.class.cast(frame);
                    webSocket.onClose(closeFrame.statusCode(), closeFrame.reasonText());
                } else {
                    if (frame instanceof TextWebSocketFrame) {
                        pendingOpcode = OPCODE_TEXT;
                    } else if (frame instanceof BinaryWebSocketFrame) {
                        pendingOpcode = OPCODE_BINARY;
                    }

                    ByteBuf buf = frame.content();
                    if (buf != null && buf.readableBytes() > 0) {
                        ResponseBodyPart rp = nettyConfig.getBodyPartFactory().newResponseBodyPart(buf, frame.isFinalFragment());
                        h.onBodyPartReceived(rp);

                        if (pendingOpcode == OPCODE_BINARY) {
                            webSocket.onBinaryFragment(rp.getBodyPartBytes(), frame.isFinalFragment());
                        } else {
                            webSocket.onTextFragment(buf.toString(Constants.UTF8), frame.isFinalFragment());
                        }
                    }
                }
            } else {
                NettyChannelHandler.LOGGER.debug("UpgradeHandler returned a null NettyWebSocket ");
            }
        } else if (e instanceof LastHttpContent) {
            // FIXME what to do with this kind of messages?
        } else {
            NettyChannelHandler.LOGGER.error("Invalid message {}", e);
        }
    }

    @Override
    public void onError(ChannelHandlerContext ctx, Throwable e) {
        try {
            Object attribute = Channels.getDefaultAttribute(ctx);
            NettyChannelHandler.LOGGER.warn("onError {}", e);
            if (!(attribute instanceof NettyResponseFuture)) {
                return;
            }

            NettyResponseFuture<?> nettyResponse = (NettyResponseFuture<?>) attribute;
            WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());

            NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());
            if (webSocket != null) {
                webSocket.onError(e.getCause());
                webSocket.close();
            }
        } catch (Throwable t) {
            NettyChannelHandler.LOGGER.error("onError", t);
        }
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
        NettyChannelHandler.LOGGER.trace("onClose {}");
        Object attribute = Channels.getDefaultAttribute(ctx);
        if (!(attribute instanceof NettyResponseFuture)) {
            return;
        }

        try {
            NettyResponseFuture<?> nettyResponse = NettyResponseFuture.class.cast(attribute);
            WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());
            NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());

            // FIXME How could this test not succeed, attachment is a
            // NettyResponseFuture????
            if (attribute != DiscardEvent.INSTANCE)
                webSocket.close(1006, "Connection was closed abnormally (that is, with no close frame being sent).");
        } catch (Throwable t) {
            NettyChannelHandler.LOGGER.error("onError", t);
        }
    }
}