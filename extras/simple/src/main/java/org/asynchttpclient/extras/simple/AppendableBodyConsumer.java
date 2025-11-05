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

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A {@link BodyConsumer} that consumes response body bytes by appending them to an {@link Appendable}.
 * <p>
 * This consumer converts ByteBuffers to Strings using the specified charset and appends
 * them to the provided Appendable (such as StringBuilder, StringBuffer, or Writer).
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Consume to StringBuilder
 * StringBuilder sb = new StringBuilder();
 * BodyConsumer consumer = new AppendableBodyConsumer(sb);
 *
 * // Consume to StringBuffer with custom charset
 * StringBuffer buffer = new StringBuffer();
 * BodyConsumer consumer = new AppendableBodyConsumer(buffer, StandardCharsets.ISO_8859_1);
 *
 * // Use with SimpleAsyncHttpClient
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 *     .setUrl("http://www.example.com")
 *     .build();
 * StringBuilder response = new StringBuilder();
 * client.get(new AppendableBodyConsumer(response));
 * }</pre>
 */
public class AppendableBodyConsumer implements BodyConsumer {

  private final Appendable appendable;
  private final Charset charset;

  /**
   * Creates a new AppendableBodyConsumer with a specified charset.
   *
   * @param appendable the Appendable to which response bytes will be appended
   * @param charset the charset to use for decoding bytes to strings
   */
  public AppendableBodyConsumer(Appendable appendable, Charset charset) {
    this.appendable = appendable;
    this.charset = charset;
  }

  /**
   * Creates a new AppendableBodyConsumer using UTF-8 charset.
   *
   * @param appendable the Appendable to which response bytes will be appended
   */
  public AppendableBodyConsumer(Appendable appendable) {
    this.appendable = appendable;
    this.charset = UTF_8;
  }

  /**
   * Consumes the ByteBuffer by converting it to a String and appending to the Appendable.
   *
   * @param byteBuffer the buffer containing response body bytes
   * @throws IOException if an I/O error occurs during appending
   */
  @Override
  public void consume(ByteBuffer byteBuffer) throws IOException {
    appendable
            .append(new String(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), byteBuffer.remaining(), charset));
  }

  /**
   * Closes the underlying Appendable if it implements {@link Closeable}.
   *
   * @throws IOException if an I/O error occurs during closing
   */
  @Override
  public void close() throws IOException {
    if (appendable instanceof Closeable) {
      Closeable.class.cast(appendable).close();
    }
  }
}
