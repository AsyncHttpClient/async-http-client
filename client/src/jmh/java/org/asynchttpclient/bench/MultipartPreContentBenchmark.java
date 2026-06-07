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
import org.asynchttpclient.request.body.multipart.MultipartBody;
import org.asynchttpclient.request.body.multipart.MultipartUtils;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.request.body.multipart.StringPart;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.TimeUnit;

/**
 * Measures allocations of building a multipart body's per-part pre-content
 * (header) section. Today every part runs {@code visitPreContent} twice through
 * {@code MultipartPart}: once with a {@code CounterPartVisitor} (to size the
 * buffer) and once with a {@code ByteBufVisitor} (to write it). The counter
 * pass only needs lengths, but the code still allocates a full {@code byte[]}
 * via {@code name.getBytes(US_ASCII)} / {@code contentType.getBytes(US_ASCII)}
 * etc. just to read {@code .length}; those arrays are thrown away. For ASCII
 * field names/values the byte length equals the String length, so the counting
 * pass can avoid the array entirely.
 *
 * Arm A ({@link #currentNewMultipartBody}) builds the body via the production
 * {@code MultipartUtils} path (constructor runs the count pass + the write pass
 * will reallocate the same arrays again). Arm B ({@link #optimizedCountOnly})
 * is a prototype counter that sizes the same header bytes using String lengths
 * for ASCII, showing the lower bound a length-only counter would reach.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MultipartPreContentBenchmark {

    @Param({"1", "8"})
    public int parts;

    private List<Part> partList;
    private HttpHeaders headers;
    private final byte[] boundary = "----boundary1234567890abcdefghij".getBytes(StandardCharsets.US_ASCII);

    @Setup(Level.Trial)
    public void setup() {
        partList = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            partList.add(new StringPart("field" + i, "value-" + i, "text/plain"));
        }
        headers = new DefaultHttpHeaders();
    }

    /** Production path: MultipartUtils builds parts; each part runs the count pass in its ctor. */
    @Benchmark
    public MultipartBody currentNewMultipartBody() {
        return MultipartUtils.newMultipartBody(partList, headers);
    }

    /**
     * Prototype: size the disposition + content-type header section using
     * String.length() for ASCII fields, with NO byte[] allocation. Mirrors the
     * bytes MultipartPart.visitDispositionHeader/visitContentTypeHeader emit.
     */
    @Benchmark
    public void optimizedCountOnly(Blackhole bh) {
        int total = 0;
        for (Part p : partList) {
            // "--" + boundary
            total += 2 + boundary.length;
            // CRLF + "Content-Disposition: " + "form-data"
            total += 2 + 21 + 9;
            String name = p.getName();
            if (name != null) {
                total += 7 + 1 + name.length() + 1; // "; name=" + quote + name + quote
            }
            String ct = p.getContentType();
            if (ct != null) {
                total += 2 + 14 + ct.length(); // CRLF + "Content-Type: " + ct
            }
            total += 4; // end-of-headers CRLFCRLF
        }
        bh.consume(total);
    }
}
