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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.netty.NettyResponseBodyControl;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.Channels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how {@link Http1ContentDecompressor} stops inflating while the response is suspended, on an
 * {@link EmbeddedChannel}, so suspension, channel closure and handler removal can be driven deterministically and
 * reference counts checked exactly.
 */
public class Http1ContentDecompressorSuspensionTest {

    private static final byte[] TEXT = "the quick brown fox jumps over the lazy dog\n".repeat(2_000)
            .getBytes(StandardCharsets.US_ASCII);
    private static final int ZEROS_SIZE = 8 * 1024 * 1024;
    private static final byte[] ZEROS_GZIP = gzip(new byte[ZEROS_SIZE]);

    private static byte[] gzip(byte[] payload) {
        return compress(payload, GZIPOutputStream::new);
    }

    private interface Compressor {
        OutputStream wrap(OutputStream out) throws IOException;
    }

    private static byte[] compress(byte[] payload, Compressor compressor) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream out = compressor.wrap(compressed)) {
            out.write(payload);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return compressed.toByteArray();
    }

    private static byte[] rawDeflate(byte[] payload) {
        return compress(payload,
                out -> new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, true)));
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            out.writeBytes(array);
        }
        return out.toByteArray();
    }

    private static byte[] compress(String contentEncoding, byte[] payload) {
        switch (contentEncoding) {
            case "gzip":
                return gzip(payload);
            case "deflate":
                return compress(payload, DeflaterOutputStream::new);
            case "x-deflate":
                return rawDeflate(payload);
            default:
                throw new IllegalArgumentException(contentEncoding);
        }
    }

    private static byte[] toBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    private static HttpResponse response(String contentEncoding, int contentLength) {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        return response;
    }

    /** Collects what the decompressor hands on, releasing each message. */
    private static final class Body {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int parts;
        int maxPart;
        boolean last;
        String trailer;

        Body drain(EmbeddedChannel channel) {
            Object msg;
            while ((msg = channel.readInbound()) != null) {
                assertFalse(last, "nothing may follow the LastHttpContent");
                if (msg instanceof HttpContent) {
                    ByteBuf content = ((HttpContent) msg).content();
                    if (content.isReadable()) {
                        parts++;
                        maxPart = Math.max(maxPart, content.readableBytes());
                        bytes.writeBytes(toBytes(content));
                    }
                    last = msg instanceof LastHttpContent;
                    if (last) {
                        trailer = ((LastHttpContent) msg).trailingHeaders().get("x-trailer");
                    }
                }
                ReferenceCountUtil.release(msg);
            }
            return this;
        }
    }

    private enum Input { HEAP, DIRECT, POOLED_DIRECT }

    private static ByteBuf input(byte[] bytes, int offset, int length, Input kind) {
        switch (kind) {
            case DIRECT:
                return Unpooled.directBuffer(Math.max(length, 1)).writeBytes(bytes, offset, length);
            case POOLED_DIRECT:
                return PooledByteBufAllocator.DEFAULT.directBuffer(Math.max(length, 1)).writeBytes(bytes, offset, length);
            default:
                return Unpooled.wrappedBuffer(bytes, offset, length);
        }
    }

    /** A channel whose response is suspended by the handler after every body part, as a demand-driven consumer does. */
    private static final class SuspendedExchange {
        final EmbeddedChannel channel;
        final NettyResponseBodyControl control;
        final Http1ContentDecompressor decompressor;
        final List<ByteBuf> input = new ArrayList<>();
        final ByteBuf compressed;
        final boolean suspendedBeforeBody;

        SuspendedExchange(long maxDecompressedBytes) {
            this(maxDecompressedBytes, false);
        }

        SuspendedExchange(long maxDecompressedBytes, boolean suspendBeforeBody) {
            this("gzip", ZEROS_GZIP, ZEROS_GZIP.length, maxDecompressedBytes, suspendBeforeBody, true, Input.HEAP);
        }

        /**
         * Writes the body in fragments of {@code fragmentSize} bytes, the last one as the {@link LastHttpContent},
         * which carries the trailer {@code x-trailer: done}.
         */
        SuspendedExchange(String contentEncoding, byte[] body, int fragmentSize, long maxDecompressedBytes,
                          boolean suspendBeforeBody, boolean autoRead, Input kind) {
            suspendedBeforeBody = suspendBeforeBody;
            channel = new EmbeddedChannel();
            channel.config().setAutoRead(autoRead);
            NettyResponseFuture<?> future = new NettyResponseFuture<>(
                    new RequestBuilder().setUrl("http://localhost/").build(), new AsyncCompletionHandlerBase(),
                    null, 0, null, null, null);
            Channels.setAttribute(channel, future);
            control = NettyResponseBodyControl.create(future, channel, () -> {
            }, ignored -> {
            });
            decompressor = new Http1ContentDecompressor(false, maxDecompressedBytes);
            channel.pipeline().addLast(decompressor, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof HttpContent) {
                        control.suspend();
                    }
                    ctx.fireChannelRead(msg);
                }
            });
            channel.writeInbound(response(contentEncoding, body.length));
            ReferenceCountUtil.release(channel.readInbound());
            if (suspendBeforeBody) {
                control.suspend();
            }
            int offset = 0;
            do {
                int length = Math.min(fragmentSize, body.length - offset);
                ByteBuf fragment = input(body, offset, length, kind);
                input.add(fragment);
                offset += length;
                if (offset < body.length) {
                    channel.writeInbound(new DefaultHttpContent(fragment));
                } else {
                    LastHttpContent last = new DefaultLastHttpContent(fragment);
                    last.trailingHeaders().set("x-trailer", "done");
                    channel.writeInbound(last);
                }
            } while (offset < body.length);
            compressed = input.get(0);
        }

        /** Resumes until the body has ended, checking that each resume delivers exactly one more part. */
        Body resumeToTheEnd() {
            Body body = new Body().drain(channel);
            assertEquals(suspendedBeforeBody ? 0 : 1, body.parts, "nothing more may arrive while suspended");
            return resumeToTheEnd(body);
        }

        /** Continues a body that has been drained up to a suspension. */
        Body resumeToTheEnd(Body body) {
            for (int resumes = 0; !body.last; resumes++) {
                assertTrue(resumes < 10_000, "the body must end");
                int before = body.parts;
                control.resume();
                body.drain(channel);
                assertTrue(body.parts == before + 1 || body.last && body.parts == before,
                        "each resume must deliver exactly one more part");
            }
            assertTrue(body.maxPart <= Http1ContentDecompressor.MAX_PART_SIZE);
            assertInputReleased();
            assertFalse(channel.finishAndReleaseAll());
            return body;
        }

        void assertInputReleased() {
            for (ByteBuf fragment : input) {
                assertEquals(0, fragment.refCnt(), "an input buffer was not released");
            }
        }
    }

    @Test
    public void suspensionBeforeTheBodyHoldsEveryPartBack() {
        SuspendedExchange exchange = new SuspendedExchange(0, true);
        Body body = new Body().drain(exchange.channel);
        assertEquals(0, body.parts, "a response suspended from onResponseBodyStart must not receive any part");

        exchange.control.resume();
        body.drain(exchange.channel);
        assertEquals(1, body.parts);
        exchange.channel.pipeline().remove(Http1ContentDecompressor.class);
        assertEquals(0, exchange.compressed.refCnt());
        assertFalse(exchange.channel.finishAndReleaseAll());
    }

    @Test
    public void suspensionHoldsInputUntilReadIsRequested() {
        SuspendedExchange exchange = new SuspendedExchange(0);
        Body body = new Body().drain(exchange.channel);
        assertEquals(1, body.parts, "decompression must stop at the part that suspended the response");

        int resumes = 0;
        while (!body.last) {
            int before = body.parts;
            exchange.control.resume();
            resumes++;
            body.drain(exchange.channel);
            assertEquals(before + 1, body.parts, "each read request must inflate exactly one more part");
        }
        assertEquals(ZEROS_SIZE, body.bytes.size());
        assertTrue(body.maxPart <= Http1ContentDecompressor.MAX_PART_SIZE);
        assertEquals(resumes + 1, body.parts);
        assertEquals(0, exchange.compressed.refCnt());
        assertFalse(exchange.channel.finishAndReleaseAll());
    }

    @Test
    public void endOfTheBodyFollowsItsLastPartWithoutAnotherResume() {
        // Only parts with content wait for a resume. Once the body has been inflated in full, its end and trailers are
        // handed on even while the response is suspended, as for an unencoded body, so a consumer whose demand ended
        // with the last part still learns that the body is complete.
        byte[] payload = "the end follows\n".repeat(100).getBytes(StandardCharsets.US_ASCII);
        byte[] compressed = gzip(payload);
        SuspendedExchange exchange =
                new SuspendedExchange("gzip", compressed, compressed.length, 0, false, true, Input.HEAP);
        Body body = new Body().drain(exchange.channel);
        assertEquals(1, body.parts);
        assertTrue(body.last, "the end of a fully inflated body must not wait for a resume");
        assertEquals("done", body.trailer);
        assertArrayEquals(payload, body.bytes.toByteArray());
        assertFalse(exchange.channel.finishAndReleaseAll());

        // The same holds for a compressed response without any body bytes, suspended before its body.
        SuspendedExchange empty = new SuspendedExchange("gzip", new byte[0], 1, 0, true, true, Input.HEAP);
        Body emptyBody = new Body().drain(empty.channel);
        assertEquals(0, emptyBody.parts);
        assertTrue(emptyBody.last, "a suspended response without body bytes must still end");
        assertFalse(empty.channel.finishAndReleaseAll());
    }

    @Test
    public void channelClosureHandsOnHeldInputBeforeChannelInactive() {
        SuspendedExchange exchange = new SuspendedExchange(0);
        List<Object> events = new ArrayList<>();
        exchange.channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                events.add("inactive");
            }
        });
        Body body = new Body().drain(exchange.channel);
        assertEquals(1, body.parts);

        exchange.channel.pipeline().fireChannelInactive();
        body.drain(exchange.channel);
        assertTrue(body.last);
        assertEquals(ZEROS_SIZE, body.bytes.size());
        assertEquals(List.of("inactive"), events);
        assertEquals(0, exchange.compressed.refCnt());
        exchange.channel.finishAndReleaseAll();
    }

    @Test
    public void removalReleasesHeldInput() {
        SuspendedExchange exchange = new SuspendedExchange(0);
        Body body = new Body().drain(exchange.channel);
        assertEquals(1, body.parts);
        assertEquals(1, exchange.compressed.refCnt(), "the compressed input is held while suspended");

        exchange.channel.pipeline().remove(Http1ContentDecompressor.class);
        assertEquals(0, exchange.compressed.refCnt());
        assertFalse(exchange.channel.finishAndReleaseAll());
    }

    @Test
    public void limitExceededWhileResumingFailsTheExchangeAndReleasesInput() {
        SuspendedExchange exchange = new SuspendedExchange(1024 * 1024);
        Body body = new Body().drain(exchange.channel);
        DecompressionException failure = null;
        while (failure == null) {
            assertFalse(body.last);
            int before = body.parts;
            exchange.control.resume();
            body.drain(exchange.channel);
            try {
                exchange.channel.checkException();
            } catch (DecompressionException e) {
                failure = e;
            }
            assertTrue(failure != null || body.parts > before, "each read request must inflate more or fail");
        }
        assertTrue(failure.getMessage().contains("maximum decompressed size of 1048576 bytes"));
        assertTrue(body.bytes.size() <= 1024 * 1024);
        assertEquals(0, exchange.compressed.refCnt());

        // What is left of the failed response is dropped, its end included.
        long delivered = body.bytes.size();
        exchange.channel.read();
        body.drain(exchange.channel);
        assertFalse(body.last);
        assertEquals(delivered, body.bytes.size());
        assertFalse(exchange.channel.finishAndReleaseAll());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void suspensionCarriesOverIntoTheNextGzipMember(boolean autoRead) {
        byte[] first = new byte[300 * 1024];
        byte[] second = "x".repeat(300 * 1024).getBytes(StandardCharsets.US_ASCII);
        SuspendedExchange exchange = new SuspendedExchange("gzip", concat(gzip(first), gzip(second)), 512, 0, true,
                autoRead, Input.HEAP);
        assertArrayEquals(concat(first, second), exchange.resumeToTheEnd().bytes.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void everyCodingDeliversBoundedPartsAcrossRepeatedSuspension(boolean autoRead) {
        // Incompressible, highly compressible and text data, so that both large and small inflater output occurs.
        byte[] random = new byte[130_000];
        new Random(29).nextBytes(random);
        byte[] payload = concat(random, new byte[200_000], TEXT);
        for (String contentEncoding : List.of("gzip", "deflate", "x-deflate")) {
            SuspendedExchange exchange = new SuspendedExchange(contentEncoding, compress(contentEncoding, payload),
                    4096, 0, true, autoRead, Input.HEAP);
            assertArrayEquals(payload, exchange.resumeToTheEnd().bytes.toByteArray(), contentEncoding);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIRECT", "POOLED_DIRECT"})
    public void aDirectInputPausedMidChunkIsKeptUntilTheInflaterHasUsedItUp(String kind) {
        // One chunk inflates to many parts, so the response pauses with most of the chunk unread. The inflater
        // still points into the chunk's memory, which must stay allocated until it has been inflated.
        byte[] payload = concat(TEXT, new byte[400_000], TEXT);
        byte[] compressed = gzip(payload);
        SuspendedExchange exchange = new SuspendedExchange("gzip", compressed, compressed.length, 0, false, true,
                Input.valueOf(kind));
        Body body = new Body().drain(exchange.channel);
        assertEquals(1, body.parts);
        assertTrue(exchange.decompressor.heldInputBytes() > 0, "the chunk must have been paused mid-way");
        assertEquals(1, exchange.compressed.refCnt(), "the decompressor must hold the chunk while paused");

        assertArrayEquals(payload, exchange.resumeToTheEnd(body).bytes.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"removal", "closure", "failure"})
    public void aHeldDirectInputIsReleasedWhenTheResponseEndsEarly(String end) {
        SuspendedExchange exchange = new SuspendedExchange("gzip", ZEROS_GZIP, ZEROS_GZIP.length,
                "failure".equals(end) ? 1024 * 1024 : 0, false, true, Input.DIRECT);
        new Body().drain(exchange.channel);
        assertEquals(1, exchange.compressed.refCnt(), "the decompressor must hold the chunk while paused");

        switch (end) {
            case "removal":
                exchange.channel.pipeline().remove(Http1ContentDecompressor.class);
                break;
            case "closure":
                exchange.channel.pipeline().fireChannelInactive();
                break;
            default:
                for (int resumes = 0; exchange.compressed.refCnt() > 0; resumes++) {
                    assertTrue(resumes < 1_000, "the limit must fail the response");
                    exchange.control.resume();
                    new Body().drain(exchange.channel);
                }
                DecompressionException failure =
                        assertThrows(DecompressionException.class, exchange.channel::checkException);
                assertTrue(failure.getMessage().contains("maximum decompressed size"));
        }
        assertEquals(0, exchange.compressed.refCnt());
        exchange.channel.finishAndReleaseAll();
    }

    @Test
    public void outputTheInflaterStillHoldsIsHandedOnAfterAResume() {
        // Raw deflate has no trailer, so the inflater can take a chunk's last byte while the output buffer is full,
        // and pause with output still held but no input left. A resume must still hand that output on.
        int hits = 0;
        int first = Http1ContentDecompressor.MAX_PART_SIZE + 1;
        for (int size = first; size <= first + 2_000; size += 13) {
            // Zeros, so that the input past the first part is a few bytes, which the inflater has already read.
            byte[] payload = new byte[size];
            byte[] compressed = rawDeflate(payload);
            SuspendedExchange exchange = new SuspendedExchange("deflate", compressed, compressed.length, 0, false,
                    true, Input.HEAP);
            Body body = new Body().drain(exchange.channel);
            if (exchange.decompressor.holdsInflaterOutput() && exchange.decompressor.heldInputBytes() == 0) {
                hits++;
            }
            assertArrayEquals(payload, exchange.resumeToTheEnd(body).bytes.toByteArray(), "size " + size);
        }
        assertTrue(hits > 0, "no size paused with output held and no input left");
    }
}
