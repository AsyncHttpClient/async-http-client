/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 */
package org.asynchttpclient.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.asynchttpclient.netty.EagerResponseBodyPart;
import org.asynchttpclient.netty.LazyResponseBodyPart;
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
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Measures the per-chunk allocation cost of the two {@code ResponseBodyPartFactory}
 * implementations on the response read hot path.
 * <p>
 * {@link EagerResponseBodyPart} (the production default) eagerly copies every received
 * {@link ByteBuf} chunk into a freshly allocated {@code byte[]} via
 * {@code ByteBufUtil.getBytes(buf)} (see {@code EagerResponseBodyPart} ctor). For a direct
 * (non-array-backed) pooled buffer — the common case under the pooled allocator — that copy
 * always allocates {@code length} bytes per chunk.
 * <p>
 * {@link LazyResponseBodyPart} keeps a reference to the Netty {@link ByteBuf} and copies
 * nothing until the caller actually requests bytes. This bench quantifies the eager-copy
 * cost that proposal 001 proposes to avoid for handlers that consume {@code getBodyByteBuf()}.
 * <p>
 * Run with: {@code /tmp/run-jmh.sh ResponseBodyPartBenchmark -prof gc -f 1 -wi 5 -i 5}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ResponseBodyPartBenchmark {

    @Param({"512", "8192", "65536"})
    public int chunkSize;

    private PooledByteBufAllocator allocator;
    private ByteBuf direct;

    @Setup(Level.Trial)
    public void setup() {
        allocator = PooledByteBufAllocator.DEFAULT;
        // Direct pooled buffer mimics what Netty hands the inbound pipeline under the default allocator.
        direct = allocator.directBuffer(chunkSize);
        for (int i = 0; i < chunkSize; i++) {
            direct.writeByte(i & 0x7f);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        direct.release();
    }

    /** Production default: eagerly copies the chunk into a new byte[]. */
    @Benchmark
    public byte[] eager() {
        // duplicate() so the reader index is independent and the buffer is reusable across iterations
        return new EagerResponseBodyPart(direct.duplicate(), true).getBodyPartBytes();
    }

    /** Proposed alternative: no copy, just wrap the existing buffer. */
    @Benchmark
    public Object lazy(Blackhole bh) {
        LazyResponseBodyPart part = new LazyResponseBodyPart(direct.duplicate(), true);
        // Simulate a handler that streams the ByteBuf out without ever materializing a byte[]
        bh.consume(part.length());
        return part.getBodyByteBuf();
    }
}
