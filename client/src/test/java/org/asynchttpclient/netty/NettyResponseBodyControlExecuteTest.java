/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import org.asynchttpclient.AsyncHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

public class NettyResponseBodyControlExecuteTest {

    @Test
    public void executeDoesNotRunTheTaskOnceTheEventLoopRejectsIt() throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        Channel channel = new LocalChannel();
        group.register(channel).sync();
        NettyResponseFuture<?> future =
                new NettyResponseFuture<>(null, mock(AsyncHandler.class), null, 3, null, null, null);
        NettyResponseBodyControl control = channel.eventLoop().submit(() -> NettyResponseBodyControl.create(
                future, channel, () -> {
                }, ignored -> {
                })).get(5, SECONDS);
        channel.close().sync();
        group.shutdownGracefully(0, 0, SECONDS).sync();

        AtomicBoolean ran = new AtomicBoolean();
        assertDoesNotThrow(() -> control.execute(() -> ran.set(true)));
        assertFalse(ran.get());
    }
}
