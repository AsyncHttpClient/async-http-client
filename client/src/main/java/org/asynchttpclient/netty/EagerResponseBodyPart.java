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

import java.nio.ByteBuffer;

import static org.asynchttpclient.netty.util.ByteBufUtils.byteBuf2Bytes;

/**
 * Response body part that eagerly copies bytes from the Netty ByteBuf.
 * <p>
 * This implementation immediately extracts bytes from the ByteBuf upon construction,
 * allowing the original buffer to be released quickly. This is the default strategy
 * and is suitable for most use cases where response bodies are small to medium sized.
 * </p>
 * <p>
 * The eager strategy trades memory (for the byte copy) for simplified lifecycle management
 * and avoids the need to manually manage ByteBuf reference counts.
 * </p>
 */
public class EagerResponseBodyPart extends HttpResponseBodyPart {

  private final byte[] bytes;

  /**
   * Constructs an eager response body part.
   *
   * @param buf the Netty ByteBuf containing the response body chunk
   * @param last whether this is the final body part
   */
  public EagerResponseBodyPart(ByteBuf buf, boolean last) {
    super(last);
    bytes = byteBuf2Bytes(buf);
  }

  /**
   * Return the response body's part bytes received.
   *
   * @return the response body's part bytes received.
   */
  @Override
  public byte[] getBodyPartBytes() {
    return bytes;
  }

  @Override
  public int length() {
    return bytes.length;
  }

  @Override
  public ByteBuffer getBodyByteBuffer() {
    return ByteBuffer.wrap(bytes);
  }
}
