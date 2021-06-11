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

import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import org.asynchttpclient.util.ReflectionUtil;

import java.util.concurrent.ThreadFactory;

class EpollDomainTransportFactory implements TransportFactory<EpollDomainSocketChannel, EpollEventLoopGroup> {

  EpollDomainTransportFactory() {
    ReflectionUtil.loadEpollClass();
  }

  @Override
  public EpollDomainSocketChannel newChannel() {
    return new EpollDomainSocketChannel();
  }

  @Override
  public EpollEventLoopGroup newEventLoopGroup(int ioThreadsCount, ThreadFactory threadFactory) {
    return new EpollEventLoopGroup(ioThreadsCount, threadFactory);
  }
}
