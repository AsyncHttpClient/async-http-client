/*
 *    Copyright (c) 2022-2023 AsyncHttpClient Project. All rights reserved.
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

import io.netty.incubator.channel.uring.IOUring;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringSocketChannel;

import java.util.concurrent.ThreadFactory;

class IoUringIncubatorTransportFactory implements TransportFactory<IOUringSocketChannel, IOUringEventLoopGroup> {

    static boolean isAvailable() {
        try {
            Class.forName("io.netty.incubator.channel.uring.IOUring");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return IOUring.isAvailable();
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
