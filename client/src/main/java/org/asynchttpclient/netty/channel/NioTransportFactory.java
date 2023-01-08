/*
 *    Copyright (c) 2019-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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
