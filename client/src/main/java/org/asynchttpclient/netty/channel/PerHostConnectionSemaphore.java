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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;

/**
 * Max per-host connections limiter.
 */
public class PerHostConnectionSemaphore implements ConnectionSemaphore {

  protected final ConcurrentHashMap<Object, Semaphore> freeChannelsPerHost = new ConcurrentHashMap<>();
  protected final int maxConnectionsPerHost;
  protected final IOException tooManyConnectionsPerHost;
  protected final int acquireTimeout;

  PerHostConnectionSemaphore(int maxConnectionsPerHost, int acquireTimeout) {
    tooManyConnectionsPerHost = unknownStackTrace(new TooManyConnectionsPerHostException(maxConnectionsPerHost), PerHostConnectionSemaphore.class, "acquireChannelLock");
    this.maxConnectionsPerHost = maxConnectionsPerHost;
    this.acquireTimeout = Math.max(0, acquireTimeout);
  }

  @Override
  public void acquireChannelLock(Object partitionKey) throws IOException {
    try {
      if (!getFreeConnectionsForHost(partitionKey).tryAcquire(acquireTimeout, TimeUnit.MILLISECONDS)) {
        throw tooManyConnectionsPerHost;
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void releaseChannelLock(Object partitionKey) {
    getFreeConnectionsForHost(partitionKey).release();
  }

  protected Semaphore getFreeConnectionsForHost(Object partitionKey) {
    return maxConnectionsPerHost > 0 ?
            freeChannelsPerHost.computeIfAbsent(partitionKey, pk -> new Semaphore(maxConnectionsPerHost)) :
            InfiniteSemaphore.INSTANCE;
  }
}
