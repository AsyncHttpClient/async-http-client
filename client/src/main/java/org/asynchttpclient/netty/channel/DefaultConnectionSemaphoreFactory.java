/*
 *    Copyright (c) 2018-2023 AsyncHttpClient Project. All rights reserved.
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

import org.asynchttpclient.AsyncHttpClientConfig;

public class DefaultConnectionSemaphoreFactory implements ConnectionSemaphoreFactory {

    @Override
    public ConnectionSemaphore newConnectionSemaphore(AsyncHttpClientConfig config) {
        int acquireFreeChannelTimeout = Math.max(0, config.getAcquireFreeChannelTimeout());
        int maxConnections = config.getMaxConnections();
        int maxConnectionsPerHost = config.getMaxConnectionsPerHost();

        if (maxConnections > 0 && maxConnectionsPerHost > 0) {
            return new CombinedConnectionSemaphore(maxConnections, maxConnectionsPerHost, acquireFreeChannelTimeout);
        }
        if (maxConnections > 0) {
            return new MaxConnectionSemaphore(maxConnections, acquireFreeChannelTimeout);
        }
        if (maxConnectionsPerHost > 0) {
            return new CombinedConnectionSemaphore(maxConnections, maxConnectionsPerHost, acquireFreeChannelTimeout);
        }

        return new NoopConnectionSemaphore();
    }
}
