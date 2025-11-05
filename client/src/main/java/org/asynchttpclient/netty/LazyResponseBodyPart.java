/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty;

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.netty.util.ByteBufUtils;

import java.nio.ByteBuffer;

/**
 * Response body part that lazily accesses bytes from the Netty ByteBuf.
 * <p>
 * This implementation retains a reference to the original ByteBuf without copying its contents,
 * allowing for zero-copy access to response data. This is more memory efficient but requires
 * careful ByteBuf lifecycle management to avoid use-after-release bugs.
 * </p>
 * <p>
 * The lazy strategy is suitable for advanced use cases where:
 * <ul>
 *   <li>Response bodies are very large and copying would be expensive</li>
 *   <li>The application can properly manage ByteBuf reference counts</li>
 *   <li>Zero-copy semantics are desired (e.g., writing directly to a file or network)</li>
 * </ul>
 * </p>
 */
public class LazyResponseBodyPart extends HttpResponseBodyPart {

  private final ByteBuf buf;

  /**
   * Constructs a lazy response body part.
   *
   * @param buf the Netty ByteBuf containing the response body chunk
   * @param last whether this is the final body part
   */
  public LazyResponseBodyPart(ByteBuf buf, boolean last) {
    super(last);
    this.buf = buf;
  }

  /**
   * Returns the underlying Netty ByteBuf.
   * <p>
   * <b>Warning:</b> The returned ByteBuf must be properly released when no longer needed
   * to avoid memory leaks. Callers are responsible for reference count management.
   * </p>
   *
   * @return the Netty ByteBuf containing the body data
   */
  public ByteBuf getBuf() {
    return buf;
  }

  @Override
  public int length() {
    return buf.readableBytes();
  }

  /**
   * Return the response body's part bytes received.
   *
   * @return the response body's part bytes received.
   */
  @Override
  public byte[] getBodyPartBytes() {
    return ByteBufUtils.byteBuf2Bytes(buf.duplicate());
  }

  @Override
  public ByteBuffer getBodyByteBuffer() {
    return buf.nioBuffer();
  }
}
