package org.asynchttpclient.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CodecException;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionDecoder;

import java.util.List;

/**
 * Created by elena on 24.07.17.
 */
public abstract class MyDeflateDecoder extends WebSocketExtensionDecoder {
    static final byte[] FRAME_TAIL = new byte[]{0, 0, -1, -1};
    private final boolean noContext;
    private EmbeddedChannel decoder;

    public MyDeflateDecoder(boolean noContext) {
        this.noContext = noContext;
    }

    protected abstract boolean appendFrameTail(WebSocketFrame var1);

    protected abstract int newRsv(WebSocketFrame var1);

    protected void decode(ChannelHandlerContext ctx, WebSocketFrame msg, List<Object> out) throws Exception {
        if(this.decoder == null) {
            if(!(msg instanceof TextWebSocketFrame) && !(msg instanceof BinaryWebSocketFrame)) {
                throw new CodecException("unexpected initial frame type: " + msg.getClass().getName());
            }

            this.decoder = new EmbeddedChannel(new ChannelHandler[]{ZlibCodecFactory.newZlibDecoder(ZlibWrapper.NONE)});
        }

        boolean readable = msg.content().isReadable();
        this.decoder.writeInbound(new Object[]{msg.content().retain()});
        if(this.appendFrameTail(msg)) {
            this.decoder.writeInbound(new Object[]{Unpooled.wrappedBuffer(FRAME_TAIL)});
        }

        CompositeByteBuf compositeUncompressedContent = ctx.alloc().compositeBuffer();

        while(true) {
            ByteBuf partUncompressedContent = (ByteBuf)this.decoder.readInbound();
            if(partUncompressedContent == null) {
                if(readable && compositeUncompressedContent.numComponents() <= 0) {
                    compositeUncompressedContent.release();
                    throw new CodecException("cannot read uncompressed buffer");
                }

                if(msg.isFinalFragment() && this.noContext) {
                    this.cleanup();
                }

                Object outMsg;
                if(msg instanceof TextWebSocketFrame) {
                    outMsg = new TextWebSocketFrame(msg.isFinalFragment(), this.newRsv(msg), compositeUncompressedContent);
                } else if(msg instanceof BinaryWebSocketFrame) {
                    outMsg = new BinaryWebSocketFrame(msg.isFinalFragment(), this.newRsv(msg), compositeUncompressedContent);
                } else {
                    if(!(msg instanceof ContinuationWebSocketFrame)) {
                        throw new CodecException("unexpected frame type: " + msg.getClass().getName());
                    }

                    outMsg = new ContinuationWebSocketFrame(msg.isFinalFragment(), this.newRsv(msg), compositeUncompressedContent);
                }

                out.add(outMsg);
                return;
            }

            if(!partUncompressedContent.isReadable()) {
                partUncompressedContent.release();
            } else {
                compositeUncompressedContent.addComponent(true, partUncompressedContent);
            }
        }
    }

    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        this.cleanup();
        super.handlerRemoved(ctx);
    }

    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.cleanup();
        super.channelInactive(ctx);
    }

    private void cleanup() {
        if(this.decoder != null) {
            if(this.decoder.finish()) {
                while(true) {
                    ByteBuf buf = (ByteBuf)this.decoder.readOutbound();
                    if(buf == null) {
                        break;
                    }

                    buf.release();
                }
            }

            this.decoder = null;
        }

    }
}
