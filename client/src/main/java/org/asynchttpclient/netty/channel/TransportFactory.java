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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;

import java.util.concurrent.ThreadFactory;

/**
 * Factory for creating transport-specific channel and event loop group instances.
 * <p>
 * This interface abstracts the creation of Netty transport components, allowing
 * support for multiple transport implementations (NIO, Epoll, KQueue). Each implementation
 * provides optimized channel and event loop classes for their respective platform.
 * </p>
 *
 * @param <C> the channel type (e.g., NioSocketChannel, EpollSocketChannel)
 * @param <L> the event loop group type (e.g., NioEventLoopGroup, EpollEventLoopGroup)
 */
public interface TransportFactory<C extends Channel, L extends EventLoopGroup> extends ChannelFactory<C> {

  /**
   * Creates a new event loop group for this transport.
   *
   * @param ioThreadsCount the number of I/O threads to create
   * @param threadFactory the factory for creating threads
   * @return a new event loop group instance
   */
  L newEventLoopGroup(int ioThreadsCount, ThreadFactory threadFactory);

}
