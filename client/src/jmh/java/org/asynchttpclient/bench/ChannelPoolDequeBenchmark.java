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
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Models {@code DefaultChannelPool.removeAll(Channel)} which calls
 * {@code ConcurrentLinkedDeque.remove(Object)} — an O(n) full traversal of the partition deque
 * performed on every connection close. Compared against a poll/offer (LIFO) pair which is O(1).
 *
 * Element identity mirrors IdleChannel.equals (compares wrapped value), so remove() must scan.
 *
 * Run multi-threaded:
 *   /tmp/run-jmh.sh ChannelPoolDequeBenchmark -t 8 -f 1 -wi 5 -i 8
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ChannelPoolDequeBenchmark {

    /** Steady-state number of idle connections per partition (deque length). */
    @Param({"4", "32", "128"})
    public int poolDepth;

    private ConcurrentLinkedDeque<Holder> deque;
    private Holder[] elements;
    private final AtomicInteger removeCursor = new AtomicInteger();

    static final class Holder {
        final int id;
        Holder(int id) { this.id = id; }
        @Override public boolean equals(Object o) {
            return this == o || (o instanceof Holder && id == ((Holder) o).id);
        }
        @Override public int hashCode() { return id; }
    }

    @Setup(Level.Invocation)
    public void setup() {
        deque = new ConcurrentLinkedDeque<>();
        elements = new Holder[poolDepth];
        for (int i = 0; i < poolDepth; i++) {
            elements[i] = new Holder(i);
            deque.offerFirst(elements[i]);
        }
    }

    /** Current removeAll path: O(n) remove(Object) scanning by equals. Removes the tail (worst case for LIFO insert). */
    @Benchmark
    public boolean currentRemoveAll() {
        int idx = removeCursor.getAndIncrement() % poolDepth;
        // remove a NEW Holder equal-by-id, exactly as DefaultChannelPool.removeAll builds
        // `new IdleChannel(channel, Long.MIN_VALUE)` and lets the deque scan for it.
        return deque.remove(new Holder(idx));
    }

    /** Baseline O(1) lease/return that poll()/offer() use, for scale reference. */
    @Benchmark
    public void pollOffer(Blackhole bh) {
        Holder h = deque.pollFirst();
        if (h != null) {
            deque.offerFirst(h);
        }
        bh.consume(h);
    }
}
