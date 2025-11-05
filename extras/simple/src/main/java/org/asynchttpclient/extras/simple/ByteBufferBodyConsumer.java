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
package org.asynchttpclient.extras.simple;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A {@link BodyConsumer} that writes response body bytes into a {@link ByteBuffer}.
 * <p>
 * This consumer accumulates response bytes directly into a ByteBuffer, which is
 * useful when you need the response data in ByteBuffer form for further processing.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * ByteBuffer buffer = ByteBuffer.allocate(8192);
 * ByteBufferBodyConsumer consumer = new ByteBufferBodyConsumer(buffer);
 *
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 *     .setUrl("http://www.example.com")
 *     .build();
 *
 * Future<Response> future = client.get(consumer);
 * // After completion, buffer contains the response data
 * }</pre>
 */
public class ByteBufferBodyConsumer implements BodyConsumer {

  private final ByteBuffer byteBuffer;

  /**
   * Creates a new ByteBufferBodyConsumer that writes to the specified buffer.
   *
   * @param byteBuffer the ByteBuffer to write response body bytes into
   */
  public ByteBufferBodyConsumer(ByteBuffer byteBuffer) {
    this.byteBuffer = byteBuffer;
  }

  /**
   * Writes the received bytes into the underlying ByteBuffer.
   *
   * @param byteBuffer the buffer containing response body bytes
   * @throws IOException if an I/O error occurs during writing
   */
  @Override
  public void consume(ByteBuffer byteBuffer) throws IOException {
    byteBuffer.put(byteBuffer);
  }

  /**
   * Flips the underlying ByteBuffer, preparing it for reading.
   * <p>
   * After this call, the buffer is ready to be read from the beginning.
   *
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void close() throws IOException {
    byteBuffer.flip();
  }
}
