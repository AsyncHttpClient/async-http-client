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

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.asynchttpclient.netty.channel.DefaultChannelPool;
import org.asynchttpclient.netty.channel.DefaultChannelPool.PoolLeaseStrategy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One idle-cleaner pass of {@link DefaultChannelPool} in a client that has talked to many hosts once and
 * now talks to a few: {@code dormantHosts} partitions that went empty longer than the idle timeout ago,
 * plus {@code liveHosts} partitions whose channel is leased and returned on every invocation, which keeps
 * them from expiring. {@code cycle} is that lease and return alone, so {@code cleanerPass - cycle} is the
 * pass. The cleaner is fired synchronously through a capturing {@link Timer}.
 *
 * <p>Meant as an A/B against a pool that keeps every partition it ever created, where the pass walks all the
 * dormant deques. Where empty partitions are dropped, {@code dormantHosts} still matters a little: the map's
 * table keeps the capacity it grew to, and the pass walks its bins.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ChannelPoolCleanerBenchmark {

    private static final long IDLE_MILLIS = 50;

    @Param({"1000", "10000"})
    public int dormantHosts;

    @Param("16")
    public int liveHosts;

    private CapturingTimer timer;
    private DefaultChannelPool pool;
    private Object[] liveKeys;

    @Setup(Level.Trial)
    public void setup() {
        timer = new CapturingTimer();
        pool = new DefaultChannelPool(Duration.ofMillis(IDLE_MILLIS), Duration.ZERO, PoolLeaseStrategy.LIFO, timer,
                Duration.ofMillis(IDLE_MILLIS));

        Channel visitor = new EmbeddedChannel();
        for (int i = 0; i < dormantHosts; i++) {
            Object key = "dormant-" + i;
            pool.offer(visitor, key);
            pool.poll(key);
        }
        // Let the dormant hosts stay quiet for longer than the idle timeout, with the cleaner ticking.
        long quietUntil = System.currentTimeMillis() + 2 * IDLE_MILLIS;
        do {
            timer.fire();
            Thread.onSpinWait();
        } while (System.currentTimeMillis() < quietUntil);
        timer.fire();

        liveKeys = new Object[liveHosts];
        for (int i = 0; i < liveHosts; i++) {
            liveKeys[i] = "live-" + i;
            pool.offer(new EmbeddedChannel(), liveKeys[i]);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.destroy();
    }

    @Benchmark
    public void cycle() {
        cycleLiveHosts();
    }

    @Benchmark
    public void cleanerPass() {
        cycleLiveHosts();
        timer.fire();
    }

    private void cycleLiveHosts() {
        for (Object key : liveKeys) {
            Channel channel = pool.poll(key);
            if (channel == null) {
                // A pause longer than the idle timeout let the cleaner close it; keep the host live.
                channel = new EmbeddedChannel();
            }
            pool.offer(channel, key);
        }
    }

    private static final class CapturingTimer implements Timer {

        private TimerTask task;

        @Override
        public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
            this.task = task;
            return new CapturedTimeout(this, task);
        }

        @Override
        public Set<Timeout> stop() {
            return Collections.emptySet();
        }

        void fire() {
            TimerTask current = task;
            try {
                current.run(new CapturedTimeout(this, current));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class CapturedTimeout implements Timeout {

        private final Timer timer;
        private final TimerTask task;

        CapturedTimeout(Timer timer, TimerTask task) {
            this.timer = timer;
            this.task = task;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean cancel() {
            return false;
        }
    }
}
