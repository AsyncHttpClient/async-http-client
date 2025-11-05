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

/**
 * A listener interface for receiving notifications from feedable body generators.
 * <p>
 * Implementations of this interface can be registered with {@link FeedableBodyGenerator}
 * instances to be notified when content is added to the generator or when errors occur.
 * This allows for reactive processing of body data as it becomes available.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * FeedableBodyGenerator generator = new UnboundedQueueFeedableBodyGenerator();
 * generator.setListener(new FeedListener() {
 *     @Override
 *     public void onContentAdded() {
 *         System.out.println("New content is available");
 *         // Resume request processing
 *     }
 *
 *     @Override
 *     public void onError(Throwable t) {
 *         System.err.println("Error feeding content: " + t.getMessage());
 *         // Handle error
 *     }
 * });
 * }</pre>
 */
public interface FeedListener {
  /**
   * Called when new content has been added to the feedable body generator.
   * <p>
   * This notification indicates that data is available for transfer and any
   * suspended operations may be resumed.
   * </p>
   */
  void onContentAdded();

  /**
   * Called when an error occurs while feeding content to the generator.
   * <p>
   * This notification allows the listener to handle errors that occur during
   * the content feeding process.
   * </p>
   *
   * @param t the error that occurred, never {@code null}
   */
  void onError(Throwable t);
}
