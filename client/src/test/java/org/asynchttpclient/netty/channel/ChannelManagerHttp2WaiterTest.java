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
package org.asynchttpclient.netty.channel;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HTTP/2 connection-waiter registry {@link ChannelManager} exposes for NettyRequestSender's
 * non-blocking deferral: a request parked (off the event loop, without blocking its caller thread) after
 * failing to acquire a connection permit is woken when a matching HTTP/2 connection registers, and is failed
 * (invoked with {@code null}) when the client closes.
 */
public class ChannelManagerHttp2WaiterTest {

    private static final Object KEY = "partition-key";

    private static ChannelManager newChannelManager(Timer timer) {
        return new ChannelManager(new DefaultAsyncHttpClientConfig.Builder().build(), timer);
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void waiterWokenWithChannelOnRegistration() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        Channel channel = new EmbeddedChannel();
        try {
            AtomicReference<Channel> woken = new AtomicReference<>();
            AtomicBoolean called = new AtomicBoolean();
            Consumer<Channel> waiter = c -> {
                woken.set(c);
                called.set(true);
            };

            cm.addHttp2ConnectionWaiter(KEY, waiter);
            cm.registerHttp2Connection(KEY, channel);

            assertTrue(called.get(), "waiter must be woken when a connection registers for its key");
            assertSame(channel, woken.get(), "waiter must be woken with the registered connection");
        } finally {
            channel.close();
            cm.close();
            timer.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void waiterForOtherKeyNotWoken() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        Channel channel = new EmbeddedChannel();
        try {
            AtomicBoolean called = new AtomicBoolean();
            cm.addHttp2ConnectionWaiter(KEY, c -> called.set(true));
            cm.registerHttp2Connection("a-different-key", channel);
            assertFalse(called.get(), "a waiter must not be woken by a connection for a different key");
        } finally {
            channel.close();
            cm.close();
            timer.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void removedWaiterNotWoken() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        Channel channel = new EmbeddedChannel();
        try {
            AtomicBoolean called = new AtomicBoolean();
            Consumer<Channel> waiter = c -> called.set(true);
            cm.addHttp2ConnectionWaiter(KEY, waiter);
            cm.removeHttp2ConnectionWaiter(KEY, waiter);
            cm.registerHttp2Connection(KEY, channel);
            assertFalse(called.get(), "a removed waiter must not be woken");
        } finally {
            channel.close();
            cm.close();
            timer.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void waiterFailedWithNullOnClose() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        try {
            AtomicBoolean called = new AtomicBoolean();
            AtomicReference<Channel> arg = new AtomicReference<>();
            Consumer<Channel> waiter = c -> {
                arg.set(c);
                called.set(true);
            };
            cm.addHttp2ConnectionWaiter(KEY, waiter);

            cm.close(); // must fail pending waiters synchronously, before the async EventLoopGroup shutdown

            assertTrue(called.get(), "close must invoke pending waiters");
            assertNull(arg.get(), "close must invoke pending waiters with null (the abort signal)");
        } finally {
            timer.stop();
        }
    }
}
