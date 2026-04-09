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

import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueSocketChannel;

import java.util.concurrent.ThreadFactory;

class KQueueTransportFactory implements TransportFactory<KQueueSocketChannel, KQueueEventLoopGroup> {

    static boolean isAvailable() {
        try {
            Class.forName("io.netty.channel.kqueue.KQueue");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return KQueue.isAvailable();
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
