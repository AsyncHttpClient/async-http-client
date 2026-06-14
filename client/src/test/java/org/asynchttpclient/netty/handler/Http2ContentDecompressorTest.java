/*
 *    Copyright (c) 2014-2026 AsyncHttpClient Project. All rights reserved.
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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Http2ContentDecompressor}, exercised in isolation with an {@link EmbeddedChannel}
 * so reference counts are deterministic (no network, no GC-timed leak detector).
 * <p>
 * The corrupt-body test is the regression guard for the residual HTTP/2 leak: a
 * {@link DecompressionException} thrown by {@link io.netty.handler.codec.compression.JdkZlibDecoder} used
 * to skip the {@code dataFrame.release()} that follows it, leaking both the retained content and the
 * embedded decoder, and surfacing a raw codec error.
 */
public class Http2ContentDecompressorTest {

    private static byte[] gzipBytes(String s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    private static ByteBuf gzip(String s) throws Exception {
        return Unpooled.wrappedBuffer(gzipBytes(s));
    }

    @Test
    public void decompressesValidGzipBodyAndReleasesFrames() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(new Http2ContentDecompressor(false));

        ch.writeInbound(new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false));
        ByteBuf gz = gzip("hello world");
        ch.writeInbound(new DefaultHttp2DataFrame(gz, true));

        // content-encoding header stripped, content forwarded
        Http2HeadersFrame headers = ch.readInbound();
        assertNotNull(headers);
        assertFalse(headers.headers().contains("content-encoding"), "content-encoding must be stripped");

        StringBuilder body = new StringBuilder();
        Http2DataFrame data;
        while ((data = ch.readInbound()) != null) {
            body.append(data.content().toString(StandardCharsets.UTF_8));
            data.release();
        }
        assertEquals("hello world", body.toString());
        assertEquals(0, gz.refCnt(), "source gzip buffer must be fully released");

        assertFalse(ch.finishAndReleaseAll(), "no buffers should be left inbound");
    }

    @Test
    public void corruptGzipBodyThrowsCleanlyAndReleasesEverything() {
        EmbeddedChannel ch = new EmbeddedChannel(new Http2ContentDecompressor(false));
        ch.writeInbound(new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false));
        // drop the forwarded (mutated) headers frame
        ReferenceCountUtil.release(ch.readInbound());

        // Bytes that are NOT a valid gzip stream — JdkZlibDecoder rejects the magic header.
        ByteBuf corrupt = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        Http2DataFrame corruptFrame = new DefaultHttp2DataFrame(corrupt, true);

        // A clean, typed failure must propagate (not a raw/opaque decoder error)...
        assertThrows(DecompressionException.class, () -> ch.writeInbound(corruptFrame));

        // ...and the frame's content buffer must be fully released — no leak.
        assertEquals(0, corrupt.refCnt(),
                "corrupt DATA frame content leaked: the decode error skipped its release");

        // Nothing was forwarded downstream, and the embedded decoder was torn down (no leftover buffers).
        assertFalse(ch.finishAndReleaseAll(), "no buffers should be left inbound after a decode failure");
    }

    @Test
    public void corruptMidStreamGzipReleasesFrameAndThrowsCleanly() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(new Http2ContentDecompressor(false));
        ch.writeInbound(new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false));
        ReferenceCountUtil.release(ch.readInbound());

        // A valid 10-byte gzip header followed by a corrupted DEFLATE payload: JdkZlibDecoder accepts the
        // header, then fails partway with readable bytes STILL buffered. That is the case where
        // EmbeddedChannel.finishAndReleaseAll() re-runs the decoder on close and throws a SECOND time —
        // which previously skipped the data-frame release (leaking it) and surfaced the raw codec error.
        byte[] gz = gzipBytes("the quick brown fox jumps over the lazy dog, repeated enough to deflate ".repeat(4));
        for (int i = 12; i < 20 && i < gz.length - 8; i++) {
            gz[i] ^= 0xFF;
        }
        ByteBuf corrupt = Unpooled.wrappedBuffer(gz);
        Http2DataFrame corruptFrame = new DefaultHttp2DataFrame(corrupt, true);

        DecompressionException ex = assertThrows(DecompressionException.class, () -> ch.writeInbound(corruptFrame));
        assertEquals("Failed to decompress HTTP/2 response body", ex.getMessage(),
                "must surface the clean wrapped failure, not the raw codec error");
        assertEquals(0, corrupt.refCnt(), "data frame content leaked on mid-stream gzip corruption");
        assertFalse(ch.finishAndReleaseAll(), "no buffers should be left after a mid-stream decode failure");
    }

    @Test
    public void decompressedBodyOverTheLimitFailsCleanly() throws Exception {
        // 8-byte decompressed ceiling; "hello world" inflates to 11 bytes > 8, so the bomb guard must trip.
        // This is the decompression-bomb protection: a tiny compressed body that inflates past the cap fails
        // just this stream instead of being forwarded (and copied into the heap) unbounded.
        EmbeddedChannel ch = new EmbeddedChannel(new Http2ContentDecompressor(false, 8));
        ch.writeInbound(new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false));
        ReferenceCountUtil.release(ch.readInbound()); // drop the forwarded headers frame

        ByteBuf gz = gzip("hello world");
        Http2DataFrame frame = new DefaultHttp2DataFrame(gz, true);

        DecompressionException ex = assertThrows(DecompressionException.class, () -> ch.writeInbound(frame));
        assertTrue(ex.getMessage().contains("maximum decompressed size"),
                "must fail with the decompression-bomb guard message, got: " + ex.getMessage());
        assertEquals(0, gz.refCnt(), "compressed source buffer must be released on the bomb-guard path");
        assertFalse(ch.finishAndReleaseAll(), "no buffers may be left after the bomb guard trips");
    }

    @Test
    public void decompressedBodyUnderTheLimitForwardsNormally() throws Exception {
        // A generous limit must not affect normal decompression.
        EmbeddedChannel ch = new EmbeddedChannel(new Http2ContentDecompressor(false, 1024 * 1024));
        ch.writeInbound(new DefaultHttp2HeadersFrame(
                new DefaultHttp2Headers().status("200").set("content-encoding", "gzip"), false));
        ReferenceCountUtil.release(ch.readInbound());

        ByteBuf gz = gzip("hello world");
        ch.writeInbound(new DefaultHttp2DataFrame(gz, true));

        StringBuilder body = new StringBuilder();
        Http2DataFrame data;
        while ((data = ch.readInbound()) != null) {
            body.append(data.content().toString(StandardCharsets.UTF_8));
            data.release();
        }
        assertEquals("hello world", body.toString());
        assertEquals(0, gz.refCnt(), "source gzip buffer must be fully released");
        assertFalse(ch.finishAndReleaseAll(), "no buffers should be left inbound");
    }
}
