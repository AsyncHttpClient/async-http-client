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
package com.ning.http.client;

/**
 * An interface used by an {@link AsyncHttpProvider} for caching http connections.
 */
public interface ConnectionsPool<U, V> {

    /**
     * Add a connection tpo the pool
     *
     * @param uri        a uri used to retrieve the cached connection
     * @param connection an I/O connection
     * @return true if added.
     */
    public boolean offer(U uri, V connection);

    /**
     * Remove the connection associated with the uri.
     *
     * @param uri the uri used when invoking addConnection
     * @return the connection associated with the uri
     */
    public V poll(U uri);

    /**
     * Remove all connections from the cache. A connection might have been associated with several uri.
     *
     * @param connection a connection
     * @return the true if the connection has been removed
     */
    public boolean removeAll(V connection);

    /**
     * Return true if a connection can be cached. A implementation can decide based on some rules to allow caching
     * Calling this method is equivalent of checking the returned value of {@link ConnectionsPool#offer(Object, Object)}
     *
     * @return true if a connection can be cached.
     */
    public boolean canCacheConnection();

    /**
     * Destroy all connections that has been cached by this instance.
     */
    public void destroy();
}
