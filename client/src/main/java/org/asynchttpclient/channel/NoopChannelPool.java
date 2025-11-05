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
package org.asynchttpclient.channel;

import io.netty.channel.Channel;

import java.util.Collections;
import java.util.Map;
import java.util.function.Predicate;

/**
 * No-operation implementation of {@link ChannelPool} that disables connection pooling.
 * <p>
 * This implementation provides a channel pool that never caches channels. All operations
 * are no-ops or return empty results. This is useful when connection pooling is not desired,
 * such as in testing scenarios or when each request should use a fresh connection.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Using the no-op channel pool
 * ChannelPool pool = NoopChannelPool.INSTANCE;
 *
 * // Attempting to offer a channel - always returns false
 * Channel channel = ...;
 * boolean added = pool.offer(channel, "key"); // returns false
 *
 * // Attempting to poll a channel - always returns null
 * Channel reused = pool.poll("key"); // returns null
 *
 * // All operations are safe but have no effect
 * pool.destroy(); // does nothing
 * pool.flushPartitions(key -> true); // does nothing
 * }</pre>
 */
public enum NoopChannelPool implements ChannelPool {

  /**
   * Singleton instance of the no-op channel pool.
   */
  INSTANCE;

  /**
   * Always rejects the channel without caching it.
   *
   * @param channel the I/O channel to add to the pool
   * @param partitionKey the key used to partition and retrieve the cached channel
   * @return always {@code false}, indicating the channel was not added
   */
  @Override
  public boolean offer(Channel channel, Object partitionKey) {
    return false;
  }

  /**
   * Always returns {@code null} as no channels are cached.
   *
   * @param partitionKey the partition key used when the channel was offered via {@link #offer(Channel, Object)}
   * @return always {@code null}, indicating no channel is available
   */
  @Override
  public Channel poll(Object partitionKey) {
    return null;
  }

  /**
   * Always returns {@code false} as no channels are cached.
   *
   * @param channel the channel to remove from all partitions
   * @return always {@code false}, indicating the channel was not found
   */
  @Override
  public boolean removeAll(Channel channel) {
    return false;
  }

  /**
   * Always returns {@code true}, indicating the pool accepts channels.
   * <p>
   * Note: Even though this returns {@code true}, {@link #offer(Channel, Object)}
   * will still return {@code false} as this implementation never caches channels.
   * </p>
   *
   * @return always {@code true}
   */
  @Override
  public boolean isOpen() {
    return true;
  }

  /**
   * No-op implementation that does nothing.
   * <p>
   * Since no channels are cached, there is nothing to destroy.
   * </p>
   */
  @Override
  public void destroy() {
  }

  /**
   * No-op implementation that does nothing.
   * <p>
   * Since no channels are cached, there are no partitions to flush.
   * </p>
   *
   * @param predicate the predicate to evaluate partition keys (ignored)
   */
  @Override
  public void flushPartitions(Predicate<Object> predicate) {
  }

  /**
   * Returns an empty map as no channels are cached.
   *
   * @return an empty immutable map
   */
  @Override
  public Map<String, Long> getIdleChannelCountPerHost() {
    return Collections.emptyMap();
  }
}
