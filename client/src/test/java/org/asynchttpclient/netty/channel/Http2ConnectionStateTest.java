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

        // Increase the limit and release to trigger
        state.updateMaxConcurrentStreams(1);
        // Need a releaseStream to drain pending — but first we need to have acquired
        // Since we can't release without acquiring, let's acquire and immediately release
        assertTrue(state.tryAcquireStream());
        state.releaseStream();

        assertEquals(1, executionCount.get());
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
}
