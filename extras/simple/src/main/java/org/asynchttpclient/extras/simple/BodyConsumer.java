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

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A simple API to be used with the {@link SimpleAsyncHttpClient} class in order to process response bytes.
 * <p>
 * Implementations of this interface consume response body chunks as they arrive, enabling
 * streaming processing of HTTP responses without loading the entire response into memory.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Consume to StringBuilder
 * StringBuilder sb = new StringBuilder();
 * BodyConsumer consumer = new AppendableBodyConsumer(sb);
 *
 * // Consume to File
 * BodyConsumer fileConsumer = new FileBodyConsumer(new File("output.txt"));
 *
 * // Consume to OutputStream
 * BodyConsumer streamConsumer = new OutputStreamBodyConsumer(outputStream);
 * }</pre>
 */
public interface BodyConsumer extends Closeable {

  /**
   * Consumes the received bytes from an HTTP response body chunk.
   * <p>
   * This method is called multiple times as response body chunks arrive,
   * allowing for streaming processing of the response.
   *
   * @param byteBuffer a ByteBuffer containing a chunk of the response body
   * @throws IOException if an I/O error occurs during consumption
   */
  void consume(ByteBuffer byteBuffer) throws IOException;
}
