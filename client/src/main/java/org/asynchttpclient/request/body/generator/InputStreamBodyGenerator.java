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
 * A {@link BodyGenerator} that reads from an {@link InputStream} without loading the entire stream into memory.
 * <p>
 * This implementation allows streaming of request bodies from input streams, which is useful
 * for large payloads that should not be fully buffered in memory. The content is read
 * incrementally as needed during the request transfer.
 * </p>
 * <p>
 * <b>Important:</b> The {@link InputStream} must support the {@link InputStream#mark(int)} and
 * {@link InputStream#reset()} operations for proper functionality. If these operations are not
 * supported, mechanisms like authentication challenges, redirects, or resumable transfers will
 * not work correctly.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create from an input stream with unknown length
 * InputStream stream = new FileInputStream("data.bin");
 * BodyGenerator generator = new InputStreamBodyGenerator(stream);
 *
 * // Create from an input stream with known length
 * InputStream stream2 = new ByteArrayInputStream(data);
 * BodyGenerator generator2 = new InputStreamBodyGenerator(stream2, data.length);
 *
 * // Use with AsyncHttpClient
 * AsyncHttpClient client = asyncHttpClient();
 * client.preparePost("http://example.com/upload")
 *     .setBody(generator)
 *     .execute();
 * }</pre>
 */
public final class InputStreamBodyGenerator implements BodyGenerator {

  private static final Logger LOGGER = LoggerFactory.getLogger(InputStreamBody.class);
  private final InputStream inputStream;
  private final long contentLength;

  /**
   * Constructs an input stream body generator with unknown content length.
   *
   * @param inputStream the input stream to read from
   */
  public InputStreamBodyGenerator(InputStream inputStream) {
    this(inputStream, -1L);
  }

  /**
   * Constructs an input stream body generator with specified content length.
   *
   * @param inputStream   the input stream to read from
   * @param contentLength the total number of bytes to read, or -1 if unknown
   */
  public InputStreamBodyGenerator(InputStream inputStream, long contentLength) {
    this.inputStream = inputStream;
    this.contentLength = contentLength;
  }

  /**
   * Gets the input stream that this generator reads from.
   *
   * @return the input stream
   */
  public InputStream getInputStream() {
    return inputStream;
  }

  /**
   * Gets the content length of this body.
   *
   * @return the content length in bytes, or -1 if unknown
   */
  public long getContentLength() {
    return contentLength;
  }

  /**
   * Creates a new body instance that reads from the input stream.
   *
   * @return a new body instance
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

