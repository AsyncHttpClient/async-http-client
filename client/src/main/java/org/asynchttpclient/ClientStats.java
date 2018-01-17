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
package org.asynchttpclient;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A record class representing the state of an (@link org.asynchttpclient.AsyncHttpClient).
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
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    final ClientStats that = (ClientStats) o;
    return Objects.equals(statsPerHost, that.statsPerHost);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(statsPerHost);
  }
}
