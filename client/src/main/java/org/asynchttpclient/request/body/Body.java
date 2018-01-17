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
 * A request body.
 */
public interface Body extends Closeable {

  /**
   * Gets the length of the body.
   *
   * @return The length of the body in bytes, or negative if unknown.
   */
  long getContentLength();

  /**
   * Reads the next chunk of bytes from the body.
   *
   * @param target The buffer to store the chunk in, must not be {@code null}.
   * @return The state.
   * @throws IOException If the chunk could not be read.
   */
  BodyState transferTo(ByteBuf target) throws IOException;

  enum BodyState {

    /**
     * There's something to read
     */
    CONTINUE,

    /**
     * There's nothing to read and input has to suspend
     */
    SUSPEND,

    /**
     * There's nothing to read and input has to stop
     */
    STOP
  }
}
