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

import java.util.concurrent.Semaphore;

/**
 * A blocking connection semaphore based on a plain java Semaphore
 *
 * @author Alex Maltinsky
 */
public class MaxConnectionBlockingSemaphore implements ConnectionSemaphore {

  private final Semaphore freeChannels;

  MaxConnectionBlockingSemaphore(int maxConnections) {
    this.freeChannels = maxConnections > 0 ? new Semaphore(maxConnections) : BlockingSemaphoreInfinite.INSTANCE;
  }

  @Override
  public void acquireChannelLock(Object partitionKey) {
    try {
      this.freeChannels.acquire();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void releaseChannelLock(Object partitionKey) {
    this.freeChannels.release();
  }
}
