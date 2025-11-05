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
 * An abstract base class for feedable body generators backed by a queue.
 * <p>
 * This class provides a common implementation for feedable body generators that use
 * a queue to store {@link BodyChunk}s. Subclasses must implement the {@link #offer(BodyChunk)}
 * method to define how chunks are added to their specific queue implementation (bounded
 * or unbounded).
 * </p>
 * <p>
 * The generator creates {@link PushBody} instances that read from the queue as data
 * becomes available. When chunks are successfully added, registered {@link FeedListener}s
 * are notified.
 * </p>
 */
public abstract class QueueBasedFeedableBodyGenerator<T extends Queue<BodyChunk>> implements FeedableBodyGenerator {

  protected final T queue;
  private FeedListener listener;

  /**
   * Constructs a queue-based feedable body generator.
   *
   * @param queue the queue to use for storing body chunks
   */
  public QueueBasedFeedableBodyGenerator(T queue) {
    this.queue = queue;
  }

  /**
   * Creates a new body instance that reads from the queue.
   *
   * @return a new {@link PushBody} instance backed by the queue
   */
  @Override
  public Body createBody() {
    return new PushBody(queue);
  }

  /**
   * Attempts to add a chunk to the queue.
   * <p>
   * Subclasses must implement this method to define the specific queuing behavior,
   * such as blocking until space is available or returning immediately if the queue
   * is full.
   * </p>
   *
   * @param chunk the body chunk to add to the queue
   * @return {@code true} if the chunk was successfully added, {@code false} otherwise
   * @throws Exception if an error occurs while adding the chunk
   */
  protected abstract boolean offer(BodyChunk chunk) throws Exception;

  /**
   * Feeds a chunk of data to the body generator.
   * <p>
   * This method wraps the buffer in a {@link BodyChunk}, attempts to add it to the queue,
   * and notifies the listener if the chunk was successfully added.
   * </p>
   *
   * @param buffer the buffer containing the chunk data
   * @param isLast {@code true} if this is the last chunk, {@code false} otherwise
   * @return {@code true} if the chunk was accepted, {@code false} otherwise
   * @throws Exception if an error occurs while feeding the chunk
   */
  @Override
  public boolean feed(final ByteBuf buffer, final boolean isLast) throws Exception {
    boolean offered = offer(new BodyChunk(buffer, isLast));
    if (offered && listener != null) {
      listener.onContentAdded();
    }
    return offered;
  }

  /**
   * Sets the listener to be notified when content is added.
   *
   * @param listener the listener to notify, or {@code null} to remove the current listener
   */
  @Override
  public void setListener(FeedListener listener) {
    this.listener = listener;
  }
}
