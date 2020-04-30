/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
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

import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;

import java.util.concurrent.ThreadFactory;

class EpollTransportFactory implements TransportFactory<EpollSocketChannel, EpollEventLoopGroup> {

  EpollTransportFactory() {
    try {
      Class.forName("io.netty.channel.epoll.Epoll");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("The epoll transport is not available");
    }
    if (!Epoll.isAvailable()) {
      throw new IllegalStateException("The epoll transport is not supported");
    }
  }

  @Override
  public EpollSocketChannel newChannel() {
    return new EpollSocketChannel();
  }

  @Override
  public EpollEventLoopGroup newEventLoopGroup(int ioThreadsCount, ThreadFactory threadFactory) {
    return new EpollEventLoopGroup(ioThreadsCount, threadFactory);
  }
}
