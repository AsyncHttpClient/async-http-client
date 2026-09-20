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

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Map;
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

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void throwingWaiterDoesNotStarveOthersOnRegistration() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        Channel channel = new EmbeddedChannel();
        try {
            AtomicBoolean survivorWoken = new AtomicBoolean();
            cm.addHttp2ConnectionWaiter(KEY, c -> {
                throw new RuntimeException("waiter blew up");
            });
            cm.addHttp2ConnectionWaiter(KEY, c -> survivorWoken.set(true));

            // Must not propagate the throwing waiter's exception: registerHttp2Connection runs on the
            // establishing connection's onSuccess path, which still has to release its permit and write.
            cm.registerHttp2Connection(KEY, channel);

            assertTrue(survivorWoken.get(), "a throwing waiter must not starve the other waiters for the key");
        } finally {
            channel.close();
            cm.close();
            timer.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void throwingWaiterDoesNotStarveOthersOnClose() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        try {
            AtomicBoolean survivorFailed = new AtomicBoolean();
            cm.addHttp2ConnectionWaiter(KEY, c -> {
                throw new RuntimeException("waiter blew up");
            });
            cm.addHttp2ConnectionWaiter(KEY, c -> survivorFailed.set(true));

            cm.close(); // must isolate the throwing waiter and still fail the rest

            assertTrue(survivorFailed.get(), "a throwing waiter must not starve the other waiters on close");
        } finally {
            timer.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void waiterAddedAfterCloseFailsClosed() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        try {
            cm.close(); // sweeps pending waiters and latches the closed state

            AtomicBoolean woken = new AtomicBoolean();
            boolean registered = cm.addHttp2ConnectionWaiter(KEY, c -> woken.set(true));

            assertFalse(registered, "adding a waiter after close must fail-closed so the caller fails its request "
                    + "instead of arming a timeout that never fires");
            assertFalse(woken.get(), "a fail-closed add must not itself invoke the waiter");
        } finally {
            timer.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void removingLastWaiterPrunesEmptyEntry() throws Exception {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        try {
            Consumer<Channel> waiter = c -> {
            };
            cm.addHttp2ConnectionWaiter(KEY, waiter);
            cm.removeHttp2ConnectionWaiter(KEY, waiter);

            assertTrue(waiterMap(cm).isEmpty(),
                    "removing the last waiter for a key must prune the empty set, not retain it for the client's lifetime");
        } finally {
            cm.close();
            timer.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void http2UnavailableWakesWaitersWithNull() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        try {
            AtomicReference<Channel> woken = new AtomicReference<>();
            AtomicBoolean called = new AtomicBoolean();
            cm.addHttp2ConnectionWaiter(KEY, c -> {
                woken.set(c);
                called.set(true);
            });

            cm.http2Unavailable(KEY);

            assertTrue(called.get(), "a handshake that settled on HTTP/1.1 must release the waiter");
            assertNull(woken.get(), "the waiter must be failed, not handed a connection");
        } finally {
            cm.close();
            timer.stop();
        }
    }

    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void http2UnavailableIsStickyUntilHttp2Registers() {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        Channel channel = new EmbeddedChannel();
        try {
            assertFalse(cm.isHttp2KnownUnavailable(KEY));

            cm.http2Unavailable(KEY);
            assertTrue(cm.isHttp2KnownUnavailable(KEY),
                    "the mark must outlive the handshake that set it, or a later waiter arms into an empty set");

            cm.registerHttp2Connection(KEY, channel);
            assertFalse(cm.isHttp2KnownUnavailable(KEY), "a registration must clear the mark");
        } finally {
            channel.close();
            cm.close();
            timer.stop();
        }
    }

    /**
     * ALPN is per-connection, so one IP of a host settling on HTTP/1.1 says nothing about the others. The
     * mark is keyed by host, so it must not be set while a sibling that did negotiate HTTP/2 is registered.
     */
    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void http2UnavailableDefersToARegisteredSibling() throws Exception {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        Channel sibling = new EmbeddedChannel();
        try {
            cm.registerHttp2Connection(new RoundRobinPartitionKey(KEY, InetAddress.getByName("127.0.0.1")), sibling);
            AtomicBoolean called = new AtomicBoolean();
            cm.addHttp2ConnectionWaiter(KEY, c -> called.set(true));

            cm.http2Unavailable(new RoundRobinPartitionKey(KEY, InetAddress.getByName("127.0.0.2")));

            assertFalse(cm.isHttp2KnownUnavailable(KEY), "a live sibling must keep the host usable");
            assertFalse(called.get(), "the waiter can still multiplex onto the sibling, so it must stay parked");
        } finally {
            sibling.close();
            cm.close();
            timer.stop();
        }
    }

    /**
     * The mark is stored under the base key, so a per-IP key marks the host. Without that collapse a waiter
     * parked on the host key would never see it.
     */
    @Test
    @Timeout(unit = TimeUnit.SECONDS, value = 30)
    public void http2UnavailableOnOneIpMarksTheWholeHost() throws Exception {
        Timer timer = new HashedWheelTimer();
        ChannelManager cm = newChannelManager(timer);
        try {
            cm.http2Unavailable(new RoundRobinPartitionKey(KEY, InetAddress.getByName("127.0.0.1")));
            assertTrue(cm.isHttp2KnownUnavailable(KEY));
        } finally {
            cm.close();
            timer.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, ?> waiterMap(ChannelManager cm) throws Exception {
        Field field = ChannelManager.class.getDeclaredField("http2ConnectionWaiters");
        field.setAccessible(true);
        return (Map<Object, ?>) field.get(cm);
    }
}
