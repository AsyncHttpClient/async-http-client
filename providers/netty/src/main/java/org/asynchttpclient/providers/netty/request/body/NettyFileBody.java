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
package org.asynchttpclient.providers.netty.request.body;

import static org.asynchttpclient.util.MiscUtils.closeSilently;
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
import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.ProgressListener;

public class NettyFileBody implements NettyBody {

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
            if (ChannelManager.isSslHandlerConfigured(channel.pipeline()) || nettyConfig.isDisableZeroCopy()) {
                writeFuture = channel.write(new ChunkedFile(raf, offset, length, nettyConfig.getChunkedFileChunkSize()), channel.newProgressivePromise());
            } else {
                FileRegion region = new DefaultFileRegion(raf.getChannel(), offset, length);
                writeFuture = channel.write(region, channel.newProgressivePromise());
            }
            writeFuture.addListener(new ProgressListener(config, future.getAsyncHandler(), future, false, getContentLength()) {
                public void operationComplete(ChannelProgressiveFuture cf) {
                    closeSilently(raf);
                    super.operationComplete(cf);
                }
            });
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } catch (IOException ex) {
            closeSilently(raf);
            throw ex;
        }
    }
}
