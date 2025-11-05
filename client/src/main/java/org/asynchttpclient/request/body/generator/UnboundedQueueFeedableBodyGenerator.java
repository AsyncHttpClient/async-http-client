/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A feedable body generator backed by an unbounded concurrent queue.
 * <p>
 * This implementation uses a {@link ConcurrentLinkedQueue} to store body chunks.
 * Unlike {@link BoundedQueueFeedableBodyGenerator}, this generator will always accept
 * new chunks regardless of queue size, which means it does not provide backpressure.
 * This can lead to unbounded memory consumption if chunks are fed faster than they
 * can be transferred.
 * </p>
 * <p>
 * Use this generator when you need a simple feedable body without backpressure concerns,
 * or when the data source naturally limits the rate of chunk production.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create an unbounded feedable body generator
 * UnboundedQueueFeedableBodyGenerator generator = new UnboundedQueueFeedableBodyGenerator();
 *
 * // Set up listener for notifications
 * generator.setListener(new FeedListener() {
 *     public void onContentAdded() {
 *         System.out.println("Content available");
 *     }
 *     public void onError(Throwable t) {
 *         System.err.println("Error: " + t.getMessage());
 *     }
 * });
 *
 * // Feed data - always succeeds
 * ByteBuf buffer1 = Unpooled.wrappedBuffer("chunk1".getBytes());
 * generator.feed(buffer1, false);
 *
 * ByteBuf buffer2 = Unpooled.wrappedBuffer("chunk2".getBytes());
 * generator.feed(buffer2, true);
 * }</pre>
 */
public final class UnboundedQueueFeedableBodyGenerator extends QueueBasedFeedableBodyGenerator<ConcurrentLinkedQueue<BodyChunk>> {

  /**
   * Constructs an unbounded queue feedable body generator.
   */
  public UnboundedQueueFeedableBodyGenerator() {
    super(new ConcurrentLinkedQueue<>());
  }

  /**
   * Adds a chunk to the unbounded queue.
   * <p>
   * This method always succeeds and returns {@code true}, as the queue has no capacity limit.
   * </p>
   *
   * @param chunk the body chunk to add to the queue
   * @return {@code true} always, as the chunk is always added
   */
  @Override
  protected boolean offer(BodyChunk chunk) {
    return queue.offer(chunk);
  }
}
