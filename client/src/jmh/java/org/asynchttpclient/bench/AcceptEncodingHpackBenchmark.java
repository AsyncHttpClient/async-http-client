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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2HeadersEncoder;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * Measures the HPACK-encoded wire size of {@code accept-encoding} for the two value spellings:
 * <ul>
 *   <li>AHC current: {@code "gzip,deflate"} (no space) — built in
 *       {@code HttpUtils.GZIP_DEFLATE = new AsciiString(GZIP + "," + DEFLATE)}.</li>
 *   <li>HPACK static table entry #16: {@code "gzip, deflate"} (with space, RFC 7541 App. A).</li>
 * </ul>
 *
 * <p>On a fresh encoder (first request of a connection) the static-table value matches as a single
 * indexed byte; the non-matching spelling is literal-encoded and inserted into the dynamic table.
 * This bench reports encoding time and, when run with {@code -prof gc}, allocation. The returned encoded
 * byte count prevents dead-code elimination; {@code AcceptEncodingHpackTest} verifies the wire sizes.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class AcceptEncodingHpackBenchmark {

    private static final AsciiString ACCEPT_ENCODING = AsciiString.cached("accept-encoding");
    private static final AsciiString AHC_VALUE = AsciiString.cached("gzip,deflate");
    private static final AsciiString STATIC_VALUE = AsciiString.cached("gzip, deflate");

    private int encodeOnce(AsciiString value) throws Exception {
        // Fresh encoder per call == "first request on a new connection" worst case.
        Http2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder();
        Http2Headers headers = new DefaultHttp2Headers().add(ACCEPT_ENCODING, value);
        ByteBuf out = Unpooled.buffer();
        try {
            encoder.encodeHeaders(3, headers, out);
            return out.readableBytes();
        } finally {
            out.release();
        }
    }

    @Benchmark
    public int ahc_no_space() throws Exception {
        return encodeOnce(AHC_VALUE);
    }

    @Benchmark
    public int static_table_with_space() throws Exception {
        return encodeOnce(STATIC_VALUE);
    }
}
