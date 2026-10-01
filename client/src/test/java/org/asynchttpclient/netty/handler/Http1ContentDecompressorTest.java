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

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Http1ContentDecompressor} inflates gzip and deflate itself instead of through Netty's
 * {@link HttpContentDecompressor}. Most tests here feed both handlers the same body, cut into chunks in
 * several ways, and require the same decoded bytes and the same error, so the two cannot drift apart.
 */
public class Http1ContentDecompressorTest {

    private static final int FTEXT = 0x01;
    private static final int FHCRC = 0x02;
    private static final int FEXTRA = 0x04;
    private static final int FNAME = 0x08;
    private static final int FCOMMENT = 0x10;

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] TEXT = "{\"id\":\"1\",\"seatbid\":[{\"bid\":[{\"price\":1.5}]}]}".repeat(200)
            .getBytes(StandardCharsets.US_ASCII);
    // Large and incompressible, so one chunk inflates past the 64 KiB forwarding threshold only when it is
    // big itself, and the response spans many chunks.
    private static final byte[] RANDOM = randomBytes(300 * 1024, 42);
    // Compressible enough that a single chunk inflates well past the forwarding threshold.
    private static final byte[] ZEROS = new byte[2 * 1024 * 1024];

    private static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] gzip(byte[] payload) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private static byte[] deflate(byte[] payload, boolean nowrap) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, nowrap);
        try {
            deflater.setInput(payload);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                bos.write(buffer, 0, deflater.deflate(buffer));
            }
            return bos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * A gzip member with every optional header field the flags ask for, built by hand because
     * {@link GZIPOutputStream} writes none of them.
     */
    private static byte[] gzipWithHeaderFields(byte[] payload, int flags) {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x1f);
        header.write(0x8b);
        header.write(8);
        header.write(flags);
        header.writeBytes(new byte[]{1, 2, 3, 4, 0, (byte) 255});
        if ((flags & FEXTRA) != 0) {
            byte[] extra = "AB\u0003\u0000xyz-extra-field".getBytes(StandardCharsets.US_ASCII);
            header.write(extra.length & 0xff);
            header.write(extra.length >>> 8);
            header.writeBytes(extra);
        }
        if ((flags & FNAME) != 0) {
            header.writeBytes("response.json".getBytes(StandardCharsets.US_ASCII));
            header.write(0);
        }
        if ((flags & FCOMMENT) != 0) {
            header.writeBytes("a comment".getBytes(StandardCharsets.US_ASCII));
            header.write(0);
        }
        if ((flags & FHCRC) != 0) {
            CRC32 crc = new CRC32();
            crc.update(header.toByteArray());
            int crc16 = (int) crc.getValue() & 0xffff;
            header.write(crc16 & 0xff);
            header.write(crc16 >>> 8);
        }
        ByteArrayOutputStream member = new ByteArrayOutputStream();
        member.writeBytes(header.toByteArray());
        member.writeBytes(deflate(payload, true));
        CRC32 crc = new CRC32();
        crc.update(payload);
        writeIntLE(member, (int) crc.getValue());
        writeIntLE(member, payload.length);
        return member.toByteArray();
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            bos.writeBytes(part);
        }
        return bos.toByteArray();
    }

    private static byte[] withByte(byte[] body, int index, int value) {
        byte[] copy = body.clone();
        copy[index < 0 ? copy.length + index : index] = (byte) value;
        return copy;
    }

    private static byte[] flipped(byte[] body, int index) {
        byte[] copy = body.clone();
        int i = index < 0 ? copy.length + index : index;
        copy[i] = (byte) ~copy[i];
        return copy;
    }

    private static final class Body {
        final String name;
        final String contentEncoding;
        final byte[] encoded;

        Body(String name, String contentEncoding, byte[] encoded) {
            this.name = name;
            this.contentEncoding = contentEncoding;
            this.encoded = encoded;
        }
    }

    private static List<Body> bodies() {
        List<Body> bodies = new ArrayList<>();
        byte[] gzipText = gzip(TEXT);
        byte[] gzipRandom = gzip(RANDOM);
        bodies.add(new Body("gzip empty", "gzip", gzip(EMPTY)));
        bodies.add(new Body("gzip no body", "gzip", EMPTY));
        bodies.add(new Body("gzip text", "gzip", gzipText));
        bodies.add(new Body("gzip random", "gzip", gzipRandom));
        bodies.add(new Body("gzip zeros", "gzip", gzip(ZEROS)));
        bodies.add(new Body("x-gzip mixed case", "X-GZip", gzipText));
        bodies.add(new Body("gzip all header fields", "gzip",
                gzipWithHeaderFields(TEXT, FTEXT | FHCRC | FEXTRA | FNAME | FCOMMENT)));
        bodies.add(new Body("gzip name and comment", "gzip", gzipWithHeaderFields(TEXT, FNAME | FCOMMENT)));
        bodies.add(new Body("gzip extra only", "gzip", gzipWithHeaderFields(RANDOM, FEXTRA)));
        bodies.add(new Body("gzip concatenated", "gzip", concat(gzipText, gzip(RANDOM), gzip(EMPTY), gzipText)));
        bodies.add(new Body("deflate zlib", "deflate", deflate(TEXT, false)));
        bodies.add(new Body("deflate zlib random", "deflate", deflate(RANDOM, false)));
        bodies.add(new Body("deflate raw", "deflate", deflate(TEXT, true)));
        bodies.add(new Body("x-deflate raw zeros", "X-Deflate", deflate(ZEROS, true)));
        bodies.add(new Body("deflate empty raw", "deflate", deflate(EMPTY, true)));
        bodies.add(new Body("deflate trailing bytes", "deflate", concat(deflate(TEXT, false), TEXT)));

        bodies.add(new Body("gzip bad crc", "gzip", flipped(gzipText, -8)));
        bodies.add(new Body("gzip bad isize", "gzip", flipped(gzipText, -1)));
        bodies.add(new Body("gzip bad header crc", "gzip",
                flipped(gzipWithHeaderFields(TEXT, FHCRC | FNAME), 24)));
        bodies.add(new Body("gzip bad magic", "gzip", withByte(gzipText, 0, 0x1e)));
        bodies.add(new Body("gzip second magic byte ignored", "gzip", withByte(gzipText, 1, 0)));
        bodies.add(new Body("gzip bad method", "gzip", withByte(gzipText, 2, 7)));
        bodies.add(new Body("gzip reserved flag", "gzip", withByte(gzipText, 3, 0x20)));
        bodies.add(new Body("gzip corrupt data", "gzip", flipped(gzipRandom, gzipRandom.length / 2)));
        bodies.add(new Body("gzip truncated data", "gzip", Arrays.copyOf(gzipRandom, gzipRandom.length / 2)));
        bodies.add(new Body("gzip truncated trailer", "gzip", Arrays.copyOf(gzipText, gzipText.length - 3)));
        bodies.add(new Body("gzip truncated header", "gzip", Arrays.copyOf(gzipText, 6)));
        bodies.add(new Body("gzip short garbage after member", "gzip", concat(gzipText, new byte[]{'\r', '\n'})));
        bodies.add(new Body("gzip long garbage after member", "gzip", concat(gzipText, TEXT)));
        bodies.add(new Body("deflate corrupt data", "deflate", flipped(deflate(RANDOM, false), 1000)));
        bodies.add(new Body("deflate bad adler", "deflate", flipped(deflate(TEXT, false), -1)));
        bodies.add(new Body("deflate one byte", "deflate", new byte[]{0x78}));
        bodies.add(new Body("deflate preset dictionary", "deflate", new byte[]{0x78, (byte) 0xbb, 0, 0, 0, 1, 1, 1}));
        return bodies;
    }

    private static List<int[]> splits(int length) {
        List<int[]> splits = new ArrayList<>();
        splits.add(new int[]{length});
        splits.add(sizes(length, 8192));
        // Tiny chunks put every header and trailer boundary on a chunk edge; a large body gets them only
        // from the random cuts.
        boolean small = length <= 64 * 1024;
        if (small) {
            splits.add(sizes(length, 1));
            splits.add(sizes(length, 7));
        }
        Random random = new Random(length);
        for (int i = small ? 0 : 1; i < 3; i++) {
            List<Integer> sizes = new ArrayList<>();
            int remaining = length;
            while (remaining > 0) {
                int size = Math.min(remaining, 1 + random.nextInt(i == 0 ? 16 : 9000));
                sizes.add(size);
                remaining -= size;
            }
            splits.add(sizes.stream().mapToInt(Integer::intValue).toArray());
        }
        return splits;
    }

    private static int[] sizes(int length, int chunk) {
        int[] sizes = new int[Math.max(1, (length + chunk - 1) / chunk)];
        Arrays.fill(sizes, chunk);
        if (length % chunk != 0) {
            sizes[sizes.length - 1] = length % chunk;
        }
        if (length == 0) {
            sizes[0] = 0;
        }
        return sizes;
    }

    private static final class Outcome {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final List<ByteBuf> inputs = new ArrayList<>();
        HttpResponse head;
        int parts;
        boolean ended;
        Throwable failure;
    }

    private static HttpResponse response(String contentEncoding, long contentLength) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        if (contentEncoding != null) {
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
        }
        if (contentLength >= 0) {
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        }
        return response;
    }

    private enum Kind { HEAP, DIRECT, POOLED_DIRECT, COMPOSITE, READ_ONLY }

    private static ByteBuf buffer(byte[] bytes, int offset, int length, Kind kind) {
        if (kind == Kind.COMPOSITE && length > 1) {
            // Two direct components, so the inflater cannot be handed one NIO buffer.
            int half = length / 2;
            return Unpooled.compositeBuffer(2)
                    .addComponent(true, Unpooled.directBuffer(half).writeBytes(bytes, offset, half))
                    .addComponent(true, Unpooled.directBuffer(length - half)
                            .writeBytes(bytes, offset + half, length - half));
        }
        int capacity = Math.max(length, 1);
        ByteBuf buf = kind == Kind.DIRECT ? Unpooled.directBuffer(capacity)
                : kind == Kind.POOLED_DIRECT ? PooledByteBufAllocator.DEFAULT.directBuffer(capacity)
                : Unpooled.buffer(capacity);
        if (kind == Kind.READ_ONLY) {
            // Heap, but without an accessible array, so its NIO view goes to the inflater.
            return buf.writeBytes(bytes, offset, length).asReadOnly();
        }
        return buf.writeBytes(bytes, offset, length);
    }

    /**
     * Sends one response through the channel and collects what comes out, stopping at the first error.
     */
    private static Outcome exchange(EmbeddedChannel channel, String contentEncoding, byte[] encoded, int[] sizes,
                                    Kind kind) {
        Outcome outcome = new Outcome();
        List<Object> messages = new ArrayList<>();
        messages.add(response(contentEncoding, encoded.length));
        int offset = 0;
        for (int i = 0; i < sizes.length; i++) {
            ByteBuf content = buffer(encoded, offset, sizes[i], kind);
            outcome.inputs.add(content);
            offset += sizes[i];
            messages.add(i == sizes.length - 1 ? new DefaultLastHttpContent(content) : new DefaultHttpContent(content));
        }
        for (int i = 0; i < messages.size(); i++) {
            try {
                channel.writeInbound(messages.get(i));
            } catch (Throwable t) {
                outcome.failure = t;
                for (int j = i + 1; j < messages.size(); j++) {
                    ReferenceCountUtil.release(messages.get(j));
                }
                break;
            } finally {
                drain(channel, outcome);
            }
        }
        return outcome;
    }

    // Netty keeps an unread input as its cumulation until the channel is finished, so this is checked after.
    private static void assertInputsReleased(Outcome outcome, String scenario) {
        for (ByteBuf input : outcome.inputs) {
            assertEquals(0, input.refCnt(), scenario + ": an input buffer was not released");
        }
    }

    private static void drain(EmbeddedChannel channel, Outcome outcome) {
        for (Object msg; (msg = channel.readInbound()) != null; ) {
            try {
                if (msg instanceof HttpResponse) {
                    assertNull(outcome.head, "a second response head");
                    assertFalse(msg instanceof HttpContent, "the head must not carry content");
                    outcome.head = (HttpResponse) msg;
                }
                if (msg instanceof HttpContent) {
                    ByteBuf content = ((HttpContent) msg).content();
                    if (content.isReadable()) {
                        outcome.parts++;
                        content.readBytes(outcome.body, content.readableBytes());
                    }
                    if (msg instanceof LastHttpContent) {
                        outcome.ended = true;
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private static EmbeddedChannel channel(ChannelHandler decompressor, ByteBufAllocator allocator) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(decompressor);
        return channel;
    }

    private static UnpooledByteBufAllocator allocator() {
        return new UnpooledByteBufAllocator(false, true);
    }

    @SuppressWarnings("deprecation")
    private static HttpContentDecompressor nettyDecompressor() {
        return new HttpContentDecompressor();
    }

    @Test
    void decodesLikeNettyForEveryBodyAndSplit() {
        int checked = 0;
        for (Body body : bodies()) {
            for (int[] sizes : splits(body.encoded.length)) {
                for (Kind kind : Kind.values()) {
                    String scenario = body.name + ", " + sizes.length + " chunks, " + kind;
                    UnpooledByteBufAllocator ahcAllocator = allocator();
                    EmbeddedChannel ahc = channel(new Http1ContentDecompressor(false, 0), ahcAllocator);
                    EmbeddedChannel netty = channel(nettyDecompressor(), allocator());
                    Outcome expected = exchange(netty, body.contentEncoding, body.encoded, sizes, kind);
                    Outcome actual = exchange(ahc, body.contentEncoding, body.encoded, sizes, kind);

                    assertSameOutcome(expected, actual, scenario);
                    ahc.finishAndReleaseAll();
                    try {
                        netty.finishAndReleaseAll();
                    } catch (DecompressionException ignored) {
                        // Netty's decoder runs once more over a corrupt body when its channel is torn down.
                    }
                    assertEquals(0, ahcAllocator.metric().usedHeapMemory(), scenario + ": heap left allocated");
                    assertInputsReleased(actual, scenario);
                    assertInputsReleased(expected, scenario + " (Netty)");
                    checked++;
                }
            }
        }
        assertTrue(checked > 300, "only " + checked + " scenarios ran");
    }

    private static void assertSameOutcome(Outcome expected, Outcome actual, String scenario) {
        assertNotNull(actual.head, scenario);
        assertEquals(expected.head.headers().entries().toString(), actual.head.headers().entries().toString(),
                scenario + ": headers");
        if (expected.failure == null) {
            assertNull(actual.failure, scenario + ": unexpected failure");
            assertArrayEquals(expected.body.toByteArray(), actual.body.toByteArray(), scenario + ": body");
            assertEquals(expected.ended, actual.ended, scenario + ": end of response");
            assertEquals(expected.parts, actual.parts, scenario + ": parts");
        } else {
            assertNotNull(actual.failure, scenario + ": expected " + expected.failure);
            assertEquals(expected.failure.getClass(), actual.failure.getClass(), scenario + ": failure type");
            assertEquals(expected.failure.getMessage(), actual.failure.getMessage(), scenario + ": failure message");
            assertFalse(actual.ended, scenario + ": failed response must not end normally");
        }
    }

    @Test
    void forwardsOnePartPerChunkUnlessItInflatesPastTheThreshold() {
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
        byte[] text = gzip(TEXT);
        Outcome small = exchange(channel, "gzip", text, new int[]{text.length}, Kind.DIRECT);
        assertDecoded(TEXT, small);
        assertEquals(1, small.parts);
        // 2 MiB from one chunk leaves in 64 KiB parts rather than as one buffer grown to 2 MiB.
        byte[] zeros = gzip(ZEROS);
        Outcome large = exchange(channel, "gzip", zeros, new int[]{zeros.length}, Kind.DIRECT);
        assertDecoded(ZEROS, large);
        assertEquals(ZEROS.length / (64 * 1024), large.parts);
        channel.finishAndReleaseAll();
    }

    @Test
    void stopsWhenAHandlerFurtherOnRemovesItMidChunk() {
        UnpooledByteBufAllocator allocator = allocator();
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator);
        int[] parts = new int[1];
        List<Object> afterRemoval = new ArrayList<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (parts[0] > 0) {
                    afterRemoval.add(msg);
                } else if (msg instanceof HttpContent && ((HttpContent) msg).content().isReadable()) {
                    parts[0]++;
                    ctx.pipeline().remove(Http1ContentDecompressor.class);
                }
                ReferenceCountUtil.release(msg);
            }
        });
        byte[] encoded = gzip(ZEROS);
        channel.writeInbound(response("gzip", encoded.length));
        channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(encoded)));
        assertEquals(1, parts[0]);
        assertEquals(List.of(), afterRemoval);
        assertNull(channel.pipeline().get(Http1ContentDecompressor.class));
        channel.finishAndReleaseAll();
        assertEquals(0, allocator.metric().usedHeapMemory());
    }

    @Test
    void closingTheChannelMidResponseReleasesWhatItHolds() {
        UnpooledByteBufAllocator allocator = allocator();
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator);
        byte[] encoded = gzipWithHeaderFields(TEXT, FCOMMENT);
        channel.writeInbound(response("gzip", -1));
        channel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(encoded, 0, 7)));
        assertTrue(allocator.metric().usedHeapMemory() > 0);
        channel.close();
        assertEquals(0, allocator.metric().usedHeapMemory());
        channel.finishAndReleaseAll();
    }

    @Test
    void reusesTheInflaterAcrossResponsesOnOneConnection() {
        UnpooledByteBufAllocator allocator = allocator();
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator);
        byte[] gzipText = gzip(TEXT);
        byte[] zlib = deflate(RANDOM, false);
        byte[] raw = deflate(TEXT, true);

        assertDecoded(TEXT, exchange(channel, "gzip", gzipText, new int[]{gzipText.length}, Kind.DIRECT));
        Outcome identity = exchange(channel, null, TEXT, new int[]{100, TEXT.length - 100}, Kind.HEAP);
        assertDecoded(TEXT, identity);
        assertEquals(String.valueOf(TEXT.length), identity.head.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertDecoded(RANDOM, exchange(channel, "deflate", zlib, sizes(zlib.length, 4000), Kind.DIRECT));
        assertDecoded(TEXT, exchange(channel, "deflate", raw, sizes(raw.length, 3), Kind.HEAP));
        // A response cut short leaves state behind that the next one must not see.
        Outcome cut = exchange(channel, "gzip", Arrays.copyOf(gzipText, 40), new int[]{40}, Kind.DIRECT);
        assertNull(cut.failure);
        assertDecoded(TEXT, exchange(channel, "gzip", gzipText, sizes(gzipText.length, 5), Kind.DIRECT));
        // So does a corrupt one.
        Outcome corrupt = exchange(channel, "gzip", flipped(gzipText, -8), new int[]{gzipText.length}, Kind.DIRECT);
        assertInstanceOf(DecompressionException.class, corrupt.failure);
        assertDecoded(TEXT, exchange(channel, "gzip", gzipText, new int[]{gzipText.length}, Kind.DIRECT));

        channel.finishAndReleaseAll();
        assertEquals(0, allocator.metric().usedHeapMemory());
    }

    @Test
    void dropsWhatIsLeftOfAFailedResponse() {
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
        byte[] corrupt = withByte(gzip(TEXT), 0, 0);
        channel.writeInbound(response("gzip", -1));
        assertInstanceOf(HttpResponse.class, channel.readInbound());
        try {
            channel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(corrupt)));
        } catch (DecompressionException expected) {
            // the exchange is failed by this
        }
        ByteBuf rest = Unpooled.wrappedBuffer(TEXT);
        channel.writeInbound(new DefaultLastHttpContent(rest));
        assertEquals(0, rest.refCnt());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    private static void assertDecoded(byte[] expected, Outcome outcome) {
        assertNull(outcome.failure);
        assertTrue(outcome.ended);
        assertArrayEquals(expected, outcome.body.toByteArray());
    }

    @Test
    void rewritesHeadersLikeNetty() {
        byte[] encoded = gzip(TEXT);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
        Outcome outcome = exchange(channel, "gzip", encoded, sizes(encoded.length, 50), Kind.HEAP);
        channel.finishAndReleaseAll();
        assertDecoded(TEXT, outcome);
        assertNull(outcome.head.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertNull(outcome.head.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(HttpHeaderValues.CHUNKED.toString(),
                outcome.head.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
    }

    @Test
    void keepEncodingHeaderLeavesContentEncoding() {
        byte[] encoded = gzip(RANDOM);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(true, 0), allocator());
        Outcome outcome = exchange(channel, "X-Gzip", encoded, sizes(encoded.length, 2000), Kind.HEAP);
        channel.finishAndReleaseAll();
        assertDecoded(RANDOM, outcome);
        assertEquals("X-Gzip", outcome.head.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertNull(outcome.head.headers().get(HttpHeaderNames.CONTENT_LENGTH));
    }

    @Test
    void fullResponseIsSplitIntoHeadAndBodyLikeNetty() {
        byte[] encoded = gzip(RANDOM);
        List<Outcome> outcomes = new ArrayList<>();
        for (ChannelHandler decompressor : new ChannelHandler[]{nettyDecompressor(),
                new Http1ContentDecompressor(false, 0)}) {
            EmbeddedChannel channel = channel(decompressor, allocator());
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(encoded));
            response.headers().set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, encoded.length);
            response.trailingHeaders().set("x-trailer", "1");
            channel.writeInbound(response);
            Outcome outcome = new Outcome();
            drain(channel, outcome);
            channel.finishAndReleaseAll();
            outcomes.add(outcome);
        }
        assertSameOutcome(outcomes.get(0), outcomes.get(1), "full response");
        assertDecoded(RANDOM, outcomes.get(1));
    }

    @Test
    void alternatesWithEncodingsLeftToNettyOnOneConnection() {
        byte[] snappyText = snappy(TEXT);
        byte[] snappyRandom = snappy(RANDOM);
        byte[] gzipText = gzip(TEXT);
        byte[] zlib = deflate(RANDOM, false);
        UnpooledByteBufAllocator allocator = allocator();
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator);

        assertDecoded(TEXT, exchange(channel, "snappy", snappyText, sizes(snappyText.length, 10), Kind.HEAP));
        assertDecoded(TEXT, exchange(channel, "gzip", gzipText, sizes(gzipText.length, 10), Kind.DIRECT));
        assertDecoded(RANDOM, exchange(channel, "snappy", snappyRandom, sizes(snappyRandom.length, 5000),
                Kind.DIRECT));
        assertDecoded(RANDOM, exchange(channel, "deflate", zlib, sizes(zlib.length, 5000), Kind.DIRECT));
        // A snappy response that never ends leaves Netty's decoder behind; the next gzip response must not care.
        channel.writeInbound(response("snappy", -1),
                new DefaultHttpContent(Unpooled.wrappedBuffer(snappyRandom, 0, 3000)));
        drain(channel, new Outcome());
        assertDecoded(TEXT, exchange(channel, "gzip", gzipText, new int[]{gzipText.length}, Kind.HEAP));
        assertDecoded(TEXT, exchange(channel, "snappy", snappyText, new int[]{snappyText.length}, Kind.HEAP));

        channel.finishAndReleaseAll();
        assertEquals(0, allocator.metric().usedHeapMemory());
    }

    private static byte[] snappy(byte[] payload) {
        EmbeddedChannel encoder = new EmbeddedChannel(new SnappyFrameEncoder());
        encoder.writeOutbound(Unpooled.wrappedBuffer(payload));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (ByteBuf buf; (buf = encoder.readOutbound()) != null; ) {
            try {
                buf.readBytes(bos, buf.readableBytes());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                buf.release();
            }
        }
        encoder.finishAndReleaseAll();
        return bos.toByteArray();
    }

    @Test
    void trailersSurviveDecompression() {
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
        byte[] encoded = gzip(TEXT);
        channel.writeInbound(response("gzip", -1));
        LastHttpContent last = new DefaultLastHttpContent(Unpooled.wrappedBuffer(encoded));
        last.trailingHeaders().set("x-checksum", "abc");
        channel.writeInbound(last);
        assertInstanceOf(HttpResponse.class, channel.readInbound());
        HttpContent body = channel.readInbound();
        assertEquals(TEXT.length, body.content().readableBytes());
        body.release();
        LastHttpContent end = channel.readInbound();
        assertEquals("abc", end.trailingHeaders().get("x-checksum"));
        assertFalse(end.content().isReadable());
        end.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void continueResponseIsPassedThrough() {
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
        HttpResponse interim = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        interim.headers().set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
        channel.writeInbound(interim, LastHttpContent.EMPTY_LAST_CONTENT);
        HttpResponse passed = channel.readInbound();
        assertEquals("gzip", passed.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertInstanceOf(LastHttpContent.class, channel.readInbound());
        byte[] encoded = gzip(TEXT);
        assertDecoded(TEXT, exchange(channel, "gzip", encoded, new int[]{encoded.length}, Kind.HEAP));
        channel.finishAndReleaseAll();
    }

    @Test
    void limitFailsTheResponseOnceTheBodyPassesIt() {
        byte[] encoded = gzip(ZEROS);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, ZEROS.length - 1), allocator());
        Outcome outcome = exchange(channel, "gzip", encoded, sizes(encoded.length, 100), Kind.DIRECT);
        assertInstanceOf(DecompressionException.class, outcome.failure);
        assertEquals("HTTP/1.1 response body exceeds the maximum decompressed size of " + (ZEROS.length - 1) + " bytes",
                outcome.failure.getMessage());
        assertTrue(outcome.body.size() < ZEROS.length);
        // The limit counts one response, not the connection.
        assertDecoded(TEXT, exchange(channel, "gzip", gzip(TEXT), new int[]{50, 56}, Kind.DIRECT));
        channel.finishAndReleaseAll();
    }

    @Test
    void limitAppliesToDeflateToo() {
        byte[] encoded = deflate(ZEROS, false);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 100_000), allocator());
        Outcome outcome = exchange(channel, "deflate", encoded, new int[]{encoded.length}, Kind.HEAP);
        assertInstanceOf(DecompressionException.class, outcome.failure);
        assertTrue(outcome.body.size() <= 100_000);
        channel.finishAndReleaseAll();
    }

    @Test
    void idleInflatersAreEndedWithTheThreadLocals() throws Exception {
        onFreshThread(() -> {
            byte[] encoded = gzip(TEXT);
            EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
            assertDecoded(TEXT, exchange(channel, "gzip", encoded, new int[]{encoded.length}, Kind.HEAP));
            channel.finishAndReleaseAll();
            Inflater pooled = Http1ContentDecompressor.idleInflaters(true).peekLast();
            assertNotNull(pooled);
            // What an event loop does on its way out.
            FastThreadLocal.removeAll();
            // An open inflater with no input returns 0 here; an ended one throws.
            assertThrows(NullPointerException.class, () -> pooled.inflate(new byte[1]));
        });
    }

    @Test
    void limitCountsEveryMemberOfAConcatenatedBody() {
        // Each member is under the limit on its own; together they are over it.
        byte[] member = gzip(Arrays.copyOf(ZEROS, 600_000));
        byte[] encoded = concat(member, member);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 1_000_000), allocator());
        Outcome outcome = exchange(channel, "gzip", encoded, new int[]{encoded.length}, Kind.HEAP);
        assertInstanceOf(DecompressionException.class, outcome.failure);
        assertTrue(outcome.body.size() <= 1_000_000);
        channel.finishAndReleaseAll();
    }

    @Test
    void limitAllowsABodyOfExactlyTheLimit() {
        byte[] encoded = gzip(ZEROS);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, ZEROS.length), allocator());
        assertDecoded(ZEROS, exchange(channel, "gzip", encoded, new int[]{encoded.length}, Kind.HEAP));
        channel.finishAndReleaseAll();
    }

    @Test
    void removingTheHandlerMidResponseReleasesWhatItHolds() {
        UnpooledByteBufAllocator allocator = allocator();
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator);
        byte[] encoded = gzipWithHeaderFields(TEXT, FNAME);
        channel.writeInbound(response("gzip", -1));
        // Five bytes of a header are held back until the rest arrives.
        channel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(encoded, 0, 5)));
        assertTrue(allocator.metric().usedHeapMemory() > 0);
        channel.pipeline().removeFirst();
        assertEquals(0, allocator.metric().usedHeapMemory());
        ReferenceCountUtil.release(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void requestsReadsLikeNettyWhenAutoReadIsOff() {
        byte[] header = gzipWithHeaderFields(TEXT, FNAME | FCOMMENT);
        byte[] zeros = gzip(ZEROS);
        byte[] raw = deflate(TEXT, true);
        byte[][] bodies = {header, zeros, raw};
        String[] encodings = {"gzip", "gzip", "deflate"};
        int[] chunk = {3, 97, 1};
        for (int i = 0; i < bodies.length; i++) {
            int[] sizes = sizes(bodies[i].length, chunk[i]);
            List<Integer> expected = readsRequested(nettyDecompressor(), encodings[i], bodies[i], sizes);
            assertEquals(expected,
                    readsRequested(new Http1ContentDecompressor(false, 0), encodings[i], bodies[i], sizes),
                    encodings[i] + " in chunks of " + chunk[i]);
            if (i == 0) {
                // Chunks holding only header bytes ask for a read; the head and chunks with output do not.
                assertTrue(expected.contains(1) && expected.contains(0), expected.toString());
            }
        }
    }

    private static List<Integer> readsRequested(ChannelHandler decompressor, String contentEncoding, byte[] encoded,
                                                int[] sizes) {
        int[] reads = new int[1];
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAutoRead(false);
        channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) {
                reads[0]++;
            }
        });
        channel.pipeline().addLast(decompressor);
        List<Integer> perMessage = new ArrayList<>();
        Supplier<Integer> step = () -> {
            int before = reads[0];
            channel.pipeline().fireChannelReadComplete();
            for (Object msg; (msg = channel.readInbound()) != null; ) {
                ReferenceCountUtil.release(msg);
            }
            return reads[0] - before;
        };
        channel.pipeline().fireChannelRead(response(contentEncoding, -1));
        perMessage.add(step.get());
        int offset = 0;
        for (int i = 0; i < sizes.length; i++) {
            ByteBuf content = Unpooled.wrappedBuffer(encoded, offset, sizes[i]);
            offset += sizes[i];
            channel.pipeline().fireChannelRead(i == sizes.length - 1 ? new DefaultLastHttpContent(content)
                    : new DefaultHttpContent(content));
            perMessage.add(step.get());
        }
        channel.finishAndReleaseAll();
        return perMessage;
    }

    @Test
    void closingTheChannelFromAHandlerFurtherOnMidChunkStopsDecoding() throws Exception {
        onFreshThread(() -> {
            UnpooledByteBufAllocator allocator = allocator();
            EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator);
            int[] parts = new int[1];
            List<Object> afterClose = new ArrayList<>();
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (parts[0] > 0) {
                        afterClose.add(msg);
                    } else if (msg instanceof HttpContent && ((HttpContent) msg).content().isReadable()) {
                        parts[0]++;
                        // Netty runs channelInactive later, once this read is over.
                        ctx.channel().close();
                    }
                    ReferenceCountUtil.release(msg);
                }
            });
            byte[] encoded = gzip(ZEROS);
            channel.writeInbound(response("gzip", encoded.length));
            channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(encoded)));
            assertEquals(1, parts[0]);
            assertEquals(List.of(), afterClose);
            assertEquals(1, Http1ContentDecompressor.idleInflaters(true).size());
            channel.finishAndReleaseAll();
            assertEquals(0, allocator.metric().usedHeapMemory());
        });
    }

    @Test
    void decodesTheEndOfABodyThatEndsWithTheConnectionLikeNetty() {
        byte[] encoded = gzip(RANDOM);
        int split = encoded.length / 2;
        List<Outcome> outcomes = new ArrayList<>();
        for (ChannelHandler decompressor : new ChannelHandler[]{nettyDecompressor(),
                new Http1ContentDecompressor(false, 0)}) {
            EmbeddedChannel channel = new EmbeddedChannel();
            // Stands in for HttpClientCodec, which ends a body without a length when the channel goes inactive.
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    ctx.fireChannelRead(new DefaultLastHttpContent(
                            Unpooled.wrappedBuffer(encoded, split, encoded.length - split)));
                    ctx.fireChannelInactive();
                }
            });
            channel.pipeline().addLast(decompressor);
            channel.writeInbound(response("gzip", -1),
                    new DefaultHttpContent(Unpooled.wrappedBuffer(encoded, 0, split)));
            channel.close();
            Outcome outcome = new Outcome();
            drain(channel, outcome);
            channel.finishAndReleaseAll();
            outcomes.add(outcome);
        }
        assertSameOutcome(outcomes.get(0), outcomes.get(1), "body ending with the connection");
        assertDecoded(RANDOM, outcomes.get(1));
    }

    @Test
    void removingOrClosingMidResponseGivesTheInflaterBack() throws Exception {
        onFreshThread(() -> {
            byte[] encoded = gzip(RANDOM);
            EmbeddedChannel removed = channel(new Http1ContentDecompressor(false, 0), allocator());
            removed.writeInbound(response("gzip", -1), new DefaultHttpContent(Unpooled.wrappedBuffer(encoded, 0, 100)));
            ReferenceCountUtil.release(removed.readInbound());
            removed.pipeline().removeFirst();
            assertEquals(1, Http1ContentDecompressor.idleInflaters(true).size());

            EmbeddedChannel closed = channel(new Http1ContentDecompressor(false, 0), allocator());
            closed.writeInbound(response("gzip", -1), new DefaultHttpContent(Unpooled.wrappedBuffer(encoded, 0, 100)));
            // Took the idle inflater.
            assertEquals(0, Http1ContentDecompressor.idleInflaters(true).size());
            closed.close();
            assertEquals(1, Http1ContentDecompressor.idleInflaters(true).size());

            removed.finishAndReleaseAll();
            closed.finishAndReleaseAll();
        });
    }

    @Test
    void transferEncodingGzipIsLeftToNettyAndDecodedTheSame() {
        byte[] encoded = gzip(TEXT);
        List<Outcome> outcomes = new ArrayList<>();
        for (ChannelHandler decompressor : new ChannelHandler[]{nettyDecompressor(),
                new Http1ContentDecompressor(false, 0)}) {
            EmbeddedChannel channel = channel(decompressor, allocator());
            HttpResponse response = response(null, -1);
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "gzip, chunked");
            channel.writeInbound(response, new DefaultLastHttpContent(Unpooled.wrappedBuffer(encoded)));
            Outcome outcome = new Outcome();
            drain(channel, outcome);
            channel.finishAndReleaseAll();
            outcomes.add(outcome);
        }
        assertSameOutcome(outcomes.get(0), outcomes.get(1), "transfer-encoding gzip");
        assertDecoded(TEXT, outcomes.get(1));
    }

    @Test
    void inflatersGoBackToABoundedPoolPerThread() throws Exception {
        onFreshThread(() -> {
            byte[] encoded = gzip(TEXT);
            List<EmbeddedChannel> channels = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
                channel.writeInbound(response("gzip", -1),
                        new DefaultHttpContent(Unpooled.wrappedBuffer(encoded, 0, 20)));
                channels.add(channel);
            }
            assertEquals(0, Http1ContentDecompressor.idleInflaters(true).size());
            for (EmbeddedChannel channel : channels) {
                channel.writeInbound(new DefaultLastHttpContent(
                        Unpooled.wrappedBuffer(encoded, 20, encoded.length - 20)));
                channel.finishAndReleaseAll();
            }
            assertEquals(16, Http1ContentDecompressor.idleInflaters(true).size());

            EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
            assertDecoded(TEXT, exchange(channel, "gzip", encoded, new int[]{encoded.length}, Kind.HEAP));
            assertEquals(16, Http1ContentDecompressor.idleInflaters(true).size());
            byte[] zlib = deflate(TEXT, false);
            assertDecoded(TEXT, exchange(channel, "deflate", zlib, new int[]{zlib.length}, Kind.HEAP));
            assertEquals(1, Http1ContentDecompressor.idleInflaters(false).size());
            channel.finishAndReleaseAll();
        });
    }

    @Test
    void anInflaterGivenBackByARemovalMidChunkDecodesTheNextResponse() throws Exception {
        onFreshThread(() -> {
            byte[] zeros = gzip(ZEROS);
            EmbeddedChannel removed = channel(new Http1ContentDecompressor(false, 0), allocator());
            removed.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof HttpContent && ((HttpContent) msg).content().isReadable()) {
                        ctx.pipeline().remove(Http1ContentDecompressor.class);
                    }
                    ReferenceCountUtil.release(msg);
                }
            });
            removed.writeInbound(response("gzip", -1), new DefaultLastHttpContent(Unpooled.wrappedBuffer(zeros)));
            removed.finishAndReleaseAll();
            assertEquals(1, Http1ContentDecompressor.idleInflaters(true).size());

            EmbeddedChannel next = channel(new Http1ContentDecompressor(false, 0), allocator());
            assertDecoded(ZEROS, exchange(next, "gzip", zeros, sizes(zeros.length, 500), Kind.DIRECT));
            byte[] raw = deflate(RANDOM, true);
            assertDecoded(RANDOM, exchange(next, "deflate", raw, sizes(raw.length, 3000), Kind.DIRECT));
            assertEquals(1, Http1ContentDecompressor.idleInflaters(true).size());
            next.finishAndReleaseAll();
        });
    }

    private static void onFreshThread(Runnable test) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // Event loops run on FastThreadLocalThreads, so the pool takes the same path as in production.
        Thread thread = new FastThreadLocalThread(() -> {
            try {
                test.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() instanceof Error) {
            throw (Error) failure.get();
        }
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
    }

    @Test
    void requestsReadsLikeNettyWhenOneReadMixesItsMessagesWithNettys() {
        byte[] snappy = snappy(TEXT);
        byte[] gzipText = gzip(TEXT);
        byte[] corrupt = withByte(gzip(TEXT), 0, 0);
        List<Supplier<List<Object>>> batches = List.of(
                () -> List.of(response("snappy", -1), new DefaultLastHttpContent(Unpooled.wrappedBuffer(snappy)),
                        response("gzip", -1), new DefaultHttpContent(Unpooled.wrappedBuffer(gzipText, 0, 3))),
                () -> List.of(new DefaultLastHttpContent(Unpooled.wrappedBuffer(gzipText, 3, gzipText.length - 3)),
                        response("snappy", -1), new DefaultHttpContent(Unpooled.wrappedBuffer(snappy, 0, 5))),
                () -> List.of(new DefaultLastHttpContent(Unpooled.wrappedBuffer(snappy, 5, snappy.length - 5)),
                        response("gzip", -1)),
                () -> List.of(new DefaultLastHttpContent(Unpooled.wrappedBuffer(gzipText))),
                // A body rejected before anything is decoded still asks for a read.
                () -> List.of(response("gzip", -1), new DefaultHttpContent(Unpooled.wrappedBuffer(corrupt))));
        List<Integer> expected = readsPerBatch(nettyDecompressor(), batches);
        assertEquals(expected, readsPerBatch(new Http1ContentDecompressor(false, 0), batches));
        assertEquals(List.of(1, 1, 0, 0, 1), expected);
    }

    private static List<Integer> readsPerBatch(ChannelHandler decompressor, List<Supplier<List<Object>>> batches) {
        int[] reads = new int[1];
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAutoRead(false);
        channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) {
                reads[0]++;
            }
        });
        channel.pipeline().addLast(decompressor);
        List<Integer> perBatch = new ArrayList<>();
        for (Supplier<List<Object>> batch : batches) {
            int before = reads[0];
            for (Object msg : batch.get()) {
                channel.pipeline().fireChannelRead(msg);
            }
            channel.pipeline().fireChannelReadComplete();
            perBatch.add(reads[0] - before);
            for (Object msg; (msg = channel.readInbound()) != null; ) {
                ReferenceCountUtil.release(msg);
            }
        }
        try {
            channel.finishAndReleaseAll();
        } catch (DecompressionException ignored) {
            // The corrupt body of the last batch.
        }
        return perBatch;
    }

    @Test
    void closingTheChannelWhileTheHeadIsForwardedDropsTheBody() throws Exception {
        onFreshThread(() -> {
            EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
            List<Object> afterClose = new ArrayList<>();
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof HttpResponse) {
                        ctx.channel().close();
                    } else {
                        afterClose.add(msg);
                    }
                    ReferenceCountUtil.release(msg);
                }
            });
            byte[] encoded = gzip(TEXT);
            // One read: the body is already queued behind the head when the head is forwarded.
            channel.writeInbound(response("gzip", encoded.length),
                    new DefaultLastHttpContent(Unpooled.wrappedBuffer(encoded)));
            assertEquals(List.of(), afterClose);
            assertEquals(1, Http1ContentDecompressor.idleInflaters(true).size());
            channel.finishAndReleaseAll();
        });
    }

    @Test
    void aBufferThatCannotGrowFailsLikeNetty() {
        // A raw deflate stream flushed but not finished, so the inflater wants more room after its output.
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        byte[] flushed = new byte[4096];
        deflater.setInput(randomBytes(900, 7));
        int length = deflater.deflate(flushed, 0, flushed.length, Deflater.SYNC_FLUSH);
        deflater.end();
        byte[] encoded = Arrays.copyOf(flushed, length);

        List<Outcome> outcomes = new ArrayList<>();
        for (ChannelHandler decompressor : new ChannelHandler[]{nettyDecompressor(),
                new Http1ContentDecompressor(false, 0)}) {
            EmbeddedChannel channel = channel(decompressor, new CappedHeapAllocator(1024));
            outcomes.add(exchange(channel, "deflate", encoded, new int[]{encoded.length}, Kind.DIRECT));
            try {
                channel.finishAndReleaseAll();
            } catch (DecompressionException ignored) {
                // Netty's decoder runs once more when its channel is torn down.
            }
        }
        assertSameOutcome(outcomes.get(0), outcomes.get(1), "capped buffer");
        assertEquals("Decompression buffer has reached maximum size: 1024", outcomes.get(1).failure.getMessage());
        assertInputsReleased(outcomes.get(1), "capped buffer");
    }

    /**
     * Never hands out a heap buffer that can grow past {@code cap} bytes.
     */
    private static final class CappedHeapAllocator extends AbstractByteBufAllocator {

        private final int cap;

        CappedHeapAllocator(int cap) {
            this.cap = cap;
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            return new UnpooledHeapByteBuf(this, Math.min(initialCapacity, cap), Math.min(maxCapacity, cap));
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }
}
