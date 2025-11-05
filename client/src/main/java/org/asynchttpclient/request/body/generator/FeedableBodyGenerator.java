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

import io.netty.buffer.ByteBuf;

/**
 * A {@link BodyGenerator} that supports incremental feeding of body content.
 * <p>
 * Unlike regular body generators where all content is available upfront, feedable
 * body generators allow content to be provided incrementally over time. This is useful
 * for scenarios where the full request body is not immediately available, such as
 * streaming uploads or reactive data sources.
 * </p>
 * <p>
 * When the body generator returns only part of the payload, the client becomes
 * responsible for feeding the remaining chunks through the {@link #feed} method.
 * A {@link FeedListener} can be registered to receive notifications when content
 * is added or errors occur.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create a feedable body generator
 * FeedableBodyGenerator generator = new UnboundedQueueFeedableBodyGenerator();
 *
 * // Set up a listener for notifications
 * generator.setListener(new FeedListener() {
 *     public void onContentAdded() {
 *         System.out.println("Content available");
 *     }
 *     public void onError(Throwable t) {
 *         System.err.println("Error: " + t);
 *     }
 * });
 *
 * // Feed data incrementally
 * ByteBuf chunk1 = Unpooled.wrappedBuffer("Hello ".getBytes());
 * generator.feed(chunk1, false);
 *
 * ByteBuf chunk2 = Unpooled.wrappedBuffer("World!".getBytes());
 * generator.feed(chunk2, true); // Mark as last chunk
 * }</pre>
 */
public interface FeedableBodyGenerator extends BodyGenerator {

  /**
   * Feeds a chunk of data to the body generator.
   * <p>
   * This method adds a chunk of body content to the generator. The chunk is queued
   * for transfer to the target. If this is the last chunk, the {@code isLast} parameter
   * should be {@code true} to signal completion.
   * </p>
   *
   * @param buffer the buffer containing the chunk data to feed
   * @param isLast {@code true} if this is the last chunk, {@code false} otherwise
   * @return {@code true} if the chunk was accepted, {@code false} if it could not be queued
   *         (e.g., queue is full in bounded implementations)
   * @throws Exception if an error occurs while feeding the chunk
   */
  boolean feed(ByteBuf buffer, boolean isLast) throws Exception;

  /**
   * Sets the listener to be notified of feed events.
   * <p>
   * The listener will be called when content is successfully added to the generator
   * or when errors occur during the feeding process.
   * </p>
   *
   * @param listener the listener to notify, or {@code null} to remove the current listener
   */
  void setListener(FeedListener listener);
}
