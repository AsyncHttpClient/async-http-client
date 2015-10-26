package org.asynchttpclient.request.body.multipart.part;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.asynchttpclient.request.body.multipart.ByteArrayPart;

public class ByteArrayMultipartPart extends MultipartPart<ByteArrayPart> {

    private final ByteBuffer contentBuffer;

    public ByteArrayMultipartPart(ByteArrayPart part, byte[] boundary) {
        super(part, boundary);
        contentBuffer = ByteBuffer.wrap(part.getBytes());
    }

    @Override
    protected long getContentLength() {
        return part.getBytes().length;
    }

    @Override
    protected long transferContentTo(ByteBuffer target) throws IOException {
        return transfer(contentBuffer, target, MultipartState.POST_CONTENT);
    }
    
    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        return transfer(contentBuffer, target, MultipartState.POST_CONTENT);
    }
    
    @Override
    public void close() {
    }
}
