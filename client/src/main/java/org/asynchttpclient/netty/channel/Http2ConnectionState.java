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
import java.util.concurrent.atomic.AtomicReference;
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
    // The effective cap on concurrently open client-initiated streams is the MIN of the client's
    // configured limit and the server-advertised SETTINGS_MAX_CONCURRENT_STREAMS (RFC 9113 §5.1.2 — a
    // peer may not exceed the limit the other side advertises). Both are tracked so a later server
    // SETTINGS frame can't silently raise the client's own configured ceiling.
    private volatile int clientMaxConcurrentStreams = Integer.MAX_VALUE;
    private volatile int serverMaxConcurrentStreams = Integer.MAX_VALUE;
    private volatile int maxConcurrentStreams = Integer.MAX_VALUE;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile int lastGoAwayStreamId = Integer.MAX_VALUE;
    // Hard cap on requests queued waiting for a free stream slot. A peer that accepts the connection but never
    // grants slots — SETTINGS_MAX_CONCURRENT_STREAMS=0, or a small limit with streams it never completes — would
    // otherwise make every subsequent request queue forever, each pinning a NettyResponseFuture and its request
    // body buffer, until the client OOMs. Past the cap, offerPendingOpener rejects and the caller fails the
    // request fast. All queue mutations happen under pendingLock, so pendingCount tracks size in O(1)
    // (ConcurrentLinkedQueue.size() is O(n)).
    private static final int MAX_PENDING_OPENERS = 10_000;
    private final ConcurrentLinkedQueue<PendingOpener> pendingOpeners = new ConcurrentLinkedQueue<>();
    private int pendingCount;
    private final Object pendingLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // Set when this connection lost the per-partition registration race (thundering herd): it is not in
    // the registry, so it only ever serves its own opening request, then closes once its last stream ends
    // — rather than lingering open and unregistered. Distinct from draining: it does NOT block its own
    // opening request from acquiring a slot.
    private final AtomicBoolean redundant = new AtomicBoolean(false);
    private volatile Object partitionKey;
    // Releases the per-host permit this connection holds in ROUND_ROBIN mode (installed by
    // NettyConnectListener, absent in DEFAULT mode). The GOAWAY handler and the channel closeFuture race
    // to fire it; getAndSet(null) makes it run at most once, since a double release would push the
    // semaphore above maxConnectionsPerHost.
    private final AtomicReference<Runnable> permitRelease = new AtomicReference<>();

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

    /**
     * Binary-compatibility wrapper for the {@code void addPendingOpener(Runnable)} member released in 3.0.9
     * (descriptor {@code ()V}); the branch's change to a {@code boolean} return was binary-incompatible. This
     * restores the original member. Delegates to {@link #offerPendingOpener(Runnable)}, ignoring the result;
     * internal callers that must observe the draining/closed rejection (the GOAWAY-orphan fix) use
     * {@code offerPendingOpener} directly.
     */
    public void addPendingOpener(Runnable opener) {
        offerPendingOpener(opener);
    }

    public boolean offerPendingOpener(Runnable opener) {
        return offerPendingOpener(null, opener);
    }

    /**
     * Runs {@code opener} immediately if a stream slot is free, otherwise queues it for a later
     * {@link #releaseStream()}. Returns {@code false} — <em>without</em> queuing — when the connection is
     * already draining or closed, or when the pending queue is already at {@link #MAX_PENDING_OPENERS}: in
     * each case the caller MUST fail the request itself rather than let it sit until the request timeout fires
     * (Issue #2160). A draining/closed connection never runs a queued opener ({@link #drainPendingOpeners} only
     * re-offers it, and {@link #failPendingOpeners} has already drained the queue); a full queue means the peer
     * is starving slots and the request would otherwise grow heap without bound.
     * <p>
     * Race-free against {@link #failPendingOpeners}: that method sets {@code closed} and drains the queue under
     * {@code pendingLock}. An opener enqueued before the drain runs is caught by the drain; an enqueue attempt
     * sequenced after it observes {@code closed} here (the lock provides the happens-before) and is rejected.
     * Either way no opener is left stranded.
     *
     * @return {@code true} if the opener was run inline or queued; {@code false} if rejected because the
     *         connection is draining/closed or the pending queue is full (caller must fail the request)
     */
    public boolean offerPendingOpener(NettyResponseFuture<?> future, Runnable opener) {
        synchronized (pendingLock) {
            if (draining.get() || closed.get()) {
                return false;
            }
            if (tryAcquireStream()) {
                opener.run();
            } else {
                if (pendingCount >= MAX_PENDING_OPENERS) {
                    return false;
                }
                pendingOpeners.add(new PendingOpener(future, opener));
                pendingCount++;
            }
            return true;
        }
    }

    private void drainPendingOpeners() {
        synchronized (pendingLock) {
            // Open as many queued requests as there are now-free stream slots. A single stream completion
            // frees exactly one slot (so this usually runs one opener), but a SETTINGS frame that RAISES
            // SETTINGS_MAX_CONCURRENT_STREAMS frees several at once — drain them all here rather than waking
            // only one and stalling the rest until the next completion (a missed-wakeup; the Issue #2160
            // silent-timeout class). tryAcquireStream() enforces the cap and the draining/closed gate, so
            // this never over-opens; every poll is under pendingLock, so a non-empty queue always yields a
            // non-null opener.
            while (!pendingOpeners.isEmpty() && tryAcquireStream()) {
                pendingCount--;
                pendingOpeners.poll().opener.run();
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
        List<PendingOpener> drained = new ArrayList<>();
        synchronized (pendingLock) {
            // Set closed UNDER pendingLock, before draining: an offerPendingOpener that already holds the lock
            // finishes its enqueue and is caught by the drain below; one that has not yet acquired the lock
            // observes closed==true (lock happens-before) and is rejected. Setting it outside the lock would
            // leave the invariant the offerPendingOpener javadoc relies on resting on luck, not the lock.
            closed.set(true);
            PendingOpener p;
            while ((p = pendingOpeners.poll()) != null) {
                drained.add(p);
            }
            pendingCount = 0;
        }
        // Fail outside the lock — failer may re-enter client code.
        for (PendingOpener p : drained) {
            if (p.future != null) {
                failer.accept(p.future);
            }
        }
    }

    /**
     * Sets the client's own configured cap (from {@code config.getHttp2MaxConcurrentStreams()}). The
     * effective limit becomes the min of this and any server-advertised value.
     */
    public void setClientMaxConcurrentStreams(int clientMaxConcurrentStreams) {
        this.clientMaxConcurrentStreams = clientMaxConcurrentStreams;
        recomputeMaxConcurrentStreams();
    }

    /**
     * Applies the server-advertised SETTINGS_MAX_CONCURRENT_STREAMS. The effective limit is the min of
     * this and the client's configured cap — a server raising its limit never overrides the client's.
     */
    public void updateMaxConcurrentStreams(int serverMaxConcurrentStreams) {
        int previous = this.maxConcurrentStreams;
        this.serverMaxConcurrentStreams = serverMaxConcurrentStreams;
        recomputeMaxConcurrentStreams();
        // If the effective cap ROSE (the server raised SETTINGS_MAX_CONCURRENT_STREAMS), the newly-available
        // slots must wake queued openers now. Otherwise they wait for the next stream completion to call
        // drainPendingOpeners — and if no in-flight stream completes (e.g. all are long-lived), the queue
        // stalls until the request timeout fires (a missed-wakeup, the Issue #2160 silent-timeout class).
        if (this.maxConcurrentStreams > previous) {
            drainPendingOpeners();
        }
    }

    private void recomputeMaxConcurrentStreams() {
        this.maxConcurrentStreams = Math.min(clientMaxConcurrentStreams, serverMaxConcurrentStreams);
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

    /** Marks this connection as a redundant duplicate that should close once its last stream ends. */
    public void markRedundant() {
        redundant.set(true);
    }

    public boolean isRedundant() {
        return redundant.get();
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

    /**
     * Installs the action that releases this connection's per-host permit (round-robin mode only).
     * {@link #releasePermitOnce()} runs it at most once.
     */
    public void setPermitRelease(Runnable release) {
        permitRelease.set(release);
    }

    /**
     * Runs the installed permit-release action the first time it is called; later calls do nothing, as do
     * calls when no action was installed (DEFAULT mode). Fired at drain start (GOAWAY) and on channel
     * close, whichever comes first.
     */
    public void releasePermitOnce() {
        Runnable release = permitRelease.getAndSet(null);
        if (release != null) {
            release.run();
        }
    }
}
