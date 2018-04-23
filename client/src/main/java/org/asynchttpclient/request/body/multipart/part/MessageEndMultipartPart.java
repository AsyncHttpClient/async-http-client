/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.request.body.multipart.FileLikePart;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

public class MessageEndMultipartPart extends MultipartPart<FileLikePart> {

  // lazy
  private ByteBuf contentBuffer;

  public MessageEndMultipartPart(byte[] boundary) {
    super(null, boundary);
    state = MultipartState.PRE_CONTENT;
  }

  @Override
  public long transferTo(ByteBuf target) {
    return transfer(lazyLoadContentBuffer(), target, MultipartState.DONE);
  }

  @Override
  public long transferTo(WritableByteChannel target) throws IOException {
    slowTarget = false;
    return transfer(lazyLoadContentBuffer(), target, MultipartState.DONE);
  }

  private ByteBuf lazyLoadContentBuffer() {
    if (contentBuffer == null) {
      contentBuffer = ByteBufAllocator.DEFAULT.buffer((int) getContentLength());
      contentBuffer.writeBytes(EXTRA_BYTES).writeBytes(boundary).writeBytes(EXTRA_BYTES).writeBytes(CRLF_BYTES);
    }
    return contentBuffer;
  }

  @Override
  protected int computePreContentLength() {
    return 0;
  }

  @Override
  protected ByteBuf computePreContentBytes(int preContentLength) {
    return Unpooled.EMPTY_BUFFER;
  }

  @Override
  protected int computePostContentLength() {
    return 0;
  }

  @Override
  protected ByteBuf computePostContentBytes(int postContentLength) {
    return Unpooled.EMPTY_BUFFER;
  }

  @Override
  protected long getContentLength() {
    return EXTRA_BYTES.length + boundary.length + EXTRA_BYTES.length + CRLF_BYTES.length;
  }

  @Override
  protected long transferContentTo(ByteBuf target) {
    throw new UnsupportedOperationException("Not supposed to be called");
  }

  @Override
  protected long transferContentTo(WritableByteChannel target) {
    throw new UnsupportedOperationException("Not supposed to be called");
  }

  @Override
  public void close() {
    super.close();
    if (contentBuffer != null)
      contentBuffer.release();
  }
}
