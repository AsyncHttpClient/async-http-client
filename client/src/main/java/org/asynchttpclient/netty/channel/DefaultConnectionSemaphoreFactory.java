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

public class DefaultConnectionSemaphoreFactory implements ConnectionSemaphoreFactory {

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
