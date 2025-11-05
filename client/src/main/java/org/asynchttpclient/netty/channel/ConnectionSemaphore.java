/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.channel;

import java.io.IOException;

/**
 * Semaphore for limiting concurrent connections.
 * <p>
 * This interface provides a mechanism to control the maximum number of concurrent
 * connections that can be established, either globally or per-host. Implementations
 * enforce connection limits to prevent resource exhaustion.
 * </p>
 */
public interface ConnectionSemaphore {

    /**
     * Acquires a lock to allow a new channel connection.
     * <p>
     * This method blocks or throws an exception if the connection limit has been reached.
     * The lock must be released by calling {@link #releaseChannelLock(Object)} when the
     * connection is no longer needed.
     * </p>
     *
     * @param partitionKey the key identifying the connection partition (e.g., host)
     * @throws IOException if the lock cannot be acquired due to connection limits
     */
    void acquireChannelLock(Object partitionKey) throws IOException;

    /**
     * Releases a previously acquired channel lock.
     * <p>
     * This method should be called when a connection is closed or returned to the pool,
     * allowing other pending requests to proceed.
     * </p>
     *
     * @param partitionKey the key identifying the connection partition (e.g., host)
     */
    void releaseChannelLock(Object partitionKey);

}
