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
package org.asynchttpclient.netty.handler;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.netty.NettyResponseBodyControl;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.Channels;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Netty reads {@code io.netty.compression.defaultMaxForwardBytes} once per JVM, so this test sets it in a fresh JVM. A
 * threshold of 0 hands on the output of every inflate step, as Netty's {@code JdkZlibDecoder} does; the decompressor
 * must still make progress, also while suspending after every part.
 */
public class Http1ContentDecompressorForwardThresholdTest {

    @Test
    public void aZeroForwardingThresholdStillDecodesTheBody() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // The child writes to a file rather than a pipe, so that a child that hangs cannot block the timed wait.
        Path log = Files.createTempFile("http1-content-decompressor-forward-threshold", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(java, "-Dio.netty.compression.defaultMaxForwardBytes=0",
                    "-cp", classPath, Check.class.getName())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            boolean exited = process.waitFor(60, SECONDS);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(exited, "the check did not finish within 60 seconds:\n" + output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("decoded "), output);
        } finally {
            if (process != null) {
                process.destroyForcibly();
                process.waitFor(10, SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }

    /** Runs in the fresh JVM; any failure exits with a non-zero status. */
    public static final class Check {

        public static void main(String[] args) {
            byte[] payload = new byte[200_000];
            new Random(91).nextBytes(payload);
            byte[] compressed = gzip(payload);

            Exchange whole = new Exchange("gzip", compressed, false);
            check(whole.last, "the body did not end");
            check(Arrays.equals(payload, whole.bytes.toByteArray()), "the body differs");

            Exchange suspended = new Exchange("gzip", compressed, true);
            for (int resumes = 0; !suspended.last; resumes++) {
                check(resumes < 100_000, "the suspended body did not end");
                int before = suspended.parts;
                suspended.control.resume();
                check(suspended.parts == before + 1 || suspended.last && suspended.parts == before,
                        "each resume must deliver exactly one more part");
            }
            check(Arrays.equals(payload, suspended.bytes.toByteArray()), "the suspended body differs");
            check(suspended.maxPart <= Http1ContentDecompressor.MAX_PART_SIZE, "a part of " + suspended.maxPart
                    + " bytes was handed on");

            // At this threshold zstd is left to Netty's decoder, which must still decode it rather than hang.
            Exchange zstd = new Exchange("zstd", Zstd.compress(payload), false);
            check(zstd.last, "the zstd body did not end");
            check(Arrays.equals(payload, zstd.bytes.toByteArray()), "the zstd body differs");

            System.out.println("decoded " + payload.length + " bytes in " + whole.parts + " parts, in "
                    + suspended.parts + " parts while suspending after each, and the zstd body in " + zstd.parts
                    + " parts");
        }

        private static byte[] gzip(byte[] payload) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(compressed)) {
                out.write(payload);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return compressed.toByteArray();
        }

        private static void check(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }

    /** A compressed response in one chunk, optionally suspended after every body part. */
    private static final class Exchange {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final NettyResponseBodyControl control;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int parts;
        int maxPart;
        boolean last;

        Exchange(String contentEncoding, byte[] compressed, boolean suspendEachPart) {
            NettyResponseFuture<?> future = new NettyResponseFuture<>(
                    new RequestBuilder().setUrl("http://localhost/").build(), new AsyncCompletionHandlerBase(),
                    null, 0, null, null, null);
            Channels.setAttribute(channel, future);
            control = NettyResponseBodyControl.create(future, channel, () -> {
            }, ignored -> {
            });
            channel.pipeline().addLast(new Http1ContentDecompressor(false, 0), new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof HttpContent) {
                            ByteBuf content = ((HttpContent) msg).content();
                            if (content.isReadable()) {
                                parts++;
                                maxPart = Math.max(maxPart, content.readableBytes());
                                byte[] data = new byte[content.readableBytes()];
                                content.readBytes(data);
                                bytes.writeBytes(data);
                            }
                            last = msg instanceof LastHttpContent;
                            if (suspendEachPart) {
                                control.suspend();
                            }
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }
            });
            DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, compressed.length);
            channel.writeInbound(response);
            channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(compressed)));
        }
    }
}
