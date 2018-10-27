/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart.part;

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.netty.request.body.BodyChunkedInput;
import org.asynchttpclient.request.body.multipart.InputStreamPart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

public class InputStreamMultipartPart extends FileLikeMultipartPart<InputStreamPart> {

  private long position = 0L;
  private ByteBuffer buffer;
  private ReadableByteChannel channel;

  public InputStreamMultipartPart(InputStreamPart part, byte[] boundary) {
    super(part, boundary);
  }

  private ByteBuffer getBuffer() {
    if (buffer == null) {
      buffer = ByteBuffer.allocateDirect(BodyChunkedInput.DEFAULT_CHUNK_SIZE);
    }
    return buffer;
  }

  private ReadableByteChannel getChannel() {
    if (channel == null) {
      channel = Channels.newChannel(part.getInputStream());
    }
    return channel;
  }

  @Override
  protected long getContentLength() {
    return part.getContentLength();
  }

  @Override
  protected long transferContentTo(ByteBuf target) throws IOException {
    InputStream inputStream = part.getInputStream();
    int transferred = target.writeBytes(inputStream, target.writableBytes());
    if (transferred > 0) {
      position += transferred;
    }
    if (position == getContentLength() || transferred < 0) {
      state = MultipartState.POST_CONTENT;
      inputStream.close();
    }
    return transferred;
  }

  @Override
  protected long transferContentTo(WritableByteChannel target) throws IOException {
    ReadableByteChannel channel = getChannel();
    ByteBuffer buffer = getBuffer();

    int transferred = 0;
    int read = channel.read(buffer);

    if (read > 0) {
      buffer.flip();
      while (buffer.hasRemaining()) {
        transferred += target.write(buffer);
      }
      buffer.compact();
      position += transferred;
    }
    if (position == getContentLength() || read < 0) {
      state = MultipartState.POST_CONTENT;
      if (channel.isOpen()) {
        channel.close();
      }
    }

    return transferred;
  }

  @Override
  public void close() {
    super.close();
    closeSilently(part.getInputStream());
    closeSilently(channel);
  }

}
