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
 * A callback class used when an HTTP response body is received.
 */
public abstract class HttpResponseBodyPart {

  private final boolean last;

  public HttpResponseBodyPart(boolean last) {
    this.last = last;
  }

  /**
   * @return length of this part in bytes
   */
  public abstract int length();

  /**
   * @return the response body's part bytes received.
   */
  public abstract byte[] getBodyPartBytes();

  /**
   * @return a {@link ByteBuffer} that wraps the actual bytes read from the response's chunk.
   * The {@link ByteBuffer}'s capacity is equal to the number of bytes available.
   */
  public abstract ByteBuffer getBodyByteBuffer();

  /**
   * @return true if this is the last part.
   */
  public boolean isLast() {
    return last;
  }
}
