/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient.providers.grizzly;

import org.glassfish.grizzly.connectionpool.MultiEndpointPool;
import org.glassfish.grizzly.utils.DelayedExecutor;

import java.net.SocketAddress;

public class ConnectionPool extends MultiEndpointPool<SocketAddress>{


    // ------------------------------------------------------------ Constructors


    public ConnectionPool(final int maxConnectionsPerEndpoint,
                          final int maxConnectionsTotal,
                          final DelayedExecutor delayedExecutor,
                          final long connectTimeoutMillis,
                          final long keepAliveTimeoutMillis,
                          final long keepAliveCheckIntervalMillis) {
        super(null, maxConnectionsPerEndpoint,
              maxConnectionsTotal, delayedExecutor, connectTimeoutMillis,
              keepAliveTimeoutMillis, keepAliveCheckIntervalMillis, -1, -1);
    }

}
