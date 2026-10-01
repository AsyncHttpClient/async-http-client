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
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static ByteBuf buffer(byte[] bytes, int offset, int length, boolean direct) {
        ByteBuf buf = direct ? Unpooled.directBuffer(Math.max(length, 1)) : Unpooled.buffer(Math.max(length, 1));
        return buf.writeBytes(bytes, offset, length);
    }

    /**
     * Sends one response through the channel and collects what comes out, stopping at the first error.
     */
    private static Outcome exchange(EmbeddedChannel channel, String contentEncoding, byte[] encoded, int[] sizes,
                                    boolean direct) {
        Outcome outcome = new Outcome();
        List<Object> messages = new ArrayList<>();
        messages.add(response(contentEncoding, encoded.length));
        int offset = 0;
        for (int i = 0; i < sizes.length; i++) {
            ByteBuf content = buffer(encoded, offset, sizes[i], direct);
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

    private static EmbeddedChannel channel(ChannelHandler decompressor, UnpooledByteBufAllocator allocator) {
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
                for (boolean direct : new boolean[]{false, true}) {
                    String scenario = body.name + ", " + sizes.length + " chunks, " + (direct ? "direct" : "heap");
                    UnpooledByteBufAllocator ahcAllocator = allocator();
                    EmbeddedChannel ahc = channel(new Http1ContentDecompressor(false, 0), ahcAllocator);
                    EmbeddedChannel netty = channel(nettyDecompressor(), allocator());
                    Outcome expected = exchange(netty, body.contentEncoding, body.encoded, sizes, direct);
                    Outcome actual = exchange(ahc, body.contentEncoding, body.encoded, sizes, direct);

                    assertSameOutcome(expected, actual, scenario);
                    ahc.finishAndReleaseAll();
                    try {
                        netty.finishAndReleaseAll();
                    } catch (DecompressionException ignored) {
                        // Netty's decoder runs once more over a corrupt body when its channel is torn down.
                    }
                    assertEquals(0, ahcAllocator.metric().usedHeapMemory(), scenario + ": heap left allocated");
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
        } else {
            assertNotNull(actual.failure, scenario + ": expected " + expected.failure);
            assertEquals(expected.failure.getClass(), actual.failure.getClass(), scenario + ": failure type");
            assertEquals(expected.failure.getMessage(), actual.failure.getMessage(), scenario + ": failure message");
            assertFalse(actual.ended, scenario + ": failed response must not end normally");
        }
    }

    @Test
    void forwardsOnePartPerChunkUnlessItInflatesPastTheThreshold() {
        byte[] encoded = gzip(TEXT);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator());
        Outcome outcome = exchange(channel, "gzip", encoded, new int[]{encoded.length}, true);
        assertNull(outcome.failure);
        assertEquals(1, outcome.parts);
        assertArrayEquals(TEXT, outcome.body.toByteArray());
        channel.finishAndReleaseAll();
    }

    @Test
    void reusesTheInflaterAcrossResponsesOnOneConnection() {
        UnpooledByteBufAllocator allocator = allocator();
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, 0), allocator);
        byte[] gzipText = gzip(TEXT);
        byte[] zlib = deflate(RANDOM, false);
        byte[] raw = deflate(TEXT, true);

        assertDecoded(TEXT, exchange(channel, "gzip", gzipText, new int[]{gzipText.length}, true));
        Outcome identity = exchange(channel, null, TEXT, new int[]{100, TEXT.length - 100}, false);
        assertDecoded(TEXT, identity);
        assertEquals(String.valueOf(TEXT.length), identity.head.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertDecoded(RANDOM, exchange(channel, "deflate", zlib, sizes(zlib.length, 4000), true));
        assertDecoded(TEXT, exchange(channel, "deflate", raw, sizes(raw.length, 3), false));
        // A response cut short leaves state behind that the next one must not see.
        Outcome cut = exchange(channel, "gzip", Arrays.copyOf(gzipText, 40), new int[]{40}, true);
        assertNull(cut.failure);
        assertDecoded(TEXT, exchange(channel, "gzip", gzipText, sizes(gzipText.length, 5), true));
        // So does a corrupt one.
        Outcome corrupt = exchange(channel, "gzip", flipped(gzipText, -8), new int[]{gzipText.length}, true);
        assertInstanceOf(DecompressionException.class, corrupt.failure);
        assertDecoded(TEXT, exchange(channel, "gzip", gzipText, new int[]{gzipText.length}, true));

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
        Outcome outcome = exchange(channel(new Http1ContentDecompressor(false, 0), allocator()), "gzip", gzip(TEXT),
                new int[]{10, 20}, false);
        assertNull(outcome.head.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertNull(outcome.head.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(HttpHeaderValues.CHUNKED.toString(), outcome.head.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
    }

    @Test
    void keepEncodingHeaderLeavesContentEncoding() {
        Outcome outcome = exchange(channel(new Http1ContentDecompressor(true, 0), allocator()), "X-Gzip", gzip(RANDOM),
                sizes(gzip(RANDOM).length, 2000), false);
        assertEquals("X-Gzip", outcome.head.headers().get(HttpHeaderNames.CONTENT_ENCODING));
        assertNull(outcome.head.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertArrayEquals(RANDOM, outcome.body.toByteArray());
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
        assertDecoded(TEXT, exchange(channel, "gzip", encoded, new int[]{encoded.length}, false));
        channel.finishAndReleaseAll();
    }

    @Test
    void limitFailsTheResponseOnceTheBodyPassesIt() {
        byte[] encoded = gzip(ZEROS);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, ZEROS.length - 1), allocator());
        Outcome outcome = exchange(channel, "gzip", encoded, sizes(encoded.length, 100), true);
        assertInstanceOf(DecompressionException.class, outcome.failure);
        assertEquals("HTTP/1.1 response body exceeds the maximum decompressed size of " + (ZEROS.length - 1) + " bytes",
                outcome.failure.getMessage());
        assertTrue(outcome.body.size() < ZEROS.length);
        // The limit counts one response, not the connection.
        assertDecoded(TEXT, exchange(channel, "gzip", gzip(TEXT), new int[]{50, 56}, true));
        channel.finishAndReleaseAll();
    }

    @Test
    void limitAllowsABodyOfExactlyTheLimit() {
        byte[] encoded = gzip(ZEROS);
        EmbeddedChannel channel = channel(new Http1ContentDecompressor(false, ZEROS.length), allocator());
        assertDecoded(ZEROS, exchange(channel, "gzip", encoded, new int[]{encoded.length}, false));
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
        byte[] encoded = gzipWithHeaderFields(TEXT, FNAME | FCOMMENT);
        int[] sizes = sizes(encoded.length, 3);
        assertEquals(readsRequested(nettyDecompressor(), encoded, sizes),
                readsRequested(new Http1ContentDecompressor(false, 0), encoded, sizes));
    }

    private static List<Integer> readsRequested(ChannelHandler decompressor, byte[] encoded, int[] sizes) {
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
        channel.pipeline().fireChannelRead(response("gzip", -1));
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
        assertTrue(perMessage.contains(1) && perMessage.contains(0), perMessage.toString());
        return perMessage;
    }
}
