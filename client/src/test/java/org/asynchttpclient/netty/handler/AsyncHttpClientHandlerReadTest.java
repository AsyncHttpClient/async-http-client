/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the read behaviour of {@link AsyncHttpClientHandler#channelActive} and
 * {@link AsyncHttpClientHandler#channelReadComplete}.
 * <p>
 * Netty's {@code HeadContext} already calls {@code Channel#read()} after firing either event whenever
 * autoRead is on, so a read requested from the handler as well doubles the outbound traversal and
 * {@code doBeginRead} for every read cycle. That duplication is invisible to the functional suites — the
 * connection works either way — so it needs pinning here.
 * <p>
 * The counting handler goes in front of the handler under test so that it sees both entry points: the
 * {@code ctx.read()} the handler issues itself, and the {@code Channel#read()} that HeadContext drives in
 * from the tail. {@link EmbeddedChannel} fires channelActive while it registers and its {@code doBeginRead}
 * is a no-op, so counting in the pipeline is the only way to observe either.
 */
class AsyncHttpClientHandlerReadTest {

    @Test
    void readsOncePerCycleWhenAutoReadIsOn() {
        AtomicInteger reads = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(readCounter(reads), handler());

        // channelActive fired during registration; HeadContext requested the read, the handler must not have.
        assertEquals(1, reads.get());

        reads.set(0);
        channel.pipeline().fireChannelReadComplete();
        assertEquals(1, reads.get());
    }

    @Test
    void stillReadsWhenAutoReadIsOff() {
        AtomicInteger reads = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(readCounter(reads), handler());
        channel.config().setAutoRead(false);

        // With autoRead off HeadContext requests nothing, so the handler is the only thing keeping the
        // connection from stalling.
        reads.set(0);
        channel.pipeline().fireChannelReadComplete();
        assertEquals(1, reads.get());
    }

    private static ChannelHandler readCounter(AtomicInteger reads) {
        return new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) {
                reads.incrementAndGet();
                ctx.read();
            }
        };
    }

    private static AsyncHttpClientHandler handler() {
        return new AsyncHttpClientHandler(new DefaultAsyncHttpClientConfig.Builder().build(), null, null) {
            @Override
            public void handleRead(Channel channel, NettyResponseFuture<?> future, Object message) {
            }

            @Override
            public void handleException(NettyResponseFuture<?> future, Throwable error) {
            }

            @Override
            public void handleChannelInactive(NettyResponseFuture<?> future) {
            }
        };
    }
}
