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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.TimeUnit;

/**
 * Models the per-checkout allocation of {@code DefaultChannelPool}: each
 * {@code offer()} wraps the channel in a freshly allocated {@code IdleChannel}
 * holder that is pushed onto a {@code ConcurrentLinkedDeque} (which itself
 * allocates a linked node per insert). On {@code poll()} the holder is
 * discarded. Under keep-alive churn this is one IdleChannel + one CLD node per
 * request.
 *
 * This bench compares the current "allocate a holder per offer" pattern against
 * an alternative that stores the bare channel reference + a parallel timestamp,
 * avoiding the holder allocation. It is a standalone model (no Netty Channel
 * needed) so it can run on the bare JMH classpath; the shapes mirror
 * DefaultChannelPool.IdleChannel exactly (one Object ref + one long + one
 * volatile int) and CLD node churn is identical for both arms because both push
 * one element.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ChannelPoolCheckoutBenchmark {

    // Mirror of DefaultChannelPool.IdleChannel: Object ref + long start + volatile int owned.
    static final class IdleChannel {
        static final AtomicIntegerFieldUpdater<IdleChannel> OWNED =
                AtomicIntegerFieldUpdater.newUpdater(IdleChannel.class, "owned");
        final Object channel;
        final long start;
        @SuppressWarnings("unused")
        private volatile int owned;

        IdleChannel(Object channel, long start) {
            this.channel = channel;
            this.start = start;
        }

        boolean takeOwnership() {
            return OWNED.getAndSet(this, 1) == 0;
        }
    }

    private ConcurrentLinkedDeque<IdleChannel> currentDeque;
    private ConcurrentLinkedDeque<Object> bareDeque;
    private Object channel;

    @Setup(Level.Trial)
    public void setup() {
        currentDeque = new ConcurrentLinkedDeque<>();
        bareDeque = new ConcurrentLinkedDeque<>();
        channel = new Object();
    }

    /** Current behavior: allocate an IdleChannel holder on every offer. */
    @Benchmark
    public void currentOfferPoll(Blackhole bh) {
        currentDeque.offerFirst(new IdleChannel(channel, 123L));
        IdleChannel c = currentDeque.pollFirst();
        if (c != null && c.takeOwnership()) {
            bh.consume(c.channel);
        }
    }

    /**
     * Alternative: push the bare channel ref. Models pushing the Channel itself
     * and reading the timestamp/owned flag from a Netty channel attribute
     * instead of a per-checkout holder. Only the CLD node is allocated.
     */
    @Benchmark
    public void bareOfferPoll(Blackhole bh) {
        bareDeque.offerFirst(channel);
        Object c = bareDeque.pollFirst();
        bh.consume(c);
    }
}
