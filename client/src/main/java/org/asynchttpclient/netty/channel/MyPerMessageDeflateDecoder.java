package org.asynchttpclient.netty.channel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.asynchttpclient.ws.WebSocket;
//import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateDecoder;

import java.util.List;

/**
 * Created by elena on 24.07.17.
 */
public class MyPerMessageDeflateDecoder extends MyDeflateDecoder {
    private boolean compressing;

    public MyPerMessageDeflateDecoder(boolean noContext) {
        super(noContext);
    }

    public boolean acceptInboundMessage(Object msg) throws Exception {
//        System.out.println("acceptInboundMessage class="+msg.getClass());
        if(msg instanceof TextWebSocketFrame || msg instanceof BinaryWebSocketFrame){
            WebSocketFrame webSocketFrame = (WebSocketFrame)msg;
//            System.out.println("acceptInboundMessage rsv="+webSocketFrame.rsv()+ " compressing="+this.compressing);
        }
        return (msg instanceof TextWebSocketFrame || msg instanceof BinaryWebSocketFrame)
                && (((WebSocketFrame)msg).rsv() & 4) > 0
                || msg instanceof ContinuationWebSocketFrame
                && this.compressing;
    }

    protected int newRsv(WebSocketFrame msg) {
        return (msg.rsv() & 4) > 0?msg.rsv() ^ 4:msg.rsv();
    }

    protected boolean appendFrameTail(WebSocketFrame msg) {
        return msg.isFinalFragment();
    }

    protected void decode(ChannelHandlerContext ctx, WebSocketFrame msg, List<Object> out) throws Exception {
        super.decode(ctx, msg, out);
        if(msg.isFinalFragment()) {
//            if(msg.rsv()==4){
//                this.compressing = true;
//            }else{
                this.compressing = false;
//            }
        } else if(msg instanceof TextWebSocketFrame || msg instanceof BinaryWebSocketFrame) {
            this.compressing = true;
        }

    }
}
