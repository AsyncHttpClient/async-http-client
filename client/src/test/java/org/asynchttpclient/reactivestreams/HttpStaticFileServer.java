/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.reactivestreams;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpStaticFileServer {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpStaticFileServer.class);

  static private EventLoopGroup bossGroup;
  static private EventLoopGroup workerGroup;

  public static void start(int port) throws Exception {
    bossGroup = new NioEventLoopGroup(1);
    workerGroup = new NioEventLoopGroup();
    ServerBootstrap b = new ServerBootstrap();
    b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .handler(new LoggingHandler(LogLevel.INFO))
            .childHandler(new HttpStaticFileServerInitializer());

    b.bind(port).sync().channel();
    LOGGER.info("Open your web browser and navigate to " + ("http") + "://localhost:" + port + '/');
  }

  public static void shutdown() {
    Future<?> bossFuture = bossGroup.shutdownGracefully();
    Future<?> workerFuture = workerGroup.shutdownGracefully();
    try {
      bossFuture.await();
      workerFuture.await();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
