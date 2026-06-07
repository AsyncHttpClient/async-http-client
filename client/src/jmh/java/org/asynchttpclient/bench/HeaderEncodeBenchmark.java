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
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AsciiString;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.TimeUnit;

/**
 * Measures the cost of encoding an outbound HTTP/1.1 request through Netty's
 * {@link HttpRequestEncoder} when header names/values are plain {@code String} vs
 * {@link AsciiString}.
 * <p>
 * Netty's {@code HttpHeadersEncoder.writeAscii} (4.2.13, lines 50-55) takes a fast
 * {@code ByteBufUtil.copy((AsciiString)...)} path only when the {@link CharSequence} is an
 * {@link AsciiString}; for a {@code String} it falls to {@code buf.setCharSequence(..., US_ASCII)}
 * which encodes char-by-char. AHC stores user-supplied header names as whatever {@code CharSequence}
 * the caller passed (typically {@code String} — see {@code RequestBuilderBase.setHeader(CharSequence,..)}),
 * so the outbound request header names take the slow path on every request encode.
 * <p>
 * Run with: {@code /tmp/run-jmh.sh HeaderEncodeBenchmark -prof gc -f 1 -wi 5 -i 5}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HeaderEncodeBenchmark {

    private EmbeddedChannel channel;

    @Setup(Level.Trial)
    public void setup() {
        channel = new EmbeddedChannel(new HttpRequestEncoder());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    private void drain() {
        ByteBuf out;
        while ((out = channel.readOutbound()) != null) {
            out.release();
        }
    }

    /** Header names/values as plain String — the AHC production case. */
    @Benchmark
    public void stringHeaders() {
        HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path/to/resource?a=1&b=2");
        req.headers()
                .add("Host", "www.example.com")
                .add("User-Agent", "AHC/3.0")
                .add("Accept", "*/*")
                .add("Accept-Encoding", "gzip,deflate")
                .add("Connection", "keep-alive")
                .add("Authorization", "Bearer abcdefghijklmnopqrstuvwxyz0123456789")
                .add("Content-Type", "application/json; charset=utf-8");
        channel.writeOutbound(req);
        // Complete the HTTP message so the stateful HttpRequestEncoder returns to ST_INIT;
        // without this the next invocation throws EncoderException (unexpected message type).
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);
        drain();
    }

    /** Header names/values as AsciiString constants — the proposed encode-boundary fast path. */
    @Benchmark
    public void asciiHeaders() {
        HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path/to/resource?a=1&b=2");
        req.headers()
                .add(HttpHeaderNames.HOST, AsciiString.cached("www.example.com"))
                .add(HttpHeaderNames.USER_AGENT, AsciiString.cached("AHC/3.0"))
                .add(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON)
                .add(HttpHeaderNames.ACCEPT_ENCODING, AsciiString.cached("gzip,deflate"))
                .add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                .add(HttpHeaderNames.AUTHORIZATION, AsciiString.cached("Bearer abcdefghijklmnopqrstuvwxyz0123456789"))
                .add(HttpHeaderNames.CONTENT_TYPE, AsciiString.cached("application/json; charset=utf-8"));
        channel.writeOutbound(req);
        // Complete the HTTP message so the stateful HttpRequestEncoder returns to ST_INIT.
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);
        drain();
    }
}
