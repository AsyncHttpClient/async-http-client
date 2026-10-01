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
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.netty.handler.Http1ContentDecompressor;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Decodes one gzip response per operation on a keep-alive connection, through Netty's
 * {@link HttpContentDecompressor} and through {@link Http1ContentDecompressor}, which inflates gzip without an
 * {@code EmbeddedChannel} and with one {@code Inflater} per connection.
 * <p>
 * The compressed body arrives in direct pooled buffers of at most {@code chunkSize} bytes, as
 * {@code HttpClientCodec} hands it on under the default allocator.
 * <p>
 * Run with: {@code /tmp/run-jmh.sh Http1ContentDecompressorBenchmark -prof gc -f 1 -wi 5 -i 5}
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Http1ContentDecompressorBenchmark {

    @Param({"2048", "20480", "204800"})
    public int bodySize;

    @Param({"8192"})
    public int chunkSize;

    @Param({"netty", "ahc"})
    public String decoder;

    private EmbeddedChannel channel;
    private byte[] compressed;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        StringBuilder json = new StringBuilder();
        for (int i = 0; json.length() < bodySize; i++) {
            json.append("{\"id\":\"").append(i).append("\",\"impid\":\"").append(i % 7)
                    .append("\",\"price\":").append(i * 0.013).append(",\"adm\":\"<VAST version=\\\"4.0\\\">")
                    .append(Integer.toHexString(i * 7919)).append("</VAST>\"},");
        }
        byte[] body = json.substring(0, bodySize).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(body);
        }
        compressed = bos.toByteArray();

        @SuppressWarnings("deprecation")
        ChannelHandler handler = "netty".equals(decoder)
                ? new HttpContentDecompressor()
                : new Http1ContentDecompressor(false, 256L * 1024 * 1024);
        channel = new EmbeddedChannel();
        channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
        channel.pipeline().addLast(handler);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public int decodeResponse() {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, compressed.length);
        channel.writeInbound(response);
        for (int offset = 0; offset < compressed.length; offset += chunkSize) {
            int length = Math.min(chunkSize, compressed.length - offset);
            ByteBuf chunk = PooledByteBufAllocator.DEFAULT.directBuffer(length).writeBytes(compressed, offset, length);
            channel.writeInbound(offset + length == compressed.length
                    ? new DefaultLastHttpContent(chunk)
                    : new DefaultHttpContent(chunk));
        }
        int decoded = 0;
        for (Object msg; (msg = channel.readInbound()) != null; ) {
            if (msg instanceof HttpContent) {
                decoded += ((HttpContent) msg).content().readableBytes();
            }
            ReferenceCountUtil.release(msg);
        }
        return decoded;
    }
}
