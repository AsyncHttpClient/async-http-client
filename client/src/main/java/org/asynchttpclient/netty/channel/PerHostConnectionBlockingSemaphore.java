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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * A blocking per-host connections limiter
 *
 * @author Alex Maltinsky
 */
public class PerHostConnectionBlockingSemaphore implements ConnectionSemaphore {

  private final ConnectionSemaphore globalSemaphore;

  private final ConcurrentHashMap<Object, Semaphore> freeChannelsPerHost = new ConcurrentHashMap<>();
  private final int maxConnectionsPerHost;

  PerHostConnectionBlockingSemaphore(int maxConnectionsPerHost, ConnectionSemaphore globalSemaphore) {
    this.globalSemaphore = globalSemaphore;
    this.maxConnectionsPerHost = maxConnectionsPerHost;
  }

  @Override
  public void acquireChannelLock(Object partitionKey) throws IOException {
    globalSemaphore.acquireChannelLock(partitionKey);

    try {
      getFreeConnectionsForHost(partitionKey).acquire();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void releaseChannelLock(Object partitionKey) {
    globalSemaphore.releaseChannelLock(partitionKey);
    getFreeConnectionsForHost(partitionKey).release();
  }

  private Semaphore getFreeConnectionsForHost(Object partitionKey) {
    return maxConnectionsPerHost > 0 ?
            freeChannelsPerHost.computeIfAbsent(partitionKey, pk -> new Semaphore(maxConnectionsPerHost)) :
            BlockingSemaphoreInfinite.INSTANCE;
  }
}
