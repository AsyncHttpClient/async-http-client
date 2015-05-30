/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request.body;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.asynchttpclient.config.AsyncHttpClientConfig;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.future.NettyResponseFuture;
import org.asynchttpclient.netty.request.ProgressListener;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.handler.stream.ChunkedFile;

public class NettyFileBody implements NettyBody {

    private final File file;
    private final long offset;
    private final long length;
    private final AsyncHttpClientConfig config;

    public NettyFileBody(File file, AsyncHttpClientConfig config) throws IOException {
        this(file, 0, file.length(), config);
    }

    public NettyFileBody(File file, long offset, long length, AsyncHttpClientConfig config) throws IOException {
        if (!file.isFile()) {
            throw new IOException(String.format("File %s is not a file or doesn't exist", file.getAbsolutePath()));
        }
        this.file = file;
        this.offset = offset;
        this.length = length;
        this.config = config;
    }

    public File getFile() {
        return file;
    }

    public long getOffset() {
        return offset;
    }

    @Override
    public long getContentLength() {
        return length;
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public void write(Channel channel, NettyResponseFuture<?> future) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile(file, "r");

        try {
            ChannelFuture writeFuture;
            if (ChannelManager.isSslHandlerConfigured(channel.getPipeline()) || config.isDisableZeroCopy()) {
                writeFuture = channel.write(new ChunkedFile(raf, offset, raf.length(), config.getChunkedFileChunkSize()));
            } else {
                final FileRegion region = new OptimizedFileRegion(raf, offset, raf.length());
                writeFuture = channel.write(region);
            }
            writeFuture.addListener(new ProgressListener(config, future.getAsyncHandler(), future, false) {
                public void operationComplete(ChannelFuture cf) {
                    closeSilently(raf);
                    super.operationComplete(cf);
                }
            });
        } catch (IOException ex) {
            closeSilently(raf);
            throw ex;
        }
    }
}
