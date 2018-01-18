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

import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedNioFile;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.WriteProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
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
}
