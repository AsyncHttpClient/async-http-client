/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;

/**
 * Represents a chunk of body data for feedable body generators.
 * <p>
 * A body chunk contains a buffer with the actual data and a flag indicating
 * whether this is the last chunk in the sequence. This class is used by
 * {@link FeedableBodyGenerator} implementations to queue and transfer body
 * data incrementally.
 * </p>
 */
public final class BodyChunk {
  /**
   * Indicates whether this is the last chunk in the body.
   * When {@code true}, no more chunks will follow.
   */
  public final boolean last;

  /**
   * The buffer containing the chunk data.
   * This buffer holds the actual bytes to be transferred.
   */
  public final ByteBuf buffer;

  /**
   * Constructs a new body chunk.
   *
   * @param buffer the buffer containing the chunk data
   * @param last   {@code true} if this is the last chunk, {@code false} otherwise
   */
  BodyChunk(ByteBuf buffer, boolean last) {
    this.buffer = buffer;
    this.last = last;
  }
}
