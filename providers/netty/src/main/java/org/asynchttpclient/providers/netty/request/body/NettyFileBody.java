/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.request.body;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.ProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyFileBody implements NettyBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyFileBody.class);

    public final static int MAX_BUFFERED_BYTES = 8192;

    private final File file;
    private final long offset;
    private final long length;
    private final boolean disableZeroCopy;

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
        disableZeroCopy = nettyConfig.isDisableZeroCopy();
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
            if (Channels.getSslHandler(channel) != null || disableZeroCopy) {
                writeFuture = channel.write(new ChunkedFile(raf, offset, length, MAX_BUFFERED_BYTES), channel.newProgressivePromise());
            } else {
                FileRegion region = new DefaultFileRegion(raf.getChannel(), offset, length);
                writeFuture = channel.write(region, channel.newProgressivePromise());
            }
            writeFuture.addListener(new ProgressListener(config, future.getAsyncHandler(), future, false, getContentLength()) {
                public void operationComplete(ChannelProgressiveFuture cf) {
                    try {
                        // FIXME probably useless in Netty 4
                        raf.close();
                    } catch (IOException e) {
                        LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                    }
                    super.operationComplete(cf);
                }
            });
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
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
