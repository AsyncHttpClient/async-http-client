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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
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
 * Builds and encodes the headers {@code NettyRequestFactory} adds itself to an HTTP/1.1 request, with the
 * {@code Content-Length} and {@code User-Agent} values in their old and new forms. Every variant sets the same
 * {@code Host}, {@code Accept} and {@code Connection} values, so pairs differ only in the header under test.
 * <p>
 * Run with: {@code java -jar benchmarks.jar RequestHeaderValueBenchmark -prof gc -f 2 -wi 5 -i 10}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RequestHeaderValueBenchmark {

    private static final Integer ZERO_CONTENT_LENGTH = 0;
    private static final AsciiString ACCEPT_ALL = new AsciiString("*/*");

    // Non-final fields so the JIT cannot constant-fold the values.
    private String userAgent;
    private AsciiString userAgentAscii;
    private long contentLength;
    private String host;

    private EmbeddedChannel channel;
    private ByteBuf scratch;

    @Setup(Level.Trial)
    public void setup() {
        userAgent = "AHC/2.1";
        userAgentAscii = new AsciiString(userAgent);
        contentLength = 1536;
        host = "www.example.com";
        channel = new EmbeddedChannel(new HttpRequestEncoder());
        scratch = PooledByteBufAllocator.DEFAULT.directBuffer(256);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
        scratch.release();
    }

    private HttpRequest newRequest(HttpMethod method) {
        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, "/path/to/resource?a=1", Unpooled.EMPTY_BUFFER);
        request.headers()
                .set(HttpHeaderNames.HOST, host)
                .set(HttpHeaderNames.ACCEPT, ACCEPT_ALL);
        return request;
    }

    private void encode(HttpRequest request) {
        channel.writeOutbound(request);
        ByteBuf out;
        while ((out = channel.readOutbound()) != null) {
            out.release();
        }
    }

    @Benchmark
    public void userAgentString() {
        HttpRequest request = newRequest(HttpMethod.GET);
        request.headers().set(HttpHeaderNames.USER_AGENT, userAgent);
        encode(request);
    }

    @Benchmark
    public void userAgentAsciiString() {
        HttpRequest request = newRequest(HttpMethod.GET);
        request.headers().set(HttpHeaderNames.USER_AGENT, userAgentAscii);
        encode(request);
    }

    @Benchmark
    public void zeroContentLengthInteger() {
        HttpRequest request = newRequest(HttpMethod.POST);
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, ZERO_CONTENT_LENGTH);
        encode(request);
    }

    @Benchmark
    public void zeroContentLengthAsciiString() {
        HttpRequest request = newRequest(HttpMethod.POST);
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
        encode(request);
    }

    @Benchmark
    public void contentLengthBoxedLong() {
        HttpRequest request = newRequest(HttpMethod.POST);
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        encode(request);
    }

    @Benchmark
    public void contentLengthSetInt() {
        HttpRequest request = newRequest(HttpMethod.POST);
        HttpHeaders headers = request.headers();
        if (contentLength <= Integer.MAX_VALUE) {
            headers.setInt(HttpHeaderNames.CONTENT_LENGTH, (int) contentLength);
        } else {
            headers.set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        }
        encode(request);
    }

    // The two branches of HttpHeadersEncoder.writeAscii for the User-Agent value alone, without the rest of
    // the request around them.
    @Benchmark
    public int userAgentValueWriteString() {
        return scratch.setCharSequence(0, userAgent, CharsetUtil.US_ASCII);
    }

    @Benchmark
    public int userAgentValueWriteAsciiString() {
        ByteBufUtil.copy(userAgentAscii, 0, scratch, 0, userAgentAscii.length());
        return userAgentAscii.length();
    }
}
