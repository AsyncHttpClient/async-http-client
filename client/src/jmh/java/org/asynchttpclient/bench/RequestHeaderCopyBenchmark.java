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

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Measures the cost of copying an already-validated AHC request header set into the outbound
 * Netty {@code HttpRequest} header set on the request hot path.
 * <p>
 * In production, {@code RequestBuilderBase} builds the request headers with validation enabled
 * (see {@code RequestBuilderBase} ctor: {@code new DefaultHttpHeaders(validateHeaders)} with
 * {@code validateHeaders=true} default). Then {@code NettyRequestFactory.newNettyRequest} builds
 * the outbound request with Netty's default validating headers factory and does
 * {@code headers.set(request.getHeaders())} — re-validating every name and value a second time.
 * <p>
 * This bench compares {@code set(...)} into a validating vs non-validating {@link DefaultHttpHeaders}
 * to quantify the redundant validation that proposal 004 proposes to drop (since the source headers
 * were already validated when the request was built).
 * <p>
 * Run with: {@code /tmp/run-jmh.sh RequestHeaderCopyBenchmark -prof gc -f 1 -wi 5 -i 5}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RequestHeaderCopyBenchmark {

    private HttpHeaders source;

    @Setup(Level.Trial)
    public void setup() {
        // Representative request header set already built (and validated) by RequestBuilderBase.
        source = new DefaultHttpHeaders(true);
        source.add("Host", "www.example.com");
        source.add("User-Agent", "AHC/3.0");
        source.add("Accept", "*/*");
        source.add("Accept-Encoding", "gzip,deflate");
        source.add("Connection", "keep-alive");
        source.add("Authorization", "Bearer abcdefghijklmnopqrstuvwxyz0123456789");
        source.add("Cookie", "session=0123456789abcdef; theme=dark; lang=en-US");
        source.add("Content-Type", "application/json; charset=utf-8");
        source.add("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000");
        source.add("X-Forwarded-For", "203.0.113.7");
    }

    /** Production behavior: outbound headers re-validate every name+value. */
    @Benchmark
    public HttpHeaders validating() {
        HttpHeaders out = new DefaultHttpHeaders(true);
        out.set(source);
        return out;
    }

    /** Proposed: source already validated, so skip re-validation on the outbound copy. */
    @Benchmark
    public HttpHeaders nonValidating() {
        HttpHeaders out = new DefaultHttpHeaders(false);
        out.set(source);
        return out;
    }
}
