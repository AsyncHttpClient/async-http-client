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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP/2 multiplexed stream gate: {@code Http2ConnectionState.tryAcquireStream()} runs a
 * CAS retry loop on a shared {@link AtomicInteger} for EVERY request sharing one h2 connection.
 * Many concurrent requests on one connection hammer this single counter.
 *
 * Compares the production CAS-loop against getAndIncrement-with-rollback (single CAS on the
 * common success path).
 *
 * Run multi-threaded:
 *   /tmp/run-jmh-conc.sh Http2StreamGateBenchmark -t 16 -f 1 -wi 5 -i 8
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class Http2StreamGateBenchmark {

    private AtomicInteger activeStreams;
    private static final int MAX = 100_000; // effectively unbounded so the gate always succeeds

    @Setup
    public void setup() {
        activeStreams = new AtomicInteger(0);
    }

    /** Exactly mirrors Http2ConnectionState.tryAcquireStream's CAS loop + releaseStream. */
    @Benchmark
    public void casLoopGate(Blackhole bh) {
        boolean acquired = false;
        while (true) {
            int current = activeStreams.get();
            if (current >= MAX) {
                break;
            }
            if (activeStreams.compareAndSet(current, current + 1)) {
                acquired = true;
                break;
            }
        }
        bh.consume(acquired);
        if (acquired) {
            activeStreams.decrementAndGet();
        }
    }

    /** Alternative: optimistic getAndIncrement, roll back if over limit (1 CAS on success path). */
    @Benchmark
    public void getAndIncrementGate(Blackhole bh) {
        int n = activeStreams.getAndIncrement();
        boolean acquired = n < MAX;
        if (!acquired) {
            activeStreams.decrementAndGet();
        }
        bh.consume(acquired);
        if (acquired) {
            activeStreams.decrementAndGet();
        }
    }
}
