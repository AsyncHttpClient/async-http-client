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

/**
 * A {@link BodyGenerator} backed by a byte array.
 * <p>
 * This implementation creates bodies that read from an in-memory byte array.
 * The byte array is shared across all body instances created by this generator,
 * but each body maintains its own read position to support multiple reads
 * (e.g., for retries and redirects).
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create a body generator from a byte array
 * byte[] data = "Request body content".getBytes(StandardCharsets.UTF_8);
 * BodyGenerator generator = new ByteArrayBodyGenerator(data);
 *
 * // Use with AsyncHttpClient
 * AsyncHttpClient client = asyncHttpClient();
 * client.preparePost("http://example.com/api")
 *     .setBody(generator)
 *     .execute();
 * }</pre>
 */
public final class ByteArrayBodyGenerator implements BodyGenerator {

  private final byte[] bytes;

  /**
   * Constructs a byte array body generator.
   *
   * @param bytes the byte array to use as the request body
   */
  public ByteArrayBodyGenerator(byte[] bytes) {
    this.bytes = bytes;
  }

  /**
   * Creates a new body instance that reads from the byte array.
   *
   * @return a new body instance
   */
  @Override
  public Body createBody() {
    return new ByteBody();
  }

  /**
   * A body implementation that reads from a byte array.
   * <p>
   * This class maintains state for reading from the byte array and can be reset
   * to support multiple reads of the same content.
   * </p>
   */
  protected final class ByteBody implements Body {
    private boolean eof = false;
    private int lastPosition = 0;

    /**
     * Returns the content length of this body.
     *
     * @return the length of the byte array in bytes
     */
    public long getContentLength() {
      return bytes.length;
    }

    /**
     * Transfers bytes from the byte array to the target buffer.
     * <p>
     * This method transfers as many bytes as possible to the target buffer,
     * limited by either the remaining bytes in the array or the available
     * space in the target buffer.
     * </p>
     *
     * @param target the buffer to write bytes to
     * @return {@link BodyState#STOP} if all bytes have been transferred,
     *         {@link BodyState#CONTINUE} otherwise
     */
    public BodyState transferTo(ByteBuf target) {

      if (eof) {
        return BodyState.STOP;
      }

      final int remaining = bytes.length - lastPosition;
      final int initialTargetWritableBytes = target.writableBytes();
      if (remaining <= initialTargetWritableBytes) {
        target.writeBytes(bytes, lastPosition, remaining);
        eof = true;
      } else {
        target.writeBytes(bytes, lastPosition, initialTargetWritableBytes);
        lastPosition += initialTargetWritableBytes;
      }
      return BodyState.CONTINUE;
    }

    /**
     * Resets the body state to allow re-reading from the beginning.
     * <p>
     * This method resets the read position and end-of-file flag, allowing
     * the body to be read again from the start.
     * </p>
     */
    public void close() {
      lastPosition = 0;
      eof = false;
    }
  }
}
