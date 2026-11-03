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
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.Decompressor;
import io.netty.handler.codec.compression.ZstdDecompressor;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Netty's zstd decompressor hands out buffers of {@code io.netty.compression.defaultMaxForwardBytes}, which Netty
 * reads once per JVM. This test raises it in a fresh JVM and checks that {@link Http1ContentDecompressor} still hands
 * on parts of at most {@link Http1ContentDecompressor#MAX_PART_SIZE} bytes, one per resume, and releases what it
 * holds.
 */
public class Http1ContentDecompressorLargeOutputTest {

    private static final int FORWARD_BYTES = 4 * Http1ContentDecompressor.MAX_PART_SIZE;

    @Test
    public void splitsDecompressorOutputLargerThanOnePart() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // The child writes to a file rather than a pipe, so that a child that hangs cannot block the timed wait.
        Path log = Files.createTempFile("http1-content-decompressor-large-output", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(java, "-Dio.netty.compression.defaultMaxForwardBytes=" + FORWARD_BYTES,
                    "-cp", classPath, Check.class.getName())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            boolean exited = process.waitFor(60, SECONDS);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(exited, "the check did not finish within 60 seconds:\n" + output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("split output of "), output);
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
            byte[] payload = "zstd hands out large buffers\n".repeat(40_000).getBytes(StandardCharsets.US_ASCII);
            byte[] compressed = Zstd.compress(payload);
            TrackingAllocator allocator = new TrackingAllocator();

            // Without this, the check below would pass trivially.
            int rawOutput = largestRawOutput(compressed, allocator);
            check(rawOutput > Http1ContentDecompressor.MAX_PART_SIZE, "the zstd output is not larger than one part: "
                    + rawOutput);

            Exchange exchange = new Exchange(allocator, compressed);
            for (int resumes = 0; !exchange.last; resumes++) {
                check(resumes < 1_000, "the body must end");
                int before = exchange.parts;
                exchange.control.resume();
                check(exchange.parts == before + 1 || exchange.last && exchange.parts == before,
                        "each resume must deliver exactly one more part");
            }
            check(exchange.maxPart <= Http1ContentDecompressor.MAX_PART_SIZE, "a part of " + exchange.maxPart
                    + " bytes was handed on");
            check(Arrays.equals(payload, exchange.bytes.toByteArray()), "the body differs");
            check(!exchange.channel.finishAndReleaseAll(), "messages were left over");
            allocator.checkAllReleased();

            // Removing the handler while it carries over the rest of a split output releases that rest.
            Exchange removed = new Exchange(allocator, compressed);
            removed.control.resume();
            check(removed.parts == 1, "one part before removal, got " + removed.parts);
            removed.channel.pipeline().remove(Http1ContentDecompressor.class);
            check(!removed.channel.finishAndReleaseAll(), "messages were left over");
            allocator.checkAllReleased();

            System.out.println("split output of " + rawOutput + " bytes into parts of at most " + exchange.maxPart);
        }

        private static int largestRawOutput(byte[] compressed, TrackingAllocator allocator) {
            Decompressor decompressor = ZstdDecompressor.builder().build(allocator);
            int largest = 0;
            try {
                check(decompressor.status() == Decompressor.Status.NEED_INPUT, "a new decompressor needs input");
                decompressor.addInput(Unpooled.wrappedBuffer(compressed));
                while (decompressor.status() == Decompressor.Status.NEED_OUTPUT) {
                    ByteBuf output = decompressor.takeOutput();
                    largest = Math.max(largest, output.readableBytes());
                    output.release();
                }
            } finally {
                decompressor.close();
            }
            return largest;
        }

        private static void check(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }

    /** A zstd response, suspended from the start and again after every body part. */
    private static final class Exchange {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final NettyResponseBodyControl control;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int parts;
        int maxPart;
        boolean last;

        Exchange(TrackingAllocator allocator, byte[] compressed) {
            channel.config().setAllocator(allocator);
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
                            control.suspend();
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }
            });
            DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, "zstd");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, compressed.length);
            control.suspend();
            channel.writeInbound(response);
            channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(compressed)));
        }
    }

    /** Records every buffer it allocates, so that leaks of the decompressor's output can be detected. */
    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        private final List<ByteBuf> allocated = new ArrayList<>();

        TrackingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buf = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
            allocated.add(buf);
            return buf;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buf = new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
            allocated.add(buf);
            return buf;
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        void checkAllReleased() {
            for (ByteBuf buf : allocated) {
                Check.check(buf.refCnt() == 0, "a buffer of " + buf.capacity() + " bytes was not released");
            }
            allocated.clear();
        }
    }
}
