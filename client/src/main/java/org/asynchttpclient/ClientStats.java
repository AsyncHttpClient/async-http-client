/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A record class representing the state of a (@link org.asynchttpclient.AsyncHttpClient).
 */
public class ClientStats {

    private final Map<String, HostStats> statsPerHost;

    public ClientStats(Map<String, HostStats> statsPerHost) {
        this.statsPerHost = Collections.unmodifiableMap(statsPerHost);
    }

    /**
     * @return A map from hostname to statistics on that host's connections.
     * The returned map is unmodifiable.
     */
    public Map<String, HostStats> getStatsPerHost() {
        return statsPerHost;
    }

    /**
     * @return The sum of {@link #getTotalActiveConnectionCount()} and {@link #getTotalIdleConnectionCount()},
     * a long representing the total number of connections in the connection pool.
     */
    public long getTotalConnectionCount() {
        return statsPerHost
                .values()
                .stream()
                .mapToLong(HostStats::getHostConnectionCount)
                .sum();
    }

    /**
     * @return A long representing the number of active connections in the connection pool.
     */
    public long getTotalActiveConnectionCount() {
        return statsPerHost
                .values()
                .stream()
                .mapToLong(HostStats::getHostActiveConnectionCount)
                .sum();
    }

    /**
     * @return A long representing the number of idle connections in the connection pool.
     */
    public long getTotalIdleConnectionCount() {
        return statsPerHost
                .values()
                .stream()
                .mapToLong(HostStats::getHostIdleConnectionCount)
                .sum();
    }

    @Override
    public String toString() {
        return "There are " + getTotalConnectionCount() +
                " total connections, " + getTotalActiveConnectionCount() +
                " are active and " + getTotalIdleConnectionCount() + " are idle.";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ClientStats that = (ClientStats) o;
        return Objects.equals(statsPerHost, that.statsPerHost);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(statsPerHost);
    }
}
