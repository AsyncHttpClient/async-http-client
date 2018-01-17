/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.request.body.Body;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link BodyGenerator} which use an {@link InputStream} for reading bytes, without having to read the entire stream in memory.
 * <br>
 * NOTE: The {@link InputStream} must support the {@link InputStream#mark} and {@link java.io.InputStream#reset()} operation. If not, mechanisms like authentication, redirect, or
 * resumable download will not works.
 */
public final class InputStreamBodyGenerator implements BodyGenerator {

  private static final Logger LOGGER = LoggerFactory.getLogger(InputStreamBody.class);
  private final InputStream inputStream;
  private final long contentLength;

  public InputStreamBodyGenerator(InputStream inputStream) {
    this(inputStream, -1L);
  }

  public InputStreamBodyGenerator(InputStream inputStream, long contentLength) {
    this.inputStream = inputStream;
    this.contentLength = contentLength;
  }

  public InputStream getInputStream() {
    return inputStream;
  }

  public long getContentLength() {
    return contentLength;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Body createBody() {
    return new InputStreamBody(inputStream, contentLength);
  }

  private class InputStreamBody implements Body {

    private final InputStream inputStream;
    private final long contentLength;
    private byte[] chunk;

    private InputStreamBody(InputStream inputStream, long contentLength) {
      this.inputStream = inputStream;
      this.contentLength = contentLength;
    }

    public long getContentLength() {
      return contentLength;
    }

    public BodyState transferTo(ByteBuf target) {

      // To be safe.
      chunk = new byte[target.writableBytes() - 10];

      int read = -1;
      boolean write = false;
      try {
        read = inputStream.read(chunk);
      } catch (IOException ex) {
        LOGGER.warn("Unable to read", ex);
      }

      if (read > 0) {
        target.writeBytes(chunk, 0, read);
        write = true;
      }
      return write ? BodyState.CONTINUE : BodyState.STOP;
    }

    public void close() throws IOException {
      inputStream.close();
    }
  }
}

