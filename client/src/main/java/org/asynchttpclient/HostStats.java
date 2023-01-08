/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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

import java.util.Objects;

/**
 * A record class representing the status of connections to some host.
 */
public class HostStats {

    private final long activeConnectionCount;
    private final long idleConnectionCount;

    public HostStats(long activeConnectionCount, long idleConnectionCount) {
        this.activeConnectionCount = activeConnectionCount;
        this.idleConnectionCount = idleConnectionCount;
    }

    /**
     * @return The sum of {@link #getHostActiveConnectionCount()} and {@link #getHostIdleConnectionCount()},
     * a long representing the total number of connections to this host.
     */
    public long getHostConnectionCount() {
        return activeConnectionCount + idleConnectionCount;
    }

    /**
     * @return A long representing the number of active connections to the host.
     */
    public long getHostActiveConnectionCount() {
        return activeConnectionCount;
    }

    /**
     * @return A long representing the number of idle connections in the connection pool.
     */
    public long getHostIdleConnectionCount() {
        return idleConnectionCount;
    }

    @Override
    public String toString() {
        return "There are " + getHostConnectionCount() +
                " total connections, " + getHostActiveConnectionCount() +
                " are active and " + getHostIdleConnectionCount() + " are idle.";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final HostStats hostStats = (HostStats) o;
        return activeConnectionCount == hostStats.activeConnectionCount && idleConnectionCount == hostStats.idleConnectionCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(activeConnectionCount, idleConnectionCount);
    }
}
