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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Http2ConnectionState} verifying stream semaphore lifecycle,
 * pending opener behavior, draining state, and thread safety under concurrent access.
 */
public class Http2ConnectionStateTest {

    // -------------------------------------------------------------------------
    // Basic tryAcquireStream / releaseStream lifecycle
    // -------------------------------------------------------------------------

    @Test
    public void initialActiveStreamsIsZero() {
        Http2ConnectionState state = new Http2ConnectionState();
        assertEquals(0, state.getActiveStreams());
    }

    @Test
    public void acquireStreamIncrementsCount() {
        Http2ConnectionState state = new Http2ConnectionState();
        assertTrue(state.tryAcquireStream());
        assertEquals(1, state.getActiveStreams());
    }

    @Test
    public void releaseStreamDecrementsCount() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.tryAcquireStream();
        assertEquals(1, state.getActiveStreams());

        state.releaseStream();
        assertEquals(0, state.getActiveStreams());
    }

    @Test
    public void acquireAndReleaseMultipleStreams() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(10);

        for (int i = 0; i < 5; i++) {
            assertTrue(state.tryAcquireStream());
        }
        assertEquals(5, state.getActiveStreams());

        for (int i = 0; i < 5; i++) {
            state.releaseStream();
        }
        assertEquals(0, state.getActiveStreams());
    }

    @Test
    public void acquireUpToMaxConcurrentStreams() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(3);

        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertFalse(state.tryAcquireStream(), "Should not acquire beyond maxConcurrentStreams");
        assertEquals(3, state.getActiveStreams());
    }

    @Test
    public void acquireFailsAtLimit() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);

        assertTrue(state.tryAcquireStream());
        assertFalse(state.tryAcquireStream());
        assertEquals(1, state.getActiveStreams());
    }

    @Test
    public void releaseAllowsSubsequentAcquire() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);

        assertTrue(state.tryAcquireStream());
        assertFalse(state.tryAcquireStream());

        state.releaseStream();
        assertTrue(state.tryAcquireStream());
        assertEquals(1, state.getActiveStreams());
    }

    // -------------------------------------------------------------------------
    // Stream semaphore leak simulation (Bug 1 scenario)
    // -------------------------------------------------------------------------

    @Test
    public void streamSlotReleasedOnSimulatedOpenFailure() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(2);

        // Acquire a stream slot (simulating tryAcquireStream() in writeHttp2Request)
        assertTrue(state.tryAcquireStream());
        assertEquals(1, state.getActiveStreams());

        // Simulate openHttp2Stream() failure — release must be called explicitly
        state.releaseStream();
        assertEquals(0, state.getActiveStreams());

        // Verify connection is not starved — we can still acquire
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertEquals(2, state.getActiveStreams());
    }

    @Test
    public void cumulativeLeaksStarveConnection() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(3);

        // Simulate 3 leaked stream slots (acquire without release)
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());

        // Connection is completely blocked
        assertFalse(state.tryAcquireStream());
        assertEquals(3, state.getActiveStreams());
    }

    @Test
    public void releaseAfterLeakedSlotsRecoverConnection() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(2);

        // Simulate 2 leaks
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertFalse(state.tryAcquireStream());

        // Fix the leak by releasing both
        state.releaseStream();
        state.releaseStream();
        assertEquals(0, state.getActiveStreams());

        // Connection is recovered
        assertTrue(state.tryAcquireStream());
    }

    // -------------------------------------------------------------------------
    // Draining state (GOAWAY handling)
    // -------------------------------------------------------------------------

    @Test
    public void acquireFailsWhenDraining() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.setDraining(100);

        assertTrue(state.isDraining());
        assertFalse(state.tryAcquireStream(), "Should not acquire stream on draining connection");
    }

    @Test
    public void drainingPreservesExistingStreamCount() {
        Http2ConnectionState state = new Http2ConnectionState();
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertEquals(2, state.getActiveStreams());

        state.setDraining(10);
        assertTrue(state.isDraining());
        assertEquals(2, state.getActiveStreams(), "Existing streams should not be affected by draining");

        // But new acquisitions should fail
        assertFalse(state.tryAcquireStream());
    }

    @Test
    public void drainingStoresLastStreamId() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.setDraining(42);

        assertEquals(42, state.getLastGoAwayStreamId());
        assertTrue(state.isDraining());
    }

    @Test
    public void releaseStreamWhileDraining() {
        Http2ConnectionState state = new Http2ConnectionState();
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());

        state.setDraining(10);
        state.releaseStream();
        assertEquals(1, state.getActiveStreams());

        state.releaseStream();
        assertEquals(0, state.getActiveStreams());
    }

    // -------------------------------------------------------------------------
    // Pending openers (Bug 4 fix validation)
    // -------------------------------------------------------------------------

    @Test
    public void addPendingOpenerRunsImmediatelyWhenSlotAvailable() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(5);

        AtomicInteger executionCount = new AtomicInteger(0);
        state.addPendingOpener(executionCount::incrementAndGet);

        assertEquals(1, executionCount.get(), "Opener should run immediately when slot available");
        assertEquals(1, state.getActiveStreams(), "Stream should be acquired for the opener");
    }

    @Test
    public void addPendingOpenerQueuesWhenNoSlotAvailable() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);

        // Fill all slots
        assertTrue(state.tryAcquireStream());
        assertEquals(1, state.getActiveStreams());

        AtomicInteger executionCount = new AtomicInteger(0);
        state.addPendingOpener(executionCount::incrementAndGet);

        assertEquals(0, executionCount.get(), "Opener should be queued, not executed");
        assertEquals(1, state.getActiveStreams());
    }

    @Test
    public void pendingOpenerRunsOnRelease() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);

        // Fill the single slot
        assertTrue(state.tryAcquireStream());

        AtomicInteger executionCount = new AtomicInteger(0);
        state.addPendingOpener(executionCount::incrementAndGet);
        assertEquals(0, executionCount.get());

        // Release the slot — the pending opener should be dequeued and run
        state.releaseStream();
        assertEquals(1, executionCount.get(), "Pending opener should have been executed on release");
    }

    @Test
    public void multiplePendingOpenersExecuteInOrder() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);

        // Fill the single slot
        assertTrue(state.tryAcquireStream());

        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
        state.addPendingOpener(() -> executionOrder.add(1));
        state.addPendingOpener(() -> executionOrder.add(2));
        state.addPendingOpener(() -> executionOrder.add(3));

        assertTrue(executionOrder.isEmpty(), "No openers should run yet");

        // Release slot 1 — first pending opener runs
        state.releaseStream();
        assertEquals(1, executionOrder.size());
        assertEquals(1, executionOrder.get(0));

        // Release slot 2 — second pending opener runs
        state.releaseStream();
        assertEquals(2, executionOrder.size());
        assertEquals(2, executionOrder.get(1));

        // Release slot 3 — third pending opener runs
        state.releaseStream();
        assertEquals(3, executionOrder.size());
        assertEquals(3, executionOrder.get(2));
    }

    @Test
    public void pendingOpenerDoesNotRunWhenDraining() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);

        // Fill the slot
        assertTrue(state.tryAcquireStream());

        AtomicInteger executionCount = new AtomicInteger(0);
        state.addPendingOpener(executionCount::incrementAndGet);

        // Start draining before releasing
        state.setDraining(10);

        // Release — pending opener should NOT run because draining prevents tryAcquireStream
        state.releaseStream();
        assertEquals(0, executionCount.get(), "Pending opener should not run on a draining connection");
    }

    // -------------------------------------------------------------------------
    // Enqueue-after-close/draining must be REJECTED, not orphaned (Issue #2160 GOAWAY race)
    // -------------------------------------------------------------------------

    @Test
    public void addPendingOpenerAcceptedAndRunsOnHealthyConnection() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);
        assertTrue(state.tryAcquireStream()); // fill the only slot

        AtomicInteger ran = new AtomicInteger();
        assertTrue(state.offerPendingOpener(ran::incrementAndGet),
                "a healthy (not draining/closed) connection must accept and queue the opener");
        assertEquals(0, ran.get(), "queued opener waits for a free slot");

        state.releaseStream();
        assertEquals(1, ran.get(), "queued opener runs when a slot frees");
    }

    @Test
    public void addPendingOpenerRejectedAfterFailPendingOpeners() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);
        assertTrue(state.tryAcquireStream()); // fill the only slot

        // The connection dropped: failPendingOpeners drained the (empty) queue and marked the state closed.
        state.failPendingOpeners(f -> { });

        // A request that raced the close and only now reaches addPendingOpener must be REJECTED (false), not
        // silently enqueued where nothing would ever run it OR fail it — that is the #2160 silent-timeout
        // orphan: a queued request with no stream channel survives only until the request timeout fires.
        AtomicInteger ran = new AtomicInteger();
        assertFalse(state.offerPendingOpener(ran::incrementAndGet),
                "addPendingOpener must reject on a closed connection so the caller fails the request itself");

        // It must not have been queued either: a later releaseStream must never run it.
        state.releaseStream();
        assertEquals(0, ran.get(), "a rejected opener must never run");
    }

    @Test
    public void addPendingOpenerRejectedWhenDrainingAtEnqueueTime() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);
        assertTrue(state.tryAcquireStream()); // fill the only slot

        // GOAWAY was processed (draining set) between this request's failed tryAcquireStream and its enqueue.
        state.setDraining(1);

        AtomicInteger ran = new AtomicInteger();
        assertFalse(state.offerPendingOpener(ran::incrementAndGet),
                "addPendingOpener must reject on a draining connection");

        state.releaseStream();
        assertEquals(0, ran.get(), "a rejected opener must never run");
    }

    @Test
    public void activeStreamCountCorrectWithPendingOpeners() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(2);

        // Acquire 2 slots
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertEquals(2, state.getActiveStreams());

        // Queue 2 pending openers
        AtomicInteger runCount = new AtomicInteger(0);
        state.addPendingOpener(runCount::incrementAndGet);
        state.addPendingOpener(runCount::incrementAndGet);
        assertEquals(0, runCount.get());
        assertEquals(2, state.getActiveStreams());

        // Release one — pending opener runs and acquires the slot
        state.releaseStream();
        assertEquals(1, runCount.get());
        // Active streams: was 2, decremented to 1 by release, then incremented to 2 by pending opener
        assertEquals(2, state.getActiveStreams());

        // Release another — second pending opener runs
        state.releaseStream();
        assertEquals(2, runCount.get());
        assertEquals(2, state.getActiveStreams());

        // Release both remaining
        state.releaseStream();
        state.releaseStream();
        assertEquals(0, state.getActiveStreams());
    }

    // -------------------------------------------------------------------------
    // MaxConcurrentStreams updates
    // -------------------------------------------------------------------------

    @Test
    public void defaultMaxConcurrentStreamsIsMaxValue() {
        Http2ConnectionState state = new Http2ConnectionState();
        assertEquals(Integer.MAX_VALUE, state.getMaxConcurrentStreams());
    }

    @Test
    public void updateMaxConcurrentStreams() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(100);
        assertEquals(100, state.getMaxConcurrentStreams());
    }

    @Test
    public void reducingMaxConcurrentStreamsDoesNotAffectExistingStreams() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(10);

        for (int i = 0; i < 5; i++) {
            assertTrue(state.tryAcquireStream());
        }
        assertEquals(5, state.getActiveStreams());

        // Reduce limit below current active count
        state.updateMaxConcurrentStreams(3);
        assertEquals(5, state.getActiveStreams(), "Existing streams should not be killed");

        // New acquisitions should fail
        assertFalse(state.tryAcquireStream());
    }

    @Test
    public void increasingMaxConcurrentStreamsAllowsMoreAcquisitions() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(2);

        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertFalse(state.tryAcquireStream());

        state.updateMaxConcurrentStreams(5);
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertFalse(state.tryAcquireStream());
        assertEquals(5, state.getActiveStreams());
    }

    // -------------------------------------------------------------------------
    // Partition key
    // -------------------------------------------------------------------------

    @Test
    public void partitionKeyIsNullByDefault() {
        Http2ConnectionState state = new Http2ConnectionState();
        assertNull(state.getPartitionKey());
    }

    @Test
    public void partitionKeyCanBeSetAndRetrieved() {
        Http2ConnectionState state = new Http2ConnectionState();
        Object key = new Object();
        state.setPartitionKey(key);
        assertSame(key, state.getPartitionKey());
    }

    // -------------------------------------------------------------------------
    // Concurrent stress tests (Bug 4 race condition validation)
    // -------------------------------------------------------------------------

    @Test
    public void concurrentAcquireAndReleaseNeverExceedsMax() throws InterruptedException {
        Http2ConnectionState state = new Http2ConnectionState();
        int maxStreams = 10;
        state.updateMaxConcurrentStreams(maxStreams);

        int numThreads = 20;
        int iterations = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        AtomicInteger maxObserved = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < iterations; i++) {
                        if (state.tryAcquireStream()) {
                            int current = state.getActiveStreams();
                            maxObserved.updateAndGet(prev -> Math.max(prev, current));
                            if (current > maxStreams) {
                                errors.incrementAndGet();
                            }
                            // Simulate some work
                            Thread.yield();
                            state.releaseStream();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "activeStreams should never exceed maxConcurrentStreams");
        assertTrue(maxObserved.get() <= maxStreams,
                "Max observed (" + maxObserved.get() + ") should not exceed max (" + maxStreams + ")");
        assertEquals(0, state.getActiveStreams(), "All streams should be released");
    }

    @Test
    public void concurrentAddPendingOpenerAndReleaseStream() throws InterruptedException {
        Http2ConnectionState state = new Http2ConnectionState();
        int maxStreams = 5;
        state.updateMaxConcurrentStreams(maxStreams);

        int numThreads = 20;
        int totalOpeners = 100;
        AtomicInteger executedCount = new AtomicInteger(0);
        CountDownLatch allSubmitted = new CountDownLatch(totalOpeners);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        // Fill all slots first
        for (int i = 0; i < maxStreams; i++) {
            assertTrue(state.tryAcquireStream());
        }

        // Submit pending openers from multiple threads
        for (int i = 0; i < totalOpeners; i++) {
            executor.submit(() -> {
                state.addPendingOpener(() -> {
                    executedCount.incrementAndGet();
                    // Immediately release so next pending opener can run
                    state.releaseStream();
                });
                allSubmitted.countDown();
            });
        }

        assertTrue(allSubmitted.await(10, TimeUnit.SECONDS), "All openers should be submitted");

        // Release the initial slots — this should cascade through all pending openers
        for (int i = 0; i < maxStreams; i++) {
            state.releaseStream();
        }

        // Give some time for cascading execution
        Thread.sleep(500);

        assertEquals(totalOpeners, executedCount.get(),
                "All pending openers should have been executed");
        assertEquals(0, state.getActiveStreams(), "All streams should be released after cascading");

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    public void concurrentAddPendingOpenerMaintainsStreamCountInvariant() throws InterruptedException {
        Http2ConnectionState state = new Http2ConnectionState();
        int maxStreams = 3;
        state.updateMaxConcurrentStreams(maxStreams);

        int numThreads = 10;
        int iterations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < iterations; i++) {
                        CountDownLatch ran = new CountDownLatch(1);
                        state.addPendingOpener(() -> {
                            int active = state.getActiveStreams();
                            if (active > maxStreams) {
                                errors.incrementAndGet();
                            }
                            ran.countDown();
                            state.releaseStream();
                        });
                        ran.await(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(),
                "activeStreams should never exceed maxConcurrentStreams in pending openers");
        assertEquals(0, state.getActiveStreams());
    }

    @Test
    public void activeStreamsNeverGoesNegative() throws InterruptedException {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(100);

        int numThreads = 10;
        int iterations = 500;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        AtomicInteger negativeObserved = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < iterations; i++) {
                        if (state.tryAcquireStream()) {
                            state.releaseStream();
                            int active = state.getActiveStreams();
                            if (active < 0) {
                                negativeObserved.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, negativeObserved.get(), "activeStreams should never go negative");
        assertTrue(state.getActiveStreams() >= 0);
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    public void acquireWithZeroMaxConcurrentStreams() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(0);

        assertFalse(state.tryAcquireStream());
        assertEquals(0, state.getActiveStreams());
    }

    @Test
    public void addPendingOpenerWithZeroMaxConcurrentStreams() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(0);

        AtomicInteger executionCount = new AtomicInteger(0);
        state.addPendingOpener(executionCount::incrementAndGet);

        assertEquals(0, executionCount.get(), "Opener should be queued when max is 0");

        // Raising the cap from 0 drains the queued opener immediately — no stream completion needed.
        // (Previously this stalled until the next releaseStream; a cap-raising SETTINGS now wakes openers.)
        state.updateMaxConcurrentStreams(1);
        assertEquals(1, executionCount.get(), "raising the cap from 0 must run the queued opener");
        assertEquals(1, state.getActiveStreams());
    }

    @Test
    public void drainingPreventsNewAcquisitionsButAllowsRelease() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(5);

        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertEquals(2, state.getActiveStreams());

        state.setDraining(100);

        // Cannot acquire new streams
        assertFalse(state.tryAcquireStream());
        assertEquals(2, state.getActiveStreams());

        // Can still release existing streams
        state.releaseStream();
        assertEquals(1, state.getActiveStreams());
        state.releaseStream();
        assertEquals(0, state.getActiveStreams());
    }

    // -------------------------------------------------------------------------
    // #8 effective max concurrent streams = min(client config, server SETTINGS)
    // -------------------------------------------------------------------------

    @Test
    public void serverSettingsCannotRaiseAboveClientConfiguredMax() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.setClientMaxConcurrentStreams(5);
        // Server advertises a HIGHER limit — the client's configured cap must still win (RFC 9113 §5.1.2).
        state.updateMaxConcurrentStreams(100);
        assertEquals(5, state.getMaxConcurrentStreams());

        for (int i = 0; i < 5; i++) {
            assertTrue(state.tryAcquireStream());
        }
        assertFalse(state.tryAcquireStream(), "6th stream must be refused even though server allowed 100");
    }

    @Test
    public void serverSettingsLowerThanClientMaxAreHonored() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.setClientMaxConcurrentStreams(100);
        state.updateMaxConcurrentStreams(2); // server is stricter
        assertEquals(2, state.getMaxConcurrentStreams());

        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream());
        assertFalse(state.tryAcquireStream());
    }

    @Test
    public void effectiveMaxIsMinRegardlessOfUpdateOrder() {
        Http2ConnectionState serverFirst = new Http2ConnectionState();
        serverFirst.updateMaxConcurrentStreams(100);
        serverFirst.setClientMaxConcurrentStreams(5);
        assertEquals(5, serverFirst.getMaxConcurrentStreams());

        Http2ConnectionState clientFirst = new Http2ConnectionState();
        clientFirst.setClientMaxConcurrentStreams(5);
        clientFirst.updateMaxConcurrentStreams(100);
        assertEquals(5, clientFirst.getMaxConcurrentStreams());
    }

    // -------------------------------------------------------------------------
    // #10 redundant connection flag must NOT block its own opening request
    // -------------------------------------------------------------------------

    @Test
    public void redundantConnectionStillAllowsItsOwnStream() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.markRedundant();
        assertTrue(state.isRedundant());
        // Unlike draining, redundant must let the connection's own opening request acquire a slot.
        assertTrue(state.tryAcquireStream());
        assertEquals(1, state.getActiveStreams());
    }

    // -------------------------------------------------------------------------
    // A cap-raising SETTINGS frame must drain queued openers without a stream completing (missed-wakeup fix)
    // -------------------------------------------------------------------------

    @Test
    public void capRaisingSettingsDrainsAllQueuedOpenersWithoutStreamCompletion() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1); // server cap = 1, effective = 1

        assertTrue(state.tryAcquireStream()); // fill the only slot (a long-lived in-flight stream)
        AtomicInteger ran = new AtomicInteger(0);
        state.offerPendingOpener(ran::incrementAndGet);
        state.offerPendingOpener(ran::incrementAndGet);
        state.offerPendingOpener(ran::incrementAndGet);
        assertEquals(0, ran.get(), "queued while at the cap");

        // The server raises SETTINGS_MAX_CONCURRENT_STREAMS. The newly-available slots must wake the queued
        // openers NOW — no in-flight stream completes here (releaseStream is never called).
        state.updateMaxConcurrentStreams(10);
        assertEquals(3, ran.get(),
                "a cap-raising SETTINGS must wake ALL queued openers (not one, not zero) with no completion");
        assertEquals(4, state.getActiveStreams(), "1 in-flight + 3 newly opened");
    }

    @Test
    public void capRaiseDrainsOnlyUpToTheNewLimit() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);

        assertTrue(state.tryAcquireStream()); // active=1, cap=1
        AtomicInteger ran = new AtomicInteger(0);
        state.offerPendingOpener(ran::incrementAndGet);
        state.offerPendingOpener(ran::incrementAndGet);
        state.offerPendingOpener(ran::incrementAndGet);

        // Raising the cap by exactly one frees exactly one slot — wake exactly one opener, never over-open.
        state.updateMaxConcurrentStreams(2);
        assertEquals(1, ran.get(), "raising the cap by one must wake exactly one queued opener");
        assertEquals(2, state.getActiveStreams(), "must not exceed the new cap");
    }

    @Test
    public void loweringSettingsDoesNotRunQueuedOpeners() {
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(2);

        assertTrue(state.tryAcquireStream());
        assertTrue(state.tryAcquireStream()); // active=2, cap=2
        AtomicInteger ran = new AtomicInteger(0);
        state.offerPendingOpener(ran::incrementAndGet); // queued (at the cap)

        // A SETTINGS frame that LOWERS the cap must never open anything.
        state.updateMaxConcurrentStreams(1);
        assertEquals(0, ran.get(), "lowering the cap must never run a queued opener");
    }

    @Test
    public void voidAddPendingOpenerWrapperStillQueuesAndRuns() {
        // The void addPendingOpener overloads are retained for binary compatibility; verify they still
        // delegate to offerPendingOpener (queue, then run on release).
        Http2ConnectionState state = new Http2ConnectionState();
        state.updateMaxConcurrentStreams(1);
        assertTrue(state.tryAcquireStream());

        AtomicInteger ran = new AtomicInteger(0);
        state.addPendingOpener(ran::incrementAndGet); // the retained void overload
        assertEquals(0, ran.get());
        state.releaseStream();
        assertEquals(1, ran.get(), "the void addPendingOpener wrapper must delegate to offerPendingOpener");
    }

    // -------------------------------------------------------------------------
    // Once-only permit release (round-robin GOAWAY drain permit, issue #2214)
    // -------------------------------------------------------------------------

    @Test
    public void releasePermitOnceIsNoOpWhenNoHookInstalled() {
        // DEFAULT mode never installs a hook; releasePermitOnce must be a safe no-op there.
        Http2ConnectionState state = new Http2ConnectionState();
        assertDoesNotThrow(state::releasePermitOnce);
    }

    @Test
    public void releasePermitOnceRunsHookExactlyOnce() {
        // The hook must fire on the first call and never again: the GOAWAY handler and the channel
        // closeFuture both call releasePermitOnce.
        Http2ConnectionState state = new Http2ConnectionState();
        AtomicInteger releases = new AtomicInteger(0);
        state.setPermitRelease(releases::incrementAndGet);

        state.releasePermitOnce();
        assertEquals(1, releases.get(), "first releasePermitOnce must run the hook");

        state.releasePermitOnce();
        state.releasePermitOnce();
        assertEquals(1, releases.get(), "subsequent releasePermitOnce calls must be no-ops");
    }

    @Test
    public void releasePermitOnceIsAtomicUnderConcurrency() throws InterruptedException {
        // Many threads call releasePermitOnce at once; the hook must run exactly once total.
        int rounds = 2000;
        AtomicInteger totalReleases = new AtomicInteger(0);
        int numThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        try {
            for (int r = 0; r < rounds; r++) {
                Http2ConnectionState state = new Http2ConnectionState();
                AtomicInteger releasesThisRound = new AtomicInteger(0);
                state.setPermitRelease(releasesThisRound::incrementAndGet);

                CyclicBarrier barrier = new CyclicBarrier(numThreads);
                CountDownLatch done = new CountDownLatch(numThreads);
                for (int t = 0; t < numThreads; t++) {
                    executor.submit(() -> {
                        try {
                            barrier.await();
                            state.releasePermitOnce();
                        } catch (Exception ignored) {
                            // barrier interruption is not expected in this test
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(done.await(10, TimeUnit.SECONDS));
                assertEquals(1, releasesThisRound.get(),
                        "releasePermitOnce must run the hook exactly once even under concurrent callers");
                totalReleases.addAndGet(releasesThisRound.get());
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(rounds, totalReleases.get(), "exactly one release per round");
    }
}
