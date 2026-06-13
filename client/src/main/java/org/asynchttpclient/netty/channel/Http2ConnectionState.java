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
package org.asynchttpclient.netty.channel;

import io.netty.util.AttributeKey;
import org.asynchttpclient.netty.NettyResponseFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Tracks per-connection HTTP/2 state: active stream count, max concurrent streams,
 * draining status (from GOAWAY), and pending stream openers.
 */
public class Http2ConnectionState {

    public static final AttributeKey<Http2ConnectionState> HTTP2_STATE_KEY =
            AttributeKey.valueOf("http2ConnectionState");

    /**
     * A request waiting for a free stream slot. Carries both the future (so it can be failed if the
     * connection dies before a slot frees up) and the action that actually opens the stream.
     */
    private static final class PendingOpener {
        final NettyResponseFuture<?> future;
        final Runnable opener;

        PendingOpener(NettyResponseFuture<?> future, Runnable opener) {
            this.future = future;
            this.opener = opener;
        }
    }

    private final AtomicInteger activeStreams = new AtomicInteger(0);
    private volatile int maxConcurrentStreams = Integer.MAX_VALUE;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile int lastGoAwayStreamId = Integer.MAX_VALUE;
    private final ConcurrentLinkedQueue<PendingOpener> pendingOpeners = new ConcurrentLinkedQueue<>();
    private final Object pendingLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Object partitionKey;

    public boolean tryAcquireStream() {
        if (draining.get() || closed.get()) {
            return false;
        }
        while (true) {
            int current = activeStreams.get();
            if (current >= maxConcurrentStreams) {
                return false;
            }
            if (activeStreams.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void releaseStream() {
        activeStreams.decrementAndGet();
        drainPendingOpeners();
    }

    public void addPendingOpener(Runnable opener) {
        addPendingOpener(null, opener);
    }

    public void addPendingOpener(NettyResponseFuture<?> future, Runnable opener) {
        synchronized (pendingLock) {
            if (tryAcquireStream()) {
                opener.run();
            } else {
                pendingOpeners.add(new PendingOpener(future, opener));
            }
        }
    }

    private void drainPendingOpeners() {
        synchronized (pendingLock) {
            PendingOpener pending = pendingOpeners.poll();
            if (pending != null) {
                if (tryAcquireStream()) {
                    pending.opener.run();
                } else {
                    // Put it back — another releaseStream() will pick it up
                    pendingOpeners.offer(pending);
                }
            }
        }
    }

    /**
     * Permanently marks the connection unusable and hands every queued (never-started) request to
     * {@code failer}. After this call {@link #tryAcquireStream()} returns {@code false}, so a request
     * enqueued concurrently with the close is failed by its own caller's post-enqueue active-channel
     * check rather than being silently orphaned.
     * <p>
     * Without this, requests sitting in {@link #pendingOpeners} when the parent connection drops are
     * never completed and survive only until the request timeout fires — the silent-timeout
     * regression of Issue #2160 (a queued request has no stream channel, hence no channelInactive
     * is ever delivered for it).
     *
     * @param failer invoked once per orphaned request future (e.g. to abort it)
     */
    public void failPendingOpeners(Consumer<NettyResponseFuture<?>> failer) {
        closed.set(true);
        List<PendingOpener> drained = new ArrayList<>();
        synchronized (pendingLock) {
            PendingOpener p;
            while ((p = pendingOpeners.poll()) != null) {
                drained.add(p);
            }
        }
        // Fail outside the lock — failer may re-enter client code.
        for (PendingOpener p : drained) {
            if (p.future != null) {
                failer.accept(p.future);
            }
        }
    }

    public void updateMaxConcurrentStreams(int maxConcurrentStreams) {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    public int getActiveStreams() {
        return activeStreams.get();
    }

    public boolean isDraining() {
        return draining.get();
    }

    public void setDraining(int lastStreamId) {
        this.lastGoAwayStreamId = lastStreamId;
        this.draining.set(true);
    }

    public int getLastGoAwayStreamId() {
        return lastGoAwayStreamId;
    }

    public void setPartitionKey(Object partitionKey) {
        this.partitionKey = partitionKey;
    }

    public Object getPartitionKey() {
        return partitionKey;
    }
}
