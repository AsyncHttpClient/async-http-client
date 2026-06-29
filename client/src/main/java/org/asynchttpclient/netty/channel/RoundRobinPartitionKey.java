/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.channel;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Channel-pool partition key used by {@link org.asynchttpclient.LoadBalance#ROUND_ROBIN}.
 *
 * <p>It augments the regular partition key (produced by the configured
 * {@link org.asynchttpclient.channel.ChannelPoolPartitioning}) with the resolved IP a request is
 * pinned to, so that pooled HTTP/1.1 connections and multiplexed HTTP/2 connections are kept and
 * reused per IP rather than per host.
 */
public final class RoundRobinPartitionKey {

    private final Object baseKey;
    private final InetAddress address;

    public RoundRobinPartitionKey(Object baseKey, InetAddress address) {
        this.baseKey = baseKey;
        this.address = address;
    }

    /**
     * @return a key with the same base but a different IP, used to re-pin to the IP actually connected
     * to when the connector fails over from the initially selected IP
     */
    public RoundRobinPartitionKey withAddress(InetAddress newAddress) {
        return new RoundRobinPartitionKey(baseKey, newAddress);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RoundRobinPartitionKey that = (RoundRobinPartitionKey) o;
        return Objects.equals(baseKey, that.baseKey) && Objects.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(baseKey) + Objects.hashCode(address);
    }

    @Override
    public String toString() {
        return "RoundRobinPartitionKey(baseKey=" + baseKey + ", address=" + address + ")";
    }
}
