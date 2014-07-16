/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.asynchttpclient.providers.netty.channel.pool;

import io.netty.channel.Channel;

public interface ChannelPool {

    /**
     * Add a channel to the pool
     * 
     * @param poolKey a key used to retrieve the cached channel
     * @param channel an I/O channel
     * @return true if added.
     */
    boolean offer(Channel channel, String poolKey);

    /**
     * Remove the channel associated with the uri.
     * 
     * @param uri the uri used when invoking addConnection
     * @return the channel associated with the uri
     */
    Channel poll(String uri);

    /**
     * Remove all channels from the cache. A channel might have been associated with several uri.
     * 
     * @param channel a channel
     * @return the true if the channel has been removed
     */
    boolean removeAll(Channel channel);

    /**
     * Return true if a channel can be cached. A implementation can decide based on some rules to allow caching
     * Calling this method is equivalent of checking the returned value of {@link ChannelPool#offer(Object, Object)}
     * 
     * @return true if a channel can be cached.
     */
    boolean isOpen();

    /**
     * Destroy all channels that has been cached by this instance.
     */
    void destroy();
}
