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

import org.asynchttpclient.AsyncHttpClientConfig;

/**
 * Factory for creating ConnectionSemaphore instances.
 * <p>
 * This factory creates connection limiters based on the client configuration,
 * allowing different semaphore strategies (per-host, global, or no limits).
 * </p>
 */
public interface ConnectionSemaphoreFactory {

    /**
     * Creates a new ConnectionSemaphore based on the provided configuration.
     * <p>
     * The returned semaphore enforces connection limits according to the
     * configuration settings such as maxConnections and maxConnectionsPerHost.
     * </p>
     *
     * @param config the async HTTP client configuration
     * @return a new ConnectionSemaphore instance
     */
    ConnectionSemaphore newConnectionSemaphore(AsyncHttpClientConfig config);

}
