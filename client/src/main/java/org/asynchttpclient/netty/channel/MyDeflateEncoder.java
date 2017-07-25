package org.asynchttpclient.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
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
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionEncoder;
//import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateDecoder;

import java.util.List;

/**
 * Created by elena on 24.07.17.
 */
public abstract class MyDeflateEncoder extends WebSocketExtensionEncoder {
    private final int compressionLevel;
    private final int windowSize;
    private final boolean noContext;
    private EmbeddedChannel encoder;

    public MyDeflateEncoder(int compressionLevel, int windowSize, boolean noContext) {
        this.compressionLevel = compressionLevel;
        this.windowSize = windowSize;
        this.noContext = noContext;
    }

    protected abstract int rsv(WebSocketFrame var1);

    protected abstract boolean removeFrameTail(WebSocketFrame var1);

    protected void encode(ChannelHandlerContext ctx, WebSocketFrame msg, List<Object> out) throws Exception {
        if(this.encoder == null) {
            this.encoder = new EmbeddedChannel(new ChannelHandler[]{ZlibCodecFactory.newZlibEncoder(ZlibWrapper.NONE, this.compressionLevel, this.windowSize, 8)});
        }

        this.encoder.writeOutbound(new Object[]{msg.content().retain()});
        CompositeByteBuf fullCompressedContent = ctx.alloc().compositeBuffer();

        while(true) {
            ByteBuf partCompressedContent = (ByteBuf)this.encoder.readOutbound();
            if(partCompressedContent == null) {
                if(fullCompressedContent.numComponents() <= 0) {
                    fullCompressedContent.release();
                    throw new CodecException("cannot read compressed buffer");
                }

                if(msg.isFinalFragment() && this.noContext) {
                    this.cleanup();
                }

                Object compressedContent;
                if(this.removeFrameTail(msg)) {
                    int realLength = fullCompressedContent.readableBytes() - MyPerMessageDeflateDecoder.FRAME_TAIL.length;
                    compressedContent = fullCompressedContent.slice(0, realLength);
                } else {
                    compressedContent = fullCompressedContent;
                }

                Object outMsg;
                if(msg instanceof TextWebSocketFrame) {
                    outMsg = new TextWebSocketFrame(msg.isFinalFragment(), this.rsv(msg), (ByteBuf)compressedContent);
                } else if(msg instanceof BinaryWebSocketFrame) {
                    outMsg = new BinaryWebSocketFrame(msg.isFinalFragment(), this.rsv(msg), (ByteBuf)compressedContent);
                } else {
                    if(!(msg instanceof ContinuationWebSocketFrame)) {
                        throw new CodecException("unexpected frame type: " + msg.getClass().getName());
                    }

                    outMsg = new ContinuationWebSocketFrame(msg.isFinalFragment(), this.rsv(msg), (ByteBuf)compressedContent);
                }

                out.add(outMsg);
                return;
            }

            if(!partCompressedContent.isReadable()) {
                partCompressedContent.release();
            } else {
                fullCompressedContent.addComponent(true, partCompressedContent);
            }
        }
    }

    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        this.cleanup();
        super.handlerRemoved(ctx);
    }

    private void cleanup() {
        if(this.encoder != null) {
            if(this.encoder.finish()) {
                while(true) {
                    ByteBuf buf = (ByteBuf)this.encoder.readOutbound();
                    if(buf == null) {
                        break;
                    }

                    buf.release();
                }
            }

            this.encoder = null;
        }

    }
}
