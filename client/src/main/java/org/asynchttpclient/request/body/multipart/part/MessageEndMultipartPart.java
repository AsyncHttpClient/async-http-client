package org.asynchttpclient.request.body.multipart.part;

import static org.asynchttpclient.request.body.multipart.Part.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

import org.asynchttpclient.request.body.multipart.FileLikePart;

public class MessageEndMultipartPart extends MultipartPart<FileLikePart> {

    private final ByteBuf buffer;

    public MessageEndMultipartPart(byte[] boundary) {
        super(null, boundary);
        buffer = ByteBufAllocator.DEFAULT.buffer((int) length());
        buffer.writeBytes(EXTRA_BYTES).writeBytes(boundary).writeBytes(EXTRA_BYTES).writeBytes(CRLF_BYTES);
        state = MultipartState.PRE_CONTENT;
    }

    @Override
    public long transferTo(ByteBuf target) throws IOException {
        return transfer(buffer, target, MultipartState.DONE);
    }

    @Override
    public long transferTo(WritableByteChannel target) throws IOException {
        slowTarget = false;
        return transfer(buffer, target, MultipartState.DONE);
    }

    @Override
    protected ByteBuf computePreContentBytes() {
        return Unpooled.EMPTY_BUFFER;
    }

    @Override
    protected ByteBuf computePostContentBytes() {
        return Unpooled.EMPTY_BUFFER;
    }

    @Override
    protected long getContentLength() {
        return EXTRA_BYTES.length + boundary.length + EXTRA_BYTES.length + CRLF_BYTES.length;
    }

    @Override
    protected long transferContentTo(ByteBuf target) throws IOException {
        throw new UnsupportedOperationException("Not supposed to be called");
    }

    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        throw new UnsupportedOperationException("Not supposed to be called");
    }

    @Override
    public void close() {
        buffer.release();
    }
}
