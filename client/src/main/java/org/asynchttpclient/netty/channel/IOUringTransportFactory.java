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

import io.netty.incubator.channel.uring.IOUring;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringSocketChannel;

import java.util.concurrent.ThreadFactory;

class IOUringTransportFactory implements TransportFactory<IOUringSocketChannel, IOUringEventLoopGroup> {

  IOUringTransportFactory() {
    try {
      Class.forName("io.netty.incubator.channel.uring.IOUring");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("The io_uring transport is not available");
    }
    if (!IOUring.isAvailable()) {
      throw new IllegalStateException("The io_uring transport is not supported");
    }
  }

  @Override
  public IOUringSocketChannel newChannel() {
    return new IOUringSocketChannel();
  }

  @Override
  public IOUringEventLoopGroup newEventLoopGroup(int ioThreadsCount, ThreadFactory threadFactory) {
    return new IOUringEventLoopGroup(ioThreadsCount, threadFactory);
  }
}
