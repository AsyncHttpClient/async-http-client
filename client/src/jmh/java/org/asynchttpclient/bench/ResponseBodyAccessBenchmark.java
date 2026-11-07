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

import io.netty.buffer.Unpooled;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.netty.EagerResponseBodyPart;
import org.asynchttpclient.netty.NettyResponse;
import org.asynchttpclient.netty.NettyResponseStatus;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures the {@link NettyResponse} body accessors an {@code AsyncCompletionHandler} user calls once the
 * response is complete, over eager body parts as the default {@code ResponseBodyPartFactory} builds them.
 * <p>
 * {@code 1x4096} is a small body that arrives in one read; {@code 16x8192} is a 128 KiB body cut into
 * {@code httpClientCodecMaxChunkSize}-sized parts. A fresh response wraps the same parts on every operation,
 * as a real response would.
 * <p>
 * {@link #drainStream} measures the stream; {@link #responseBodyTwice} measures the string accessors, which
 * concatenate a multi-part body on every call.
 * <p>
 * Run with: {@code /tmp/run-jmh.sh ResponseBodyAccessBenchmark -prof gc -f 2 -wi 5 -w 1 -i 10 -r 1}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ResponseBodyAccessBenchmark {

    @Param({"1x4096", "16x8192"})
    public String shape;

    private NettyResponseStatus status;
    private List<HttpResponseBodyPart> parts;
    // A caller's read buffer, of the order a streaming parser reads into.
    private final byte[] readBuffer = new byte[8000];

    @Setup(Level.Trial)
    public void setup() {
        String[] dims = shape.split("x");
        int count = Integer.parseInt(dims[0]);
        int size = Integer.parseInt(dims[1]);
        status = new NettyResponseStatus(null, null, null);
        parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] chunk = new byte[size];
            for (int j = 0; j < size; j++) {
                chunk[j] = (byte) ('a' + (i + j) % 26);
            }
            parts.add(new EagerResponseBodyPart(Unpooled.wrappedBuffer(chunk), i == count - 1));
        }
    }

    @Benchmark
    public long drainStream() throws IOException {
        InputStream in = new NettyResponse(status, null, parts).getResponseBodyAsStream();
        long total = 0;
        int n;
        while ((n = in.read(readBuffer, 0, readBuffer.length)) != -1) {
            total += n;
        }
        return total;
    }

    @Benchmark
    public void responseBodyTwice(Blackhole bh) {
        NettyResponse response = new NettyResponse(status, null, parts);
        bh.consume(response.getResponseBody());
        bh.consume(response.getResponseBody());
    }
}
