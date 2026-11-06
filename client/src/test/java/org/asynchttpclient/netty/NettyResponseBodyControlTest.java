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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import org.asynchttpclient.AsyncHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class NettyResponseBodyControlTest {

    private final NettyResponseFuture<?> future =
            new NettyResponseFuture<>(null, mock(AsyncHandler.class), null, 3, null, null, null);
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger suspensionsStarted = new AtomicInteger();
    private final AtomicInteger suspensionsEnded = new AtomicInteger();
    private final ExecutorService anotherThread = Executors.newSingleThreadExecutor();

    private EventLoopGroup group;
    private Channel channel;

    @BeforeEach
    public void registerChannel() throws InterruptedException {
        group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        channel = new LocalChannel();
        channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) {
                reads.incrementAndGet();
                ctx.read();
            }
        });
        group.register(channel).sync();
    }

    @AfterEach
    public void closeChannel() throws InterruptedException {
        anotherThread.shutdownNow();
        channel.close().sync();
        group.shutdownGracefully(0, 100, MILLISECONDS).sync();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void earlierSuspendFromAnotherThreadDoesNotUndoLaterResume(boolean autoRead) throws Exception {
        NettyResponseBodyControl control = suspendedControl(autoRead);

        onEventLoop(() -> {
            callFromAnotherThread(control::suspend);
            control.resume();
            return null;
        });
        awaitEventLoop();

        assertFalse(NettyResponseBodyControl.isSuspended(future));
        assertEquals(autoRead, channel.config().isAutoRead());
        assertEquals(1, reads.get(), "resuming must request exactly one read");
        assertEquals(1, suspensionsStarted.get());
        assertEquals(1, suspensionsEnded.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void resumeFromAnotherThreadIsNotLostToALaterSuspend(boolean autoRead) throws Exception {
        // This also passes when no queued suspend() is skipped. It fails if a queued resume() is skipped for a later
        // suspend().
        NettyResponseBodyControl control = suspendedControl(autoRead);
        control.resume();
        awaitEventLoop();

        onEventLoop(() -> {
            // A callback buffered a part and decided to suspend. Before it did, a consumer took that part and resumed.
            callFromAnotherThread(control::resume);
            control.suspend();
            return null;
        });
        awaitEventLoop();

        assertFalse(NettyResponseBodyControl.isSuspended(future), "nothing else would resume reads");
        assertEquals(autoRead, channel.config().isAutoRead());
        assertEquals(2, reads.get());
        assertEquals(2, suspensionsStarted.get());
        assertEquals(2, suspensionsEnded.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void queuedCallsFromAnotherThreadEndInTheStateOfTheLatestCall(boolean autoRead) throws Exception {
        NettyResponseBodyControl control = suspendedControl(autoRead);
        control.resume();
        awaitEventLoop();

        onEventLoop(() -> {
            callFromAnotherThread(control::suspend);
            callFromAnotherThread(control::resume);
            callFromAnotherThread(control::suspend);
            return null;
        });
        awaitEventLoop();
        assertTrue(NettyResponseBodyControl.isSuspended(future));
        assertFalse(channel.config().isAutoRead());
        assertEquals(3, suspensionsStarted.get());
        assertEquals(2, suspensionsEnded.get());

        onEventLoop(() -> {
            callFromAnotherThread(control::resume);
            callFromAnotherThread(control::suspend);
            callFromAnotherThread(control::resume);
            return null;
        });
        awaitEventLoop();
        assertFalse(NettyResponseBodyControl.isSuspended(future));
        assertEquals(autoRead, channel.config().isAutoRead());
        assertEquals(3, suspensionsStarted.get(), "the skipped suspend() must not start a suspension");
        assertEquals(3, suspensionsEnded.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void suspendAfterASkippedOneStillTakesEffect(boolean autoRead) throws Exception {
        NettyResponseBodyControl control = suspendedControl(autoRead);
        onEventLoop(() -> {
            callFromAnotherThread(control::suspend);
            control.resume();
            return null;
        });
        awaitEventLoop();
        assertFalse(NettyResponseBodyControl.isSuspended(future));

        control.suspend();
        awaitEventLoop();
        assertTrue(NettyResponseBodyControl.isSuspended(future));
        assertFalse(channel.config().isAutoRead());
    }

    @Test
    public void suspendOnTheEventLoopTakesEffectWhenAResumeClearsTheRequestFirst() {
        // A resume() from another thread runs exactly when suspend() asks whether it is on the event loop, that is,
        // after suspend() has recorded its request and before it acts on it.
        Thread loop = Thread.currentThread();
        Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        AtomicReference<Runnable> onLoopQuery = new AtomicReference<>();
        InterceptedChannel channel = new InterceptedChannel(loop, queued, onLoopQuery);
        NettyResponseBodyControl control = NettyResponseBodyControl.create(future, channel,
                suspensionsStarted::incrementAndGet, suspensionsEnded::incrementAndGet, () -> {
                }, ignored -> {
                });
        try {
            onLoopQuery.set(() -> {
                try {
                    callFromAnotherThread(control::resume);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            control.suspend();
            assertTrue(NettyResponseBodyControl.isSuspended(future), "a suspend() on the event loop must take effect");
            assertEquals(1, suspensionsStarted.get());
            assertFalse(channel.config().isAutoRead());

            // The resume from the other thread was queued, and still takes effect afterwards.
            Runnable task;
            while ((task = queued.poll()) != null) {
                task.run();
            }
            assertFalse(NettyResponseBodyControl.isSuspended(future));
            assertEquals(1, suspensionsEnded.get());
        } finally {
            onLoopQuery.set(null);
            NettyResponseBodyControl.complete(future);
            channel.finishAndReleaseAll();
        }
    }

    private NettyResponseBodyControl suspendedControl(boolean autoRead) throws Exception {
        return onEventLoop(() -> {
            channel.config().setAutoRead(autoRead);
            NettyResponseBodyControl control = NettyResponseBodyControl.create(future, channel,
                    suspensionsStarted::incrementAndGet, suspensionsEnded::incrementAndGet, () -> {
                    }, ignored -> {
                    });
            control.suspend();
            return control;
        });
    }

    private <T> T onEventLoop(Callable<T> task) throws Exception {
        return channel.eventLoop().submit(task).get(5, SECONDS);
    }

    private void awaitEventLoop() throws Exception {
        onEventLoop(() -> null);
    }

    // The calling event loop stays busy until the call returns, so the control has to queue the call behind it.
    private void callFromAnotherThread(Runnable call) throws Exception {
        anotherThread.submit(call).get(5, SECONDS);
    }

    /**
     * An {@link EmbeddedChannel} whose event loop is the given thread. Tasks from other threads are queued, and a hook
     * runs when the event loop thread asks whether it is on the event loop.
     */
    private static final class InterceptedChannel extends EmbeddedChannel {

        private final EventLoop eventLoop;

        InterceptedChannel(Thread loop, Queue<Runnable> queued, AtomicReference<Runnable> onLoopQuery) {
            EventLoop real = super.eventLoop();
            eventLoop = (EventLoop) Proxy.newProxyInstance(EventLoop.class.getClassLoader(),
                    new Class<?>[]{EventLoop.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "inEventLoop":
                                if (Thread.currentThread() == loop) {
                                    Runnable hook = onLoopQuery.getAndSet(null);
                                    if (hook != null) {
                                        hook.run();
                                    }
                                }
                                return (args == null || args.length == 0 ? Thread.currentThread() : args[0]) == loop;
                            case "execute":
                                queued.add((Runnable) args[0]);
                                return null;
                            default:
                                try {
                                    return method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                        }
                    });
        }

        @Override
        public EventLoop eventLoop() {
            return eventLoop != null ? eventLoop : super.eventLoop();
        }
    }
}
