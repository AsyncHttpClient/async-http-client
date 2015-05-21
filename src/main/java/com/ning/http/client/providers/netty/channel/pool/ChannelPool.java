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
package com.ning.http.client.providers.netty.channel.pool;

import org.jboss.netty.channel.Channel;

/**
 * An interface used by an {@link AsyncHttpProvider} for caching http connections.
 */
public interface ChannelPool {

    /**
     * Add a connection to the pool
     *
     * @param partition        a key used to retrieve the cached connection
     * @param connection an I/O connection
     * @return true if added.
     */
    boolean offer(Channel connection, Object partitionKey);

    /**
     * Get a connection from a partition
     *
     * @param partition the id of the partition used when invoking offer
     * @return the connection associated with the partition
     */
    Channel poll(Object partitionKey);

    /**
     * Remove all connections from the cache. A connection might have been associated with several uri.
     *
     * @param connection a connection
     * @return the true if the connection has been removed
     */
    boolean removeAll(Channel connection);

    /**
     * Return true if a connection can be cached. A implementation can decide based on some rules to allow caching
     * Calling this method is equivalent of checking the returned value of {@link ChannelPool#offer(Object, Object)}
     *
     * @return true if a connection can be cached.
     */
    boolean isOpen();

    /**
     * Destroy all connections that has been cached by this instance.
     */
    void destroy();

    /**
     * Flush a partition
     * 
     * @param partition
     */
    void flushPartition(Object partitionKey);

    /**
     * Flush partitions based on a selector
     * 
     * @param selector
     */
    void flushPartitions(ChannelPoolPartitionSelector selector);
}
