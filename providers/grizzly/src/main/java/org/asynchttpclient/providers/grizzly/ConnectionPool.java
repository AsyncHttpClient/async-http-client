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

import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.connectionpool.EndpointKey;
import org.glassfish.grizzly.connectionpool.MultiEndpointPool;
import org.glassfish.grizzly.connectionpool.SingleEndpointPool;
import org.glassfish.grizzly.utils.DelayedExecutor;

import java.io.IOException;
import java.net.SocketAddress;

/**
 * Extension of standard Grizzly {@link MultiEndpointPool}.
 *
 * @since 2.0
 * @author The Grizzly Team
 */
public class ConnectionPool extends MultiEndpointPool<SocketAddress>{

    private final Object lock = new Object();

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


    // ------------------------------------------ Methods from MultiEndpointPool


    protected SingleEndpointPool<SocketAddress> obtainSingleEndpointPool(
            final EndpointKey<SocketAddress> endpointKey) throws IOException {
        SingleEndpointPool<SocketAddress> sePool =
                endpointToPoolMap.get(endpointKey);
        if (sePool == null) {
            synchronized (poolSync) {
                checkNotClosed();
                if (isMaxCapacityReached()) {
                    throw new MaxCapacityException();
                }
                sePool = endpointToPoolMap.get(endpointKey);
                if (sePool == null) {
                    sePool = createSingleEndpointPool(endpointKey);
                    endpointToPoolMap.put(endpointKey, sePool);
                }
            }
        }

        return sePool;
    }

    @Override
    public GrizzlyFuture<Connection> take(final EndpointKey<SocketAddress> endpointKey) {
        synchronized (lock) {
            final GrizzlyFuture<Connection> f = super.take(endpointKey);
            f.addCompletionHandler(new EmptyCompletionHandler<Connection>() {
                @Override
                public void completed(Connection result) {
                    if (Utils.isSpdyConnection(result)) {
                        release(result);
                    }
                    super.completed(result);
                }
            });
            return f;
        }
    }

    @Override
    public void take(final EndpointKey<SocketAddress> endpointKey,
                     final CompletionHandler<Connection> completionHandler) {
        synchronized (lock) {
            if (completionHandler == null) {
                throw new IllegalStateException("CompletionHandler argument cannot be null.");
            }

            super.take(endpointKey, new CompletionHandler<Connection>() {
                @Override
                public void cancelled() {
                    completionHandler.cancelled();
                }

                @Override
                public void failed(Throwable throwable) {
                    completionHandler.failed(throwable);
                }

                @Override
                public void completed(Connection result) {
                    release(result);
                    completionHandler.completed(result);
                }

                @Override
                public void updated(Connection result) {
                    completionHandler.updated(result);
                }
            });
        }
    }

    @Override
    public boolean release(Connection connection) {
        synchronized (lock) {
            return super.release(connection);
        }
    }

    // ---------------------------------------------------------- Nested Classes


    public static final class MaxCapacityException extends IOException {

        public MaxCapacityException() {
            super("Maximum pool capacity has been reached");
        }

    }

}
