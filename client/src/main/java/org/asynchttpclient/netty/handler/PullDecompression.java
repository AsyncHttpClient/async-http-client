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
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.BrotliDecompressor;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.compression.Decompressor;
import io.netty.handler.codec.compression.SnappyFrameDecompressor;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.compression.ZstdDecompressor;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

import static org.asynchttpclient.netty.handler.Http1ContentDecompressor.MAX_FORWARD_BYTES;
import static org.asynchttpclient.netty.handler.Http1ContentDecompressor.MAX_PART_SIZE;

/**
 * Decompresses a {@code br}, {@code zstd} or {@code snappy} body for {@link Http1ContentDecompressor}, one part of at
 * most {@link Http1ContentDecompressor#MAX_PART_SIZE} bytes at a time, with Netty's pull-based {@link Decompressor}, so
 * that it can stop between parts while the response is suspended.
 * <p>
 * Netty added {@link Decompressor} in 4.2.17, and still marks it unstable while it migrates its codecs to it, so a Netty
 * upgrade may require changes here. Only this class refers to it, and {@link Http1ContentDecompressor} loads it only
 * once one of these codings shows up, so {@code gzip} and {@code deflate} do not need that Netty version.
 */
final class PullDecompression {

    private final Decompressor decompressor;
    private final String coding;
    // Maximum cumulative decompressed bytes for the body; 0 disables the limit.
    private final long maxDecompressedBytes;
    private long decompressedBytes;
    // Output already taken from the decompressor that did not fit into the previous part.
    private @Nullable ByteBuf carriedOutput;
    private boolean inputAdded;
    private boolean inputEnded;

    private PullDecompression(Decompressor decompressor, String coding, long maxDecompressedBytes) {
        this.decompressor = decompressor;
        this.coding = coding;
        this.maxDecompressedBytes = maxDecompressedBytes;
    }

    /**
     * Returns {@code null} for a coding that Netty cannot decode here because its library is missing, which Netty's
     * {@code HttpContentDecompressor} leaves undecoded too.
     */
    static @Nullable PullDecompression create(String contentEncoding, ByteBufAllocator alloc,
                                              long maxDecompressedBytes) {
        Decompressor decompressor;
        String coding;
        if (HttpHeaderValues.BR.contentEqualsIgnoreCase(contentEncoding)) {
            if (!Brotli.isAvailable()) {
                return null;
            }
            decompressor = BrotliDecompressor.builder().maxOutputChunkSize(MAX_PART_SIZE).build(alloc);
            coding = "br";
        } else if (HttpHeaderValues.ZSTD.contentEqualsIgnoreCase(contentEncoding)) {
            // Netty's ZstdDecompressor hands out buffers of io.netty.compression.defaultMaxForwardBytes. At 0 it hands
            // out nothing while reporting more, so zstd is left to Netty's push decoder then, as before.
            if (!Zstd.isAvailable() || MAX_FORWARD_BYTES <= 0) {
                return null;
            }
            decompressor = ZstdDecompressor.builder().build(alloc);
            coding = "zstd";
        } else if (HttpHeaderValues.SNAPPY.contentEqualsIgnoreCase(contentEncoding)) {
            decompressor = SnappyFrameDecompressor.builder().build(alloc);
            coding = "snappy";
        } else {
            throw new IllegalArgumentException(contentEncoding);
        }
        return new PullDecompression(decompressor, coding, maxDecompressedBytes);
    }

    /**
     * Takes over {@code input}. Once the compressed stream is complete, further bytes are dropped, as Netty's decoders
     * drop them.
     */
    void addInput(ByteBuf input) {
        if (decompressor.status() == Decompressor.Status.NEED_INPUT) {
            inputAdded = true;
            decompressor.addInput(input);
        } else {
            input.release();
        }
    }

    /**
     * Tells the decompressor that the body has ended, which fails a truncated stream. Only called once all output has
     * been taken; there may be more afterwards. A body without any bytes is left alone, as Netty's decoders leave it.
     */
    void endOfInput() {
        if (inputAdded && !inputEnded && decompressor.status() == Decompressor.Status.NEED_INPUT) {
            inputEnded = true;
            try {
                decompressor.endOfInput();
            } catch (DecompressionException e) {
                // zstd-jni reports a truncated stream with an IOException, which AHC would take for a failed
                // connection.
                if (e.getCause() instanceof IOException) {
                    DecompressionException truncated =
                            new DecompressionException("Truncated " + coding + " stream");
                    truncated.addSuppressed(e);
                    throw truncated;
                }
                throw e;
            }
        }
    }

    boolean hasOutput() {
        return carriedOutput != null || decompressor.status() == Decompressor.Status.NEED_OUTPUT;
    }

    /**
     * Takes up to {@link Http1ContentDecompressor#MAX_PART_SIZE} bytes of output. Small output buffers are copied
     * together into one buffer sized for them, so a highly compressible body is not handed on in hundreds of tiny parts.
     */
    @Nullable ByteBuf nextPart(ByteBufAllocator alloc) {
        ByteBuf part = carriedOutput;
        carriedOutput = null;
        if (part != null && part.readableBytes() > MAX_PART_SIZE) {
            return split(part);
        }
        boolean merged = false;
        try {
            while (decompressor.status() == Decompressor.Status.NEED_OUTPUT) {
                ByteBuf output = decompressor.takeOutput();
                int length = output.readableBytes();
                if (maxDecompressedBytes > 0 && (decompressedBytes += length) > maxDecompressedBytes) {
                    output.release();
                    throw new DecompressionException("HTTP/1.1 response body exceeds the maximum decompressed size of "
                            + maxDecompressedBytes + " bytes");
                }

                if (length == 0) {
                    output.release();
                } else if (part == null) {
                    if (length > MAX_PART_SIZE) {
                        return split(output);
                    }
                    part = output;
                } else if (part.readableBytes() + length > MAX_PART_SIZE) {
                    carriedOutput = output;
                    break;
                } else {
                    if (!merged) {
                        ByteBuf first = part;
                        part = alloc.heapBuffer(first.readableBytes() + length, MAX_PART_SIZE);
                        part.writeBytes(first);
                        first.release();
                        merged = true;
                    }
                    part.writeBytes(output);
                    output.release();
                }
            }
        } catch (RuntimeException e) {
            ReferenceCountUtil.release(part);
            throw e;
        }
        return part;
    }

    /** Hands on the first {@link Http1ContentDecompressor#MAX_PART_SIZE} bytes of {@code output} and carries the rest. */
    private ByteBuf split(ByteBuf output) {
        ByteBuf part = output.readRetainedSlice(MAX_PART_SIZE);
        carriedOutput = output;
        return part;
    }

    void close() {
        ByteBuf carriedOutput = this.carriedOutput;
        if (carriedOutput != null) {
            this.carriedOutput = null;
            carriedOutput.release();
        }
        decompressor.close();
    }
}
