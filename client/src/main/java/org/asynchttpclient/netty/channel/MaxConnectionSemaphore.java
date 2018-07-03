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

import org.asynchttpclient.exception.TooManyConnectionsException;

import java.io.IOException;

import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;

/**
 * Max connections limiter.
 *
 * @author Stepan Koltsov
 */
public class MaxConnectionSemaphore implements ConnectionSemaphore {

  private final NonBlockingSemaphoreLike freeChannels;
  private final IOException tooManyConnections;

  MaxConnectionSemaphore(int maxConnections) {
    tooManyConnections = unknownStackTrace(new TooManyConnectionsException(maxConnections), MaxConnectionSemaphore.class, "acquireChannelLock");
    freeChannels = new NonBlockingSemaphore(maxConnections);
  }

  @Override
  public void acquireChannelLock(Object partitionKey) throws IOException {
    if (!freeChannels.tryAcquire())
      throw tooManyConnections;
  }

  @Override
  public void releaseChannelLock(Object partitionKey) {
    freeChannels.release();
  }
}
