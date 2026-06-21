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
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.AsciiString;

/**
 * HTTP/2 content decompressor that transparently decompresses gzip/deflate response bodies.
 * Installed on stream child channels when automatic decompression is enabled.
 * <p>
 * Uses Netty's {@link JdkZlibDecoder} via an {@link EmbeddedChannel} for streaming decompression,
 * forwarding decompressed data frames as they arrive rather than buffering the entire response.
 */
public class Http2ContentDecompressor extends ChannelInboundHandlerAdapter {

    private final boolean keepEncodingHeader;
    // Maximum cumulative decompressed bytes for this stream's response; 0 disables the limit. Guards against
    // decompression-bomb responses — a tiny, highly compressible body that inflates to gigabytes and OOMs
    // the client. One handler instance lives for one stream, so this counter spans the whole response.
    private final long maxDecompressedBytes;
    private EmbeddedChannel decompressor;
    private long totalDecompressedBytes;

    public Http2ContentDecompressor(boolean keepEncodingHeader) {
        this(keepEncodingHeader, 0L);
    }

    public Http2ContentDecompressor(boolean keepEncodingHeader, long maxDecompressedBytes) {
        this.keepEncodingHeader = keepEncodingHeader;
        this.maxDecompressedBytes = maxDecompressedBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame) {
            Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
            CharSequence contentEncoding = headersFrame.headers().get(HttpHeaderNames.CONTENT_ENCODING);
            if (contentEncoding != null) {
                // Case-insensitive substring match against the AsciiString header-value constants in place,
                // instead of allocating contentEncoding.toString().toLowerCase() per response. AsciiString
                // case-folding is ASCII-only, hence locale-independent, so it also avoids the Turkish-locale
                // hazard where a default-locale toLowerCase() would mangle "GZIP" and miss the match.
                boolean gzip = AsciiString.containsIgnoreCase(contentEncoding, HttpHeaderValues.GZIP);
                if (gzip || AsciiString.containsIgnoreCase(contentEncoding, HttpHeaderValues.DEFLATE)) {
                    ZlibWrapper wrapper = gzip ? ZlibWrapper.GZIP : ZlibWrapper.ZLIB_OR_NONE;
                    decompressor = new EmbeddedChannel(false, new JdkZlibDecoder(wrapper));
                    if (!keepEncodingHeader) {
                        headersFrame.headers().remove(HttpHeaderNames.CONTENT_ENCODING);
                    }
                    headersFrame.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
                }
            }
            ctx.fireChannelRead(msg);
        } else if (msg instanceof Http2DataFrame && decompressor != null) {
            Http2DataFrame dataFrame = (Http2DataFrame) msg;
            boolean endStream = dataFrame.isEndStream();

            // Accumulate decompressed output here. On ANY failure (typically a DecompressionException
            // from a corrupt gzip/deflate body) the decode throws before the dataFrame.release() below,
            // which would otherwise leak both the retained content (now held by the embedded decoder)
            // and the EmbeddedChannel itself, and surface a raw codec error. The catch releases all
            // three and re-raises a clean DecompressionException so the stream fails predictably.
            CompositeByteBuf decompressed = ctx.alloc().compositeBuffer();
            try {
                ByteBuf content = dataFrame.content();
                if (content.isReadable()) {
                    decompressor.writeInbound(content.retain());
                }

                ByteBuf decoded;
                while ((decoded = decompressor.readInbound()) != null) {
                    decompressed.addComponent(true, decoded);
                }

                if (endStream) {
                    decompressor.finish();
                    while ((decoded = decompressor.readInbound()) != null) {
                        decompressed.addComponent(true, decoded);
                    }
                    releaseDecompressor();
                }
            } catch (Throwable t) {
                // Release everything we own BEFORE tearing the decoder down. releaseDecompressor() closes
                // the embedded channel, which re-runs the decoder over any readable leftover (a body
                // corrupted PAST the gzip header) and can throw a SECOND DecompressionException out of
                // finishAndReleaseAll(). Releasing the frame first — and swallowing that cleanup throw —
                // guarantees the frame is freed and the caller sees the clean exception below instead of
                // the raw codec error. The decoder's own cumulation is released in its channelInputClosed
                // finally regardless, so swallowing here leaks nothing.
                decompressed.release();
                dataFrame.release();
                try {
                    releaseDecompressor();
                } catch (Throwable cleanupError) {
                    // best-effort decoder teardown; see above
                }
                throw new DecompressionException("Failed to decompress HTTP/2 response body", t);
            }

            // Release the original frame — its readable content was retained into the decoder above.
            dataFrame.release();

            // Decompression-bomb guard: bound the cumulative decompressed size for this stream. A malicious
            // server can send a tiny, highly compressible body that inflates to gigabytes; without this the
            // forwarded output (and the byte[] copies the body-part factory makes) can OOM the client. The
            // frame is already released above, so release the accumulator and tear down the decoder, then
            // fail just this stream — the thrown DecompressionException routes via exceptionCaught ->
            // handleException -> streamFailed, leaving sibling multiplexed streams untouched.
            if (maxDecompressedBytes > 0) {
                totalDecompressedBytes += decompressed.readableBytes();
                if (totalDecompressedBytes > maxDecompressedBytes) {
                    decompressed.release();
                    // Swallow any teardown throw (finishAndReleaseAll() can re-run the decoder over a leftover
                    // and throw a second DecompressionException) so the caller sees the bomb-limit message below,
                    // not the raw codec error. The decoder's own cumulation is freed in channelInputClosed's
                    // finally regardless, so swallowing here leaks nothing — same as the corrupt-body catch.
                    try {
                        releaseDecompressor();
                    } catch (Throwable cleanupError) {
                        // best-effort decoder teardown
                    }
                    throw new DecompressionException(
                            "HTTP/2 response body exceeds the maximum decompressed size of "
                                    + maxDecompressedBytes + " bytes");
                }
            }

            if (decompressed.isReadable() || endStream) {
                ctx.fireChannelRead(new DefaultHttp2DataFrame(decompressed, endStream));
            } else {
                decompressed.release();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseDecompressor();
    }

    private void releaseDecompressor() {
        if (decompressor != null) {
            decompressor.finishAndReleaseAll();
            decompressor = null;
        }
    }
}
