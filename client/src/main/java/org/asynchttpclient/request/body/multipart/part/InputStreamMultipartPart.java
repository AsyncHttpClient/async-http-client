package org.asynchttpclient.request.body.multipart.part;

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.request.body.multipart.InputStreamPart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.WritableByteChannel;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

public class InputStreamMultipartPart extends FileLikeMultipartPart<InputStreamPart> {

  private long position = 0L;

  public InputStreamMultipartPart(InputStreamPart part, byte[] boundary) {
    super(part, boundary);
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
    throw new UnsupportedOperationException("InputStreamPart does not support zero-copy transfers");
  }

  @Override
  public void close() {
    super.close();
    closeSilently(part.getInputStream());
  }

}
