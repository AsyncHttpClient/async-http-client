package org.asynchttpclient.request.body.multipart.part;

import static org.asynchttpclient.request.body.multipart.Part.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.asynchttpclient.request.body.multipart.FileLikePart;

public class MessageEndMultipartPart extends MultipartPart<FileLikePart> {

    private static final ByteBuffer EMPTY_BYTE_BUFFER = ByteBuffer.allocate(0);

    private final ByteBuffer buffer;

    public MessageEndMultipartPart(byte[] boundary) {
        super(null, boundary);
        buffer = ByteBuffer.allocate((int) length());
        buffer.put(EXTRA_BYTES).put(boundary).put(EXTRA_BYTES).put(CRLF_BYTES);
        buffer.flip();
        state = MultipartState.PRE_CONTENT;
    }

    @Override
    public long transferTo(ByteBuffer target) throws IOException {
        return transfer(buffer, target, MultipartState.DONE);
    }

    @Override
    public long transferTo(WritableByteChannel target) throws IOException {
        slowTarget = false;
        return transfer(buffer, target, MultipartState.DONE);
    }

    @Override
    protected ByteBuffer computePreContentBytes() {
        return EMPTY_BYTE_BUFFER;
    }

    @Override
    protected ByteBuffer computePostContentBytes() {
        return EMPTY_BYTE_BUFFER;
    }

    @Override
    protected long getContentLength() {
        return EXTRA_BYTES.length + boundary.length + EXTRA_BYTES.length + CRLF_BYTES.length;
    }

    @Override
    protected long transferContentTo(ByteBuffer target) throws IOException {
        throw new UnsupportedOperationException("Not supposed to be called");
    }

    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        throw new UnsupportedOperationException("Not supposed to be called");
    }

    @Override
    public void close() {
    }
}
