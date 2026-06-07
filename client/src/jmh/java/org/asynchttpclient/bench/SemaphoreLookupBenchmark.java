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
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Models the hot path of {@code PerHostConnectionSemaphore.getFreeConnectionsForHost}:
 * a {@link ConcurrentHashMap#get} (current) vs the {@code get-then-computeIfAbsent} pattern
 * the production code actually uses, executed once per acquire AND once per release.
 *
 * The "current" benchmark mirrors PerHostConnectionSemaphore exactly. The "tryAcquireRelease"
 * benchmark adds the Semaphore acquire/release to show the lookup cost relative to the lock op.
 *
 * Run multi-threaded with -t N to surface CHM bucket / Semaphore contention:
 *   /tmp/run-jmh.sh SemaphoreLookupBenchmark -t 8 -f 1 -wi 5 -i 8
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SemaphoreLookupBenchmark {

    /** Number of distinct partition keys (hosts) requests are spread across. */
    @Param({"1", "8"})
    public int hosts;

    private ConcurrentHashMap<Object, Semaphore> freeChannelsPerHost;
    private Object[] keys;

    @State(Scope.Thread)
    public static class Cursor {
        int i;
    }

    @Setup(Level.Trial)
    public void setup() {
        freeChannelsPerHost = new ConcurrentHashMap<>();
        keys = new Object[hosts];
        for (int i = 0; i < hosts; i++) {
            keys[i] = "host-" + i;
            // Pre-populate so we exercise the steady-state (get-hit) path.
            freeChannelsPerHost.put(keys[i], new Semaphore(Integer.MAX_VALUE));
        }
    }

    /** Exactly mirrors getFreeConnectionsForHost: get(), fall back to computeIfAbsent. */
    private Semaphore getFreeConnectionsForHost(Object key) {
        Semaphore s = freeChannelsPerHost.get(key);
        if (s == null) {
            s = freeChannelsPerHost.computeIfAbsent(key, k -> new Semaphore(Integer.MAX_VALUE));
        }
        return s;
    }

    @Benchmark
    @Group("lookup")
    public void currentLookup(Cursor c, Blackhole bh) {
        Object key = keys[(c.i++ & Integer.MAX_VALUE) % keys.length];
        bh.consume(getFreeConnectionsForHost(key));
    }

    /** Full acquire+release as the request hot path does it (two lookups per request). */
    @Benchmark
    @Group("acquireRelease")
    public void acquireReleaseCurrent(Cursor c, Blackhole bh) throws InterruptedException {
        Object key = keys[(c.i++ & Integer.MAX_VALUE) % keys.length];
        Semaphore acq = getFreeConnectionsForHost(key);
        boolean got = acq.tryAcquire(0, TimeUnit.MILLISECONDS);
        if (got) {
            Semaphore rel = getFreeConnectionsForHost(key); // second lookup on release
            rel.release();
            bh.consume(rel);
        }
    }
}
