package org.asynchttpclient.request.body.multipart.part;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

import org.asynchttpclient.request.body.multipart.ByteArrayPart;

public class ByteArrayMultipartPart extends MultipartPart<ByteArrayPart> {

    private final ByteBuf contentBuffer;

    public ByteArrayMultipartPart(ByteArrayPart part, byte[] boundary) {
        super(part, boundary);
        contentBuffer = Unpooled.wrappedBuffer(part.getBytes());
    }

    @Override
    protected long getContentLength() {
        return part.getBytes().length;
    }

    @Override
    protected long transferContentTo(ByteBuf target) throws IOException {
        return transfer(contentBuffer, target, MultipartState.POST_CONTENT);
    }
    
    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        return transfer(contentBuffer, target, MultipartState.POST_CONTENT);
    }
    
    @Override
    public void close() {
        super.close();
        contentBuffer.release();
    }
}
