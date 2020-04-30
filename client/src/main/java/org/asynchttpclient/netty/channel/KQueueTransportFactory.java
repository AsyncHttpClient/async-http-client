/*
 * Copyright (c) 2019 AsyncHttpClient Project. All rights reserved.
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

import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueSocketChannel;

import java.util.concurrent.ThreadFactory;

class KQueueTransportFactory implements TransportFactory<KQueueSocketChannel, KQueueEventLoopGroup> {

  KQueueTransportFactory() {
    try {
      Class.forName("io.netty.channel.kqueue.KQueue");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("The kqueue transport is not available");
    }
    if (!KQueue.isAvailable()) {
      throw new IllegalStateException("The kqueue transport is not supported");
    }
  }

  @Override
  public KQueueSocketChannel newChannel() {
    return new KQueueSocketChannel();
  }

  @Override
  public KQueueEventLoopGroup newEventLoopGroup(int ioThreadsCount, ThreadFactory threadFactory) {
    return new KQueueEventLoopGroup(ioThreadsCount, threadFactory);
  }
}
