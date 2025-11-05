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

import java.util.Map;
import java.util.function.Predicate;

/**
 * Pool for managing and reusing Netty channels for persistent HTTP connections.
 * <p>
 * The channel pool maintains idle channels grouped by partition keys, allowing
 * connection reuse across multiple HTTP requests to reduce connection overhead.
 * Channels are organized using a partitioning strategy to ensure proper isolation
 * between different connection configurations (hosts, proxies, etc.).
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create a channel pool
 * ChannelPool pool = new DefaultChannelPool(...);
 *
 * // Offer a channel to the pool
 * Channel channel = ...;
 * Object partitionKey = "https://example.com:443";
 * boolean added = pool.offer(channel, partitionKey);
 *
 * // Poll a channel from the pool
 * Channel reusedChannel = pool.poll(partitionKey);
 * if (reusedChannel != null) {
 *     // Reuse the channel
 * }
 *
 * // Clean up when done
 * pool.destroy();
 * }</pre>
 */
public interface ChannelPool {

  /**
   * Adds a channel to the pool for potential reuse.
   * <p>
   * The channel is stored under the specified partition key and can be retrieved
   * later using {@link #poll(Object)} with the same key. If the pool is closed
   * or the channel cannot be cached for any reason, this method returns {@code false}.
   * </p>
   *
   * @param channel the I/O channel to add to the pool
   * @param partitionKey the key used to partition and retrieve the cached channel
   * @return {@code true} if the channel was successfully added to the pool, {@code false} otherwise
   */
  boolean offer(Channel channel, Object partitionKey);

  /**
   * Retrieves and removes a channel from the pool.
   * <p>
   * Returns an idle channel associated with the specified partition key, if available.
   * The channel is removed from the pool and becomes the caller's responsibility to
   * manage. Returns {@code null} if no channel is available for the given partition key.
   * </p>
   *
   * @param partitionKey the partition key used when the channel was offered via {@link #offer(Channel, Object)}
   * @return an idle channel associated with the partition key, or {@code null} if none available
   */
  Channel poll(Object partitionKey);

  /**
   * Removes all occurrences of the specified channel from the pool.
   * <p>
   * A channel may be associated with multiple partition keys. This method removes
   * the channel from all partitions where it appears, ensuring complete cleanup.
   * </p>
   *
   * @param channel the channel to remove from all partitions
   * @return {@code true} if the channel was found and removed, {@code false} otherwise
   */
  boolean removeAll(Channel channel);

  /**
   * Checks whether the pool is open and accepting channels.
   * <p>
   * When the pool is closed, calls to {@link #offer(Channel, Object)} will return
   * {@code false}, and no new channels will be cached. This method can be used to
   * check the pool's state before attempting to offer a channel.
   * </p>
   *
   * @return {@code true} if the pool is open and can cache channels, {@code false} otherwise
   */
  boolean isOpen();

  /**
   * Destroys the pool and closes all cached channels.
   * <p>
   * This method closes all idle channels in the pool and releases associated resources.
   * After calling this method, the pool should not be used further. Any subsequent
   * operations may throw exceptions or return immediately without performing any action.
   * </p>
   */
  void destroy();

  /**
   * Removes all channels from partitions matching the specified predicate.
   * <p>
   * This method evaluates each partition key against the provided predicate. If the
   * predicate returns {@code true} for a partition key, all channels in that partition
   * are removed and closed. This is useful for selective cleanup, such as removing
   * channels for specific hosts or proxy configurations.
   * </p>
   *
   * @param predicate the predicate to evaluate partition keys; partitions for which
   *                  the predicate returns {@code true} will be flushed
   */
  void flushPartitions(Predicate<Object> predicate);

  /**
   * Returns statistics about idle channels grouped by host.
   * <p>
   * This method provides visibility into the pool's current state, showing how many
   * idle channels are cached for each host. The returned map uses host identifiers
   * as keys and channel counts as values. This information is useful for monitoring
   * and debugging connection pooling behavior.
   * </p>
   *
   * @return an immutable map of host identifiers to idle channel counts
   */
  Map<String, Long> getIdleChannelCountPerHost();
}
