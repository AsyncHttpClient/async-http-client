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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A feedable body generator backed by a bounded blocking queue.
 * <p>
 * This implementation uses an {@link ArrayBlockingQueue} with a fixed capacity to store
 * body chunks. When the queue reaches its capacity, attempts to feed additional chunks
 * will fail (return {@code false}) until space becomes available. This provides backpressure
 * to prevent unbounded memory consumption.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create a bounded feedable body generator with capacity of 100 chunks
 * BoundedQueueFeedableBodyGenerator generator = new BoundedQueueFeedableBodyGenerator(100);
 * generator.setListener(new FeedListener() {
 *     public void onContentAdded() {
 *         System.out.println("Content added to queue");
 *     }
 *     public void onError(Throwable t) {
 *         System.err.println("Error: " + t.getMessage());
 *     }
 * });
 *
 * // Feed data
 * ByteBuf buffer = Unpooled.wrappedBuffer("data".getBytes());
 * boolean accepted = generator.feed(buffer, false);
 * if (!accepted) {
 *     System.out.println("Queue is full");
 * }
 * }</pre>
 */
public final class BoundedQueueFeedableBodyGenerator extends QueueBasedFeedableBodyGenerator<BlockingQueue<BodyChunk>> {

  /**
   * Constructs a bounded queue feedable body generator with the specified capacity.
   *
   * @param capacity the maximum number of chunks that can be queued
   */
  public BoundedQueueFeedableBodyGenerator(int capacity) {
    super(new ArrayBlockingQueue<>(capacity, true));
  }

  /**
   * Attempts to add a chunk to the queue.
   * <p>
   * If the queue is full, this method returns {@code false} immediately without blocking.
   * </p>
   *
   * @param chunk the body chunk to add to the queue
   * @return {@code true} if the chunk was added, {@code false} if the queue is full
   * @throws InterruptedException if interrupted while attempting to add the chunk
   */
  @Override
  protected boolean offer(BodyChunk chunk) throws InterruptedException {
    return queue.offer(chunk);
  }
}
