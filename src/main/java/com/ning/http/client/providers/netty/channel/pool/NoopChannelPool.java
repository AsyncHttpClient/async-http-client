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

public class NoopChannelPool implements ChannelPool {

    @Override
    public boolean offer(Channel connection, String partition) {
        return false;
    }

    @Override
    public Channel poll(String uri) {
        return null;
    }

    @Override
    public boolean removeAll(Channel connection) {
        return false;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public void destroy() {
    }

    @Override
    public void flushPartition(String partitionId) {
    }

    @Override
    public void flushPartitions(ChannelPoolPartitionSelector selector) {
    }
}
