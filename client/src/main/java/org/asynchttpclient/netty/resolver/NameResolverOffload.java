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

import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;
import static org.asynchttpclient.RequestBuilderBase.DEFAULT_NAME_RESOLVER;

/**
 * Offloads the default blocking fallback name resolver and tracks its pending promises for client shutdown.
 */
public final class NameResolverOffload implements AutoCloseable {

    private final @Nullable ExecutorService executor;
    private final Map<Promise<?>, EventExecutor> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public NameResolverOffload(AsyncHttpClientConfig config) {
        requireNonNull(config, "config");
        if (!config.isFallbackNameResolverOffloadEnabled()) {
            executor = null;
            return;
        }

        int threads = configuredThreads(config);
        int queueSize = configuredQueueSize(config, threads);
        ThreadFactory threadFactory = config.getThreadFactory() != null
                ? config.getThreadFactory()
                : new DefaultThreadFactory(config.getThreadPoolName() + "-resolver");
        executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueSize), threadFactory);
    }

    public boolean shouldOffload(NameResolver<InetAddress> nameResolver) {
        return executor != null && nameResolver == DEFAULT_NAME_RESOLVER;
    }

    public void execute(EventExecutor eventLoop, Promise<?> promise, Runnable task) {
        requireNonNull(eventLoop, "eventLoop");
        requireNonNull(promise, "promise");
        requireNonNull(task, "task");

        ExecutorService resolverExecutor = executor;
        if (resolverExecutor == null) {
            completeFailure(eventLoop, promise,
                    new RejectedExecutionException("Fallback name resolver offload is disabled"));
            return;
        }

        pending.put(promise, eventLoop);
        promise.addListener(ignored -> pending.remove(promise));

        if (closed.get()) {
            completeFailure(eventLoop, promise, closedFailure());
            return;
        }

        try {
            resolverExecutor.execute(() -> {
                if (promise.isDone()) {
                    return;
                }
                try {
                    task.run();
                } catch (RuntimeException e) {
                    completeFailure(eventLoop, promise, e);
                }
            });
        } catch (RejectedExecutionException e) {
            completeFailure(eventLoop, promise, e);
        }
    }

    public <T> void completeSuccess(EventExecutor eventLoop, Promise<T> promise, T result) {
        if (eventLoop.inEventLoop()) {
            promise.trySuccess(result);
            return;
        }
        try {
            eventLoop.execute(() -> promise.trySuccess(result));
        } catch (RejectedExecutionException e) {
            promise.tryFailure(e);
        }
    }

    public void completeFailure(EventExecutor eventLoop, Promise<?> promise, Throwable cause) {
        if (eventLoop.inEventLoop()) {
            promise.tryFailure(cause);
            return;
        }
        try {
            eventLoop.execute(() -> promise.tryFailure(cause));
        } catch (RejectedExecutionException e) {
            promise.tryFailure(cause);
        }
    }

    @Override
    public void close() {
        ExecutorService resolverExecutor = executor;
        if (resolverExecutor == null || !closed.compareAndSet(false, true)) {
            return;
        }

        resolverExecutor.shutdownNow();
        RejectedExecutionException failure = closedFailure();
        pending.forEach((promise, eventLoop) -> completeFailure(eventLoop, promise, failure));
    }

    private static int configuredThreads(AsyncHttpClientConfig config) {
        int threads = config.getFallbackNameResolverOffloadThreadsCount();
        if (threads <= 0) {
            threads = config.getIoThreadsCount();
        }
        return Math.max(1, threads);
    }

    private static int configuredQueueSize(AsyncHttpClientConfig config, int threads) {
        int queueSize = config.getFallbackNameResolverOffloadQueueSize();
        if (queueSize <= 0) {
            queueSize = threads > Integer.MAX_VALUE / 16 ? Integer.MAX_VALUE : Math.max(1024, threads * 16);
        }
        return Math.max(1, queueSize);
    }

    private static RejectedExecutionException closedFailure() {
        return new RejectedExecutionException("Fallback name resolver offload is closed");
    }
}
