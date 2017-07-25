package org.asynchttpclient.netty.channel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
//import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateEncoder;

import java.util.List;

/**
 * Created by elena on 24.07.17.
 */
public class MyPerMessageDeflateEncoder extends MyDeflateEncoder {
    private boolean compressing;

    public MyPerMessageDeflateEncoder(int compressionLevel, int windowSize, boolean noContext) {
        super(compressionLevel, windowSize, noContext);
    }

    public boolean acceptOutboundMessage(Object msg) throws Exception {
        return (msg instanceof TextWebSocketFrame || msg instanceof BinaryWebSocketFrame) && (((WebSocketFrame)msg).rsv() & 4) == 0 || msg instanceof ContinuationWebSocketFrame && this.compressing;
    }

    protected int rsv(WebSocketFrame msg) {
        return !(msg instanceof TextWebSocketFrame) && !(msg instanceof BinaryWebSocketFrame)?msg.rsv():msg.rsv() | 4;
    }

    protected boolean removeFrameTail(WebSocketFrame msg) {
        return msg.isFinalFragment();
    }

    protected void encode(ChannelHandlerContext ctx, WebSocketFrame msg, List<Object> out) throws Exception {
        super.encode(ctx, msg, out);
        if(msg.isFinalFragment()) {
            this.compressing = false;
        } else if(msg instanceof TextWebSocketFrame || msg instanceof BinaryWebSocketFrame) {
            this.compressing = true;
        }

    }
}
