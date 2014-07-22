package com.ning.http.client.providers.netty.handler;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameDecoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket08FrameEncoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Request;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.client.providers.netty.response.ResponseBodyPart;
import com.ning.http.client.providers.netty.response.ResponseHeaders;
import com.ning.http.client.providers.netty.response.ResponseStatus;
import com.ning.http.client.providers.netty.ws.NettyWebSocket;
import com.ning.http.client.providers.netty.ws.WebSocketUtil;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;
import com.ning.http.util.StandardCharsets;

import java.io.IOException;

public class WebSocketProtocol extends Protocol {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketProtocol.class);
    
    private static final byte OPCODE_CONT = 0x0;
    private static final byte OPCODE_TEXT = 0x1;
    private static final byte OPCODE_BINARY = 0x2;
    private static final byte OPCODE_UNKNOWN = -1;
    
    public WebSocketProtocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyRequestSender nettyRequestSender) {
        super(channelManager, config, nettyRequestSender);
    }
    
    // We don't need to synchronize as replacing the "ws-decoder" will process using the same thread.
    private void invokeOnSucces(Channel channel, WebSocketUpgradeHandler h) {
        if (!h.touchSuccess()) {
            try {
                h.onSuccess(new NettyWebSocket(channel));
            } catch (Exception ex) {
                LOGGER.warn("onSuccess unexexpected exception", ex);
            }
        }
    }

    @Override
    public void handle(Channel channel, MessageEvent e, final NettyResponseFuture future) throws Exception {

        WebSocketUpgradeHandler wsUpgradeHandler = (WebSocketUpgradeHandler) future.getAsyncHandler();
        Request request = future.getRequest();

        if (e.getMessage() instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) e.getMessage();
            HttpHeaders nettyResponseHeaders = response.headers();

            HttpResponseStatus s = new ResponseStatus(future.getURI(), config, response);
            HttpResponseHeaders responseHeaders = new ResponseHeaders(response);
            FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(wsUpgradeHandler).request(request)
                    .responseStatus(s).responseHeaders(responseHeaders).build();
            for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                try {
                    fc = asyncFilter.filter(fc);
                    if (fc == null) {
                        throw new NullPointerException("FilterContext is null");
                    }
                } catch (FilterException efe) {
                    nettyRequestSender.abort(future, efe);
                }
            }

            // The handler may have been wrapped.
            future.setAsyncHandler(fc.getAsyncHandler());

            // The request has changed
            if (fc.replayRequest()) {
                replayRequest(future, fc, channel);
                return;
            }

            future.setHttpResponse(response);
            if (exitAfterHandlingRedirect(channel, future, request, response, response.getStatus().getCode()))
                return;

            final org.jboss.netty.handler.codec.http.HttpResponseStatus status = new org.jboss.netty.handler.codec.http.HttpResponseStatus(
                    101, "Web Socket Protocol Handshake");

            final boolean validStatus = response.getStatus().equals(status);
            final boolean validUpgrade = nettyResponseHeaders.contains(HttpHeaders.Names.UPGRADE);
            String c = nettyResponseHeaders.get(HttpHeaders.Names.CONNECTION);
            if (c == null) {
                c = nettyResponseHeaders.get("connection");
            }

            final boolean validConnection = c == null ? false : c.equalsIgnoreCase(HttpHeaders.Values.UPGRADE);

            s = new ResponseStatus(future.getURI(), config, response);
            final boolean statusReceived = wsUpgradeHandler.onStatusReceived(s) == STATE.UPGRADE;

            if (!statusReceived) {
                try {
                    wsUpgradeHandler.onCompleted();
                } finally {
                    future.done();
                }
                return;
            }

            final boolean headerOK = wsUpgradeHandler.onHeadersReceived(responseHeaders) == STATE.CONTINUE;
            if (!headerOK || !validStatus || !validUpgrade || !validConnection) {
                nettyRequestSender.abort(future, new IOException("Invalid handshake response"));
                return;
            }

            String accept = nettyResponseHeaders.get(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT);
            String key = WebSocketUtil.getAcceptKey(future.getNettyRequest().headers().get(HttpHeaders.Names.SEC_WEBSOCKET_KEY));
            if (accept == null || !accept.equals(key)) {
                nettyRequestSender.abort(future, new IOException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept, key)));
                return;
            }

            // FIXME
            channel.getPipeline().replace(ChannelManager.HTTP_HANDLER, "ws-encoder", new WebSocket08FrameEncoder(true));
            channel.getPipeline().addBefore(ChannelManager.WS_PROCESSOR, "ws-decoder", new WebSocket08FrameDecoder(false, false));

            invokeOnSucces(channel, wsUpgradeHandler);
            future.done();
        } else if (e.getMessage() instanceof WebSocketFrame) {

            invokeOnSucces(channel, wsUpgradeHandler);

            final WebSocketFrame frame = (WebSocketFrame) e.getMessage();

            byte pendingOpcode = OPCODE_UNKNOWN;
            if (frame instanceof TextWebSocketFrame) {
                pendingOpcode = OPCODE_TEXT;
            } else if (frame instanceof BinaryWebSocketFrame) {
                pendingOpcode = OPCODE_BINARY;
            }

            if (frame.getBinaryData() != null) {
                
                HttpChunk webSocketChunk = new HttpChunk() {
                    private ChannelBuffer content = ChannelBuffers.wrappedBuffer(frame.getBinaryData());

                    @Override
                    public boolean isLast() {
                        return false;
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
                
                ResponseBodyPart rp = new ResponseBodyPart(null, webSocketChunk, true);
                wsUpgradeHandler.onBodyPartReceived(rp);

                NettyWebSocket webSocket = NettyWebSocket.class.cast(wsUpgradeHandler.onCompleted());

                if (webSocket != null) {
                    if (pendingOpcode == OPCODE_BINARY) {
                        webSocket.onBinaryFragment(rp.getBodyPartBytes(), frame.isFinalFragment());
                    } else if (pendingOpcode == OPCODE_TEXT) {
                        webSocket.onTextFragment(frame.getBinaryData().toString(StandardCharsets.UTF_8), frame.isFinalFragment());
                    }

                    if (frame instanceof CloseWebSocketFrame) {
                        try {
                            Channels.setDiscard(channel);
                            webSocket.onClose(CloseWebSocketFrame.class.cast(frame).getStatusCode(),
                                    CloseWebSocketFrame.class.cast(frame).getReasonText());
                        } finally {
                            wsUpgradeHandler.resetSuccess();
                        }
                    }
                } else {
                    LOGGER.debug("UpgradeHandler returned a null NettyWebSocket ");
                }
            }
        } else {
            LOGGER.error("Invalid message {}", e.getMessage());
        }
    }

    @Override
    public void onError(Channel channel, ExceptionEvent e) {
        try {
            Object attachment = Channels.getAttachment(channel);
            LOGGER.warn("onError {}", e);
            if (!(attachment instanceof NettyResponseFuture)) {
                return;
            }

            NettyResponseFuture<?> nettyResponse = (NettyResponseFuture<?>) attachment;
            WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());

            NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());
            if (webSocket != null) {
                webSocket.onError(e.getCause());
                webSocket.close();
            }
        } catch (Throwable t) {
            LOGGER.error("onError", t);
        }
    }

    @Override
    public void onClose(Channel channel, ChannelStateEvent e) {
        LOGGER.trace("onClose {}", e);

        Object attachment = Channels.getAttachment(channel);
        if (attachment instanceof NettyResponseFuture) {
            try {
                NettyResponseFuture<?> nettyResponse = (NettyResponseFuture<?>) attachment;
                WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());
                h.resetSuccess();

            } catch (Throwable t) {
                LOGGER.error("onError", t);
            }
        }
    }
}
