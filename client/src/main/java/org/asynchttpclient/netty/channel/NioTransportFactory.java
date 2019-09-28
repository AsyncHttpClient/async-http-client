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

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.concurrent.ThreadFactory;

enum NioTransportFactory implements TransportFactory<NioSocketChannel, NioEventLoopGroup> {

  INSTANCE;

  @Override
  public NioSocketChannel newChannel() {
    return new NioSocketChannel();
  }

  @Override
  public NioEventLoopGroup newEventLoopGroup(int ioThreadsCount, ThreadFactory threadFactory) {
    return new NioEventLoopGroup(ioThreadsCount, threadFactory);
  }
}
