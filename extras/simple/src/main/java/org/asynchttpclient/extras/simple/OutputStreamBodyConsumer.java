/*
 * Copyright (c) 2010-2013 Sonatype, Inc. All rights reserved.
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
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A {@link BodyConsumer} that writes response body bytes to an {@link OutputStream}.
 * <p>
 * This consumer writes response bytes directly to any OutputStream, making it
 * flexible for writing to files, network streams, or any other output destination.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Write to file
 * OutputStream fos = new FileOutputStream("output.dat");
 * OutputStreamBodyConsumer consumer = new OutputStreamBodyConsumer(fos);
 *
 * // Write to byte array
 * ByteArrayOutputStream baos = new ByteArrayOutputStream();
 * OutputStreamBodyConsumer consumer = new OutputStreamBodyConsumer(baos);
 *
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 *     .setUrl("http://www.example.com/data")
 *     .build();
 * client.get(consumer);
 * }</pre>
 */
public class OutputStreamBodyConsumer implements BodyConsumer {

  private final OutputStream outputStream;

  /**
   * Creates a new OutputStreamBodyConsumer that writes to the specified stream.
   *
   * @param outputStream the OutputStream to write response body bytes to
   */
  public OutputStreamBodyConsumer(OutputStream outputStream) {
    this.outputStream = outputStream;
  }

  /**
   * Writes the received bytes to the underlying OutputStream.
   *
   * @param byteBuffer the buffer containing response body bytes
   * @throws IOException if an I/O error occurs during writing
   */
  @Override
  public void consume(ByteBuffer byteBuffer) throws IOException {
    outputStream.write(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), byteBuffer.remaining());
  }

  /**
   * Closes the underlying OutputStream.
   *
   * @throws IOException if an I/O error occurs during closing
   */
  @Override
  public void close() throws IOException {
    outputStream.close();
  }
}
