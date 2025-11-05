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
import org.asynchttpclient.request.body.Body;

import java.util.Queue;

/**
 * A body implementation that reads from a queue of chunks.
 * <p>
 * This class is used by {@link QueueBasedFeedableBodyGenerator} implementations to provide
 * a body that pulls data from a queue as it becomes available. The body maintains state
 * to track whether more data is expected, the transfer should be suspended, or the
 * body is complete.
 * </p>
 * <p>
 * The content length is unknown (-1) since data is fed incrementally to the queue.
 * The body continues reading chunks from the queue until it receives a chunk marked
 * as the last chunk.
 * </p>
 */
public final class PushBody implements Body {

  private final Queue<BodyChunk> queue;
  private BodyState state = BodyState.CONTINUE;

  /**
   * Constructs a push body that reads from the specified queue.
   *
   * @param queue the queue containing body chunks
   */
  public PushBody(Queue<BodyChunk> queue) {
    this.queue = queue;
  }

  /**
   * Returns the content length of this body.
   * <p>
   * Since the content is fed incrementally, the total length is not known in advance.
   * </p>
   *
   * @return -1 indicating unknown length
   */
  @Override
  public long getContentLength() {
    return -1;
  }

  /**
   * Transfers available chunks from the queue to the target buffer.
   * <p>
   * This method reads chunks from the queue and writes them to the target buffer
   * until the buffer is full, no more chunks are available, or the last chunk
   * has been processed.
   * </p>
   *
   * @param target the buffer to write chunks to
   * @return the current body state: {@link BodyState#CONTINUE} if more data may be available,
   *         {@link BodyState#SUSPEND} if the queue is empty and more data is expected,
   *         or {@link BodyState#STOP} if the last chunk has been processed
   */
  @Override
  public BodyState transferTo(final ByteBuf target) {
    switch (state) {
      case CONTINUE:
        return readNextChunk(target);
      case STOP:
        return BodyState.STOP;
      default:
        throw new IllegalStateException("Illegal process state.");
    }
  }

  private BodyState readNextChunk(ByteBuf target) {
    BodyState res = BodyState.SUSPEND;
    while (target.isWritable() && state != BodyState.STOP) {
      BodyChunk nextChunk = queue.peek();
      if (nextChunk == null) {
        // Nothing in the queue. suspend stream if nothing was read. (reads == 0)
        return res;
      } else if (!nextChunk.buffer.isReadable() && !nextChunk.last) {
        // skip empty buffers
        queue.remove();
      } else {
        res = BodyState.CONTINUE;
        readChunk(target, nextChunk);
      }
    }
    return res;
  }

  private void readChunk(ByteBuf target, BodyChunk part) {
    target.writeBytes(part.buffer);
    if (!part.buffer.isReadable()) {
      if (part.last) {
        state = BodyState.STOP;
      }
      queue.remove();
    }
  }

  @Override
  public void close() {
  }
}
