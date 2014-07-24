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
package com.ning.http.client.providers.netty.request.body;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.handler.stream.ChunkedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.ProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class NettyFileBody implements NettyBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyFileBody.class);

    public final static int MAX_BUFFERED_BYTES = 8192;

    private final File file;
    private final long offset;
    private final long length;
    private final NettyAsyncHttpProviderConfig nettyConfig;

    public NettyFileBody(File file, NettyAsyncHttpProviderConfig nettyConfig) throws IOException {
        this(file, 0, file.length(), nettyConfig);
    }

    public NettyFileBody(File file, long offset, long length, NettyAsyncHttpProviderConfig nettyConfig) throws IOException {
        if (!file.isFile()) {
            throw new IOException(String.format("File %s is not a file or doesn't exist", file.getAbsolutePath()));
        }
        this.file = file;
        this.offset = offset;
        this.length = length;
        this.nettyConfig = nettyConfig;
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
    public void write(Channel channel, NettyResponseFuture<?> future, AsyncHttpClientConfig config) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile(file, "r");

        try {
            ChannelFuture writeFuture;
            if (ChannelManager.isSslHandlerConfigured(channel.getPipeline()) || nettyConfig.isDisableZeroCopy()) {
                writeFuture = channel.write(new ChunkedFile(raf, 0, raf.length(), nettyConfig.getChunkedFileChunkSize()));
            } else {
                final FileRegion region = new OptimizedFileRegion(raf, 0, raf.length());
                writeFuture = channel.write(region);
            }
            writeFuture.addListener(new ProgressListener(config, future.getAsyncHandler(), future, false) {
                public void operationComplete(ChannelFuture cf) {
                    try {
                        raf.close();
                    } catch (IOException e) {
                        LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                    }
                    super.operationComplete(cf);
                }
            });
        } catch (IOException ex) {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                }
            }
            throw ex;
        }
    }
}
