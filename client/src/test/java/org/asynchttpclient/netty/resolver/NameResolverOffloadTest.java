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
package org.asynchttpclient.netty.resolver;

import io.netty.channel.DefaultEventLoop;
import io.netty.resolver.DefaultNameResolver;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.RequestBuilderBase.DEFAULT_NAME_RESOLVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameResolverOffloadTest {

    private final DefaultEventLoop eventLoop = new DefaultEventLoop();

    @AfterEach
    void closeEventLoop() {
        eventLoop.shutdownGracefully(0, 5, SECONDS).syncUninterruptibly();
    }

    @Test
    void offloadsDefaultResolverToConfiguredWorker() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setThreadPoolName("ahc-resolver-unit")
                .setFallbackNameResolverOffloadThreadsCount(1)
                .setFallbackNameResolverOffloadQueueSize(1)
                .build();

        try (NameResolverOffload offload = new NameResolverOffload(config)) {
            assertTrue(offload.shouldOffload(DEFAULT_NAME_RESOLVER));
            assertFalse(offload.shouldOffload(new DefaultNameResolver(ImmediateEventExecutor.INSTANCE)));

            Promise<Void> promise = ImmediateEventExecutor.INSTANCE.newPromise();
            AtomicReference<String> threadName = new AtomicReference<>();
            offload.execute(eventLoop, promise, () -> {
                threadName.set(Thread.currentThread().getName());
                offload.completeSuccess(eventLoop, promise, null);
            });

            promise.get(5, SECONDS);
            assertTrue(threadName.get().contains("ahc-resolver-unit-resolver"));
        }
    }

    @Test
    void disabledOffloadDoesNotSelectDefaultResolver() {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFallbackNameResolverOffloadEnabled(false)
                .build();

        try (NameResolverOffload offload = new NameResolverOffload(config)) {
            assertFalse(offload.shouldOffload(DEFAULT_NAME_RESOLVER));
        }
    }

    @Test
    void boundedQueueRejectsOverflowAndCloseFailsPendingWork() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFallbackNameResolverOffloadThreadsCount(1)
                .setFallbackNameResolverOffloadQueueSize(1)
                .build();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try (NameResolverOffload offload = new NameResolverOffload(config)) {
            Promise<Void> running = ImmediateEventExecutor.INSTANCE.newPromise();
            Promise<Void> queued = ImmediateEventExecutor.INSTANCE.newPromise();
            Promise<Void> rejected = ImmediateEventExecutor.INSTANCE.newPromise();

            offload.execute(eventLoop, running, () -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                    offload.completeSuccess(eventLoop, running, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(5, SECONDS));

            offload.execute(eventLoop, queued, () -> offload.completeSuccess(eventLoop, queued, null));
            offload.execute(eventLoop, rejected, () -> offload.completeSuccess(eventLoop, rejected, null));

            ExecutionException overflow = assertThrows(ExecutionException.class, () -> rejected.get(5, SECONDS));
            assertInstanceOf(RejectedExecutionException.class, overflow.getCause());

            offload.close();
            assertRejected(running);
            assertRejected(queued);
        }
    }

    private static void assertRejected(Promise<Void> promise) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> promise.get(5, SECONDS));
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        assertEquals("Fallback name resolver offload is closed", failure.getCause().getMessage());
    }
}
