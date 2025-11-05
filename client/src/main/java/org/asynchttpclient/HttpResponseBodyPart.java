/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient;

import java.nio.ByteBuffer;

/**
 * Represents a chunk of the HTTP response body.
 * <p>
 * When using {@link AsyncHandler}, the response body is delivered incrementally as
 * multiple HttpResponseBodyPart instances. This allows for streaming processing of
 * large responses without buffering the entire body in memory.
 * </p>
 * <p>
 * <b>Note:</b> Depending on the underlying provider (Netty), this callback may be
 * invoked with empty body parts. Always check {@link #length()} before processing.
 * </p>
 *
 * @see AsyncHandler#onBodyPartReceived(HttpResponseBodyPart)
 */
public abstract class HttpResponseBodyPart {

  private final boolean last;

  public HttpResponseBodyPart(boolean last) {
    this.last = last;
  }

  /**
   * Returns the length of this body part in bytes.
   *
   * @return the number of bytes in this body part
   */
  public abstract int length();

  /**
   * Returns the body part content as a byte array.
   *
   * @return the bytes of this body part
   */
  public abstract byte[] getBodyPartBytes();

  /**
   * Returns the body part content as a ByteBuffer.
   * The ByteBuffer's capacity equals the number of bytes available in this part.
   *
   * @return a ByteBuffer wrapping the body part bytes
   */
  public abstract ByteBuffer getBodyByteBuffer();

  /**
   * Indicates whether this is the last body part of the response.
   *
   * @return true if this is the last body part, false otherwise
   */
  public boolean isLast() {
    return last;
  }
}
