package org.asynchttpclient.request.body.multipart.part;

import static org.asynchttpclient.util.MiscUtils.closeSilently;
import io.netty.buffer.ByteBuf;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

import org.asynchttpclient.netty.request.body.BodyChunkedInput;
import org.asynchttpclient.request.body.multipart.FilePart;

public class FileMultipartPart extends MultipartPart<FilePart> {

    private final FileChannel channel;
    private final long length;
    private long position = 0L;

    public FileMultipartPart(FilePart part, byte[] boundary) {
        super(part, boundary);
        try {
            channel = new FileInputStream(part.getFile()).getChannel();
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("File part doesn't exist: " + part.getFile().getAbsolutePath(), e);
        }
        length = part.getFile().length();
    }

    @Override
    protected long getContentLength() {
        return part.getFile().length();
    }

    @Override
    protected long transferContentTo(ByteBuf target) throws IOException {
        int transferred = target.writeBytes(channel, target.writableBytes());
        position += transferred;
        if (position == length) {
            state = MultipartState.POST_CONTENT;
            channel.close();
        }
        return transferred;
    }

    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        long transferred = channel.transferTo(channel.position(), BodyChunkedInput.DEFAULT_CHUNK_SIZE, target);
        position += transferred;
        if (position == length) {
            state = MultipartState.POST_CONTENT;
            channel.close();
        } else {
            slowTarget = true;
        }
        return transferred;
    }

    @Override
    public void close() {
        super.close();
        closeSilently(channel);
    }
}
