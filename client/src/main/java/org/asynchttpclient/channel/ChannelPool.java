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

public interface ChannelPool {

  /**
   * Add a channel to the pool
   *
   * @param channel      an I/O channel
   * @param partitionKey a key used to retrieve the cached channel
   * @return true if added.
   */
  boolean offer(Channel channel, Object partitionKey);

  /**
   * Remove the channel associated with the uri.
   *
   * @param partitionKey the partition used when invoking offer
   * @return the channel associated with the uri
   */
  Channel poll(Object partitionKey);

  /**
   * Remove all channels from the cache. A channel might have been associated
   * with several uri.
   *
   * @param channel a channel
   * @return the true if the channel has been removed
   */
  boolean removeAll(Channel channel);

  /**
   * Return true if a channel can be cached. A implementation can decide based
   * on some rules to allow caching Calling this method is equivalent of
   * checking the returned value of {@link ChannelPool#offer(Channel, Object)}
   *
   * @return true if a channel can be cached.
   */
  boolean isOpen();

  /**
   * Destroy all channels that has been cached by this instance.
   */
  void destroy();

  /**
   * Flush partitions based on a predicate
   *
   * @param predicate the predicate
   */
  void flushPartitions(Predicate<Object> predicate);

  /**
   * @return The number of idle channels per host.
   */
  Map<String, Long> getIdleChannelCountPerHost();
}
