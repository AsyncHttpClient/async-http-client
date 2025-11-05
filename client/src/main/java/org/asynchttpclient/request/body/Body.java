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

package org.asynchttpclient.request.body;

import io.netty.buffer.ByteBuf;

import java.io.Closeable;
import java.io.IOException;

/**
 * Represents a request body that can be transferred to a target buffer.
 * <p>
 * This interface provides methods to retrieve the content length and transfer
 * body content in chunks to a ByteBuf. Implementations should be closeable to
 * release any resources held by the body.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * Body body = bodyGenerator.createBody();
 * try {
 *     long length = body.getContentLength();
 *     ByteBuf buffer = Unpooled.buffer();
 *     BodyState state = body.transferTo(buffer);
 *     while (state == BodyState.CONTINUE) {
 *         // Process buffer content
 *         state = body.transferTo(buffer);
 *     }
 * } finally {
 *     body.close();
 * }
 * }</pre>
 */
public interface Body extends Closeable {

  /**
   * Gets the content length of the body.
   *
   * @return the length of the body in bytes, or a negative value if the length is unknown
   */
  long getContentLength();

  /**
   * Transfers the next chunk of bytes from the body to the target buffer.
   * <p>
   * This method reads available bytes from the body and writes them to the provided
   * target buffer. The transfer continues until the buffer is full or no more data
   * is immediately available.
   * </p>
   *
   * @param target the buffer to store the chunk in, must not be {@code null}
   * @return the current state of the body transfer indicating whether to continue,
   *         suspend, or stop reading
   * @throws IOException if the chunk could not be read due to an I/O error
   */
  BodyState transferTo(ByteBuf target) throws IOException;

  /**
   * Represents the state of a body transfer operation.
   * <p>
   * This enum is used to control the flow of data transfer from a body to a target buffer,
   * indicating whether more data is available, the operation should pause, or the transfer
   * is complete.
   * </p>
   */
  enum BodyState {

    /**
     * More data is available and the transfer should continue.
     * This state indicates that the body has successfully written data to the buffer
     * and additional data may be available for reading.
     */
    CONTINUE,

    /**
     * No data is currently available and the transfer should be suspended.
     * This state indicates that the transfer should pause temporarily and may be
     * resumed later when more data becomes available.
     */
    SUSPEND,

    /**
     * No more data is available and the transfer should stop.
     * This state indicates that the body has been completely transferred and
     * no further reading is necessary.
     */
    STOP
  }
}
