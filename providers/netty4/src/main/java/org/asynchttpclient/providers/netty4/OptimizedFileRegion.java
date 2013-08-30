package org.asynchttpclient.providers.netty4;

import io.netty.channel.FileRegion;
import io.netty.util.AbstractReferenceCounted;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

public class OptimizedFileRegion extends AbstractReferenceCounted implements FileRegion {

    private final FileChannel file;
    private final RandomAccessFile raf;
    private final long position;
    private final long count;
    private long byteWritten;

    public OptimizedFileRegion(RandomAccessFile raf, long position, long count) {
        this.raf = raf;
        this.file = raf.getChannel();
        this.position = position;
        this.count = count;
    }

    public long position() {
        return position;
    }

    public long count() {
        return count;
    }
    
    public long transfered() {
        return byteWritten;
    }

    public long transferTo(WritableByteChannel target, long position) throws IOException {
        long count = this.count - position;
        if (count < 0 || position < 0) {
            throw new IllegalArgumentException("position out of range: " + position + " (expected: 0 - " + (this.count - 1) + ")");
        }
        if (count == 0) {
            return 0L;
        }

        long bw = file.transferTo(this.position + position, count, target);
        byteWritten += bw;
        if (byteWritten == raf.length()) {
            deallocate();
        }
        return bw;
    }

    public void deallocate() {
        try {
            file.close();
        } catch (IOException e) {
            NettyAsyncHttpProvider.LOGGER.warn("Failed to close a file.", e);
        }

        try {
            raf.close();
        } catch (IOException e) {
            NettyAsyncHttpProvider.LOGGER.warn("Failed to close a file.", e);
        }
    }
}