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

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringSocketChannel;

import java.util.concurrent.ThreadFactory;

class IoUringTransportFactory implements TransportFactory<IoUringSocketChannel, MultiThreadIoEventLoopGroup> {

    static boolean isAvailable() {
        try {
            Class.forName("io.netty.channel.uring.IoUring");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return IoUring.isAvailable();
    }

    @Override
    public IoUringSocketChannel newChannel() {
        return new IoUringSocketChannel();
    }

    @Override
    public MultiThreadIoEventLoopGroup newEventLoopGroup(int ioThreadsCount, ThreadFactory threadFactory) {
        return new MultiThreadIoEventLoopGroup(ioThreadsCount, threadFactory, IoUringIoHandler.newFactory());
    }
}
