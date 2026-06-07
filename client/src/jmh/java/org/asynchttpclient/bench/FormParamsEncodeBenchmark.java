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

import org.asynchttpclient.util.HttpUtils;
import org.asynchttpclient.util.Utf8UrlEncoder;
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures allocations of url-encoding form params for an
 * {@code application/x-www-form-urlencoded} body.
 *
 * Arm A ({@link #currentUrlEncode}) is the production path:
 * {@code HttpUtils.urlEncodeFormParams} builds a (pooled) StringBuilder, calls
 * {@code .toString()} to materialize an intermediate {@code String}, then
 * {@code charset.encode(CharBuffer.wrap(str))} to get a {@code ByteBuffer}.
 *
 * Arm B ({@link #optimizedDirectEncode}) prototypes skipping the intermediate
 * String: it appends the encoded chars into a reused StringBuilder and encodes
 * the StringBuilder's chars to bytes via a CharBuffer view, avoiding the
 * String allocation. Both arms produce the same US-ASCII bytes.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FormParamsEncodeBenchmark {

    @Param({"2", "8"})
    public int params;

    private List<org.asynchttpclient.Param> paramList;
    private final StringBuilder scratch = new StringBuilder(512);

    @Setup(Level.Trial)
    public void setup() {
        paramList = new ArrayList<>(params);
        for (int i = 0; i < params; i++) {
            paramList.add(new org.asynchttpclient.Param("field" + i, "value " + i + "&x=y"));
        }
    }

    /** Production path: StringBuilder -> String -> ByteBuffer. */
    @Benchmark
    public ByteBuffer currentUrlEncode() {
        return HttpUtils.urlEncodeFormParams(paramList, StandardCharsets.UTF_8);
    }

    /**
     * Prototype: encode params into a reused StringBuilder, then encode the
     * builder's chars straight to bytes (no intermediate String). Mirrors the
     * UTF-8 form-encoding the production path performs.
     */
    @Benchmark
    public void optimizedDirectEncode(Blackhole bh) {
        StringBuilder sb = scratch;
        sb.setLength(0);
        for (org.asynchttpclient.Param p : paramList) {
            Utf8UrlEncoder.encodeAndAppendFormElement(sb, p.getName());
            sb.append('=');
            Utf8UrlEncoder.encodeAndAppendFormElement(sb, p.getValue());
            sb.append('&');
        }
        sb.setLength(sb.length() - 1);
        // encode StringBuilder chars to ASCII bytes without String.toString()
        int len = sb.length();
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) {
            bytes[i] = (byte) sb.charAt(i); // all output chars are US-ASCII after encoding
        }
        bh.consume(ByteBuffer.wrap(bytes));
    }
}
