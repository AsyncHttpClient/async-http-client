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
package org.asynchttpclient.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contention on the single shared global {@code Semaphore} used by MaxConnectionSemaphore /
 * CombinedConnectionSemaphore. Every request acquires+releases this one AQS-backed counter.
 *
 * Compares:
 *  - semaphoreTimedTryAcquire: the production path (timed tryAcquire then release).
 *  - atomicCounterBound: a lock-free CAS-bounded counter as a "best case" reference
 *    (NOT a drop-in: loses blocking-with-timeout semantics; used only to size the headroom).
 *
 * Run multi-threaded:
 *   /tmp/run-jmh-conc.sh GlobalSemaphoreBenchmark -t 16 -f 1 -wi 5 -i 8
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class GlobalSemaphoreBenchmark {

    private Semaphore semaphore;
    private AtomicInteger atomic;
    private static final int MAX = 100_000;

    @Setup
    public void setup() {
        semaphore = new Semaphore(MAX);
        atomic = new AtomicInteger(0);
    }

    /** Mirrors MaxConnectionSemaphore.acquireChannelLock with a 0ms timed tryAcquire + release. */
    @Benchmark
    public void semaphoreTimedTryAcquire(Blackhole bh) throws InterruptedException {
        boolean got = semaphore.tryAcquire(0, TimeUnit.MILLISECONDS);
        bh.consume(got);
        if (got) {
            semaphore.release();
        }
    }

    /** Lower-bound reference: CAS-bounded counter (no blocking/timeout). */
    @Benchmark
    public void atomicCounterBound(Blackhole bh) {
        int cur;
        boolean got = false;
        do {
            cur = atomic.get();
            if (cur >= MAX) {
                break;
            }
        } while (!(got = atomic.compareAndSet(cur, cur + 1)));
        bh.consume(got);
        if (got) {
            atomic.decrementAndGet();
        }
    }
}
