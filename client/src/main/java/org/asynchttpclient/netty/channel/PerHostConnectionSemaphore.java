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

import org.asynchttpclient.exception.TooManyConnectionsPerHostException;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;

/**
 * Max per-host connections limiter.
 */
public class PerHostConnectionSemaphore implements ConnectionSemaphore {

  private final ConnectionSemaphore globalSemaphore;

  private final ConcurrentHashMap<Object, NonBlockingSemaphore> freeChannelsPerHost = new ConcurrentHashMap<>();
  private final int maxConnectionsPerHost;
  private final IOException tooManyConnectionsPerHost;

  PerHostConnectionSemaphore(int maxConnectionsPerHost, ConnectionSemaphore globalSemaphore) {
    this.globalSemaphore = globalSemaphore;
    tooManyConnectionsPerHost = unknownStackTrace(new TooManyConnectionsPerHostException(maxConnectionsPerHost), PerHostConnectionSemaphore.class, "acquireChannelLock");
    this.maxConnectionsPerHost = maxConnectionsPerHost;
  }

  @Override
  public void acquireChannelLock(Object partitionKey) throws IOException {
    globalSemaphore.acquireChannelLock(partitionKey);

    if (!getFreeConnectionsForHost(partitionKey).tryAcquire()) {
      globalSemaphore.releaseChannelLock(partitionKey);
      throw tooManyConnectionsPerHost;
    }
  }

  @Override
  public void releaseChannelLock(Object partitionKey) {
    globalSemaphore.releaseChannelLock(partitionKey);
    getFreeConnectionsForHost(partitionKey).release();
  }

  private NonBlockingSemaphoreLike getFreeConnectionsForHost(Object partitionKey) {
    return freeChannelsPerHost.computeIfAbsent(partitionKey, pk -> new NonBlockingSemaphore(maxConnectionsPerHost));
  }
}
