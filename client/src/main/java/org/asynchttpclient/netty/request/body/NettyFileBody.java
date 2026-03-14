/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.request.body;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.stream.ChunkedNioFile;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.WriteProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class NettyFileBody implements NettyBody {

    private final File file;
    private final long offset;
    private final long length;
    private final AsyncHttpClientConfig config;

    public NettyFileBody(File file, AsyncHttpClientConfig config) {
        this(file, 0, file.length(), config);
    }

    public NettyFileBody(File file, long offset, long length, AsyncHttpClientConfig config) {
        if (!file.isFile()) {
            throw new IllegalArgumentException(String.format("File %s is not a file or doesn't exist", file.getAbsolutePath()));
        }
        this.file = file;
        this.offset = offset;
        this.length = length;
        this.config = config;
    }

    public File getFile() {
        return file;
    }

    @Override
    public long getContentLength() {
        return length;
    }

    @Override
    public void write(Channel channel, NettyResponseFuture<?> future) throws IOException {
        @SuppressWarnings("resource")
        // netty will close the FileChannel
        FileChannel fileChannel = new RandomAccessFile(file, "r").getChannel();
        boolean noZeroCopy = ChannelManager.isSslHandlerConfigured(channel.pipeline()) || config.isDisableZeroCopy();
        Object body = noZeroCopy ? new ChunkedNioFile(fileChannel, offset, length, config.getChunkedFileChunkSize()) : new DefaultFileRegion(fileChannel, offset, length);

        channel.write(body, channel.newProgressivePromise())
                .addListener(new WriteProgressListener(future, false, length));
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, channel.voidPromise());
    }

    @Override
    public void writeHttp2(Http2StreamChannel channel, NettyResponseFuture<?> future) throws IOException {
        int chunkSize = config.getChunkedFileChunkSize();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel fileChannel = raf.getChannel()) {
            long remaining = length;
            long pos = offset;
            while (remaining > 0) {
                int toRead = (int) Math.min(chunkSize, remaining);
                ByteBuf buf = channel.alloc().buffer(toRead);
                int read = buf.writeBytes(fileChannel, pos, toRead);
                if (read <= 0) {
                    buf.release();
                    break;
                }
                remaining -= read;
                pos += read;
                boolean last = remaining <= 0;
                channel.write(new DefaultHttp2DataFrame(buf, last));
            }
            channel.flush();
        }
    }
}
