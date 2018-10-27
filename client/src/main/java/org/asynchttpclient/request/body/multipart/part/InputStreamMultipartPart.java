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
