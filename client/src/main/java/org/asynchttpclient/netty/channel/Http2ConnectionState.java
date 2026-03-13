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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks per-connection HTTP/2 state: active stream count, max concurrent streams,
 * draining status (from GOAWAY), and pending stream openers.
 */
public class Http2ConnectionState {

    public static final AttributeKey<Http2ConnectionState> HTTP2_STATE_KEY =
            AttributeKey.valueOf("http2ConnectionState");

    private final AtomicInteger activeStreams = new AtomicInteger(0);
    private volatile int maxConcurrentStreams = Integer.MAX_VALUE;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile int lastGoAwayStreamId = Integer.MAX_VALUE;
    private final ConcurrentLinkedQueue<Runnable> pendingOpeners = new ConcurrentLinkedQueue<>();

    public boolean tryAcquireStream() {
        if (draining.get()) {
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
        // Try to dequeue and run a pending opener
        Runnable pending = pendingOpeners.poll();
        if (pending != null) {
            pending.run();
        }
    }

    public void addPendingOpener(Runnable opener) {
        pendingOpeners.add(opener);
        // Re-check in case a stream was released between the failed tryAcquire and this enqueue
        if (tryAcquireStream()) {
            Runnable dequeued = pendingOpeners.poll();
            if (dequeued != null) {
                dequeued.run();
            } else {
                releaseStream();
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
}
