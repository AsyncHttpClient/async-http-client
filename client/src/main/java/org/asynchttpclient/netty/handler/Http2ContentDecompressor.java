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
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.io.ByteArrayInputStream;

/**
 * HTTP/2 content decompressor that transparently decompresses gzip/deflate response bodies.
 * Installed on stream child channels when automatic decompression is enabled.
 * <p>
 * Accumulates compressed data frames, then decompresses on the final frame.
 * This is simpler and more robust than trying to decompress individual frames.
 */
public class Http2ContentDecompressor extends ChannelInboundHandlerAdapter {

    private final boolean keepEncodingHeader;
    private String encoding;
    private CompositeByteBuf accumulator;

    public Http2ContentDecompressor(boolean keepEncodingHeader) {
        this.keepEncodingHeader = keepEncodingHeader;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame) {
            Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
            CharSequence contentEncoding = headersFrame.headers().get("content-encoding");
            if (contentEncoding != null) {
                String enc = contentEncoding.toString().toLowerCase();
                if (enc.contains("gzip") || enc.contains("deflate")) {
                    encoding = enc;
                    if (!keepEncodingHeader) {
                        headersFrame.headers().remove("content-encoding");
                    }
                    headersFrame.headers().remove("content-length");
                }
            }
            ctx.fireChannelRead(msg);
        } else if (msg instanceof Http2DataFrame && encoding != null) {
            Http2DataFrame dataFrame = (Http2DataFrame) msg;
            ByteBuf content = dataFrame.content();
            boolean endStream = dataFrame.isEndStream();

            if (content.isReadable()) {
                if (accumulator == null) {
                    accumulator = ctx.alloc().compositeBuffer();
                }
                accumulator.addComponent(true, content.retain());
            }

            // Release the original frame
            dataFrame.release();

            if (endStream) {
                ByteBuf decompressed;
                if (accumulator != null && accumulator.isReadable()) {
                    byte[] compressed = new byte[accumulator.readableBytes()];
                    accumulator.readBytes(compressed);
                    accumulator.release();
                    accumulator = null;

                    byte[] result = decompress(compressed, encoding);
                    decompressed = ctx.alloc().buffer(result.length);
                    decompressed.writeBytes(result);
                } else {
                    if (accumulator != null) {
                        accumulator.release();
                        accumulator = null;
                    }
                    decompressed = ctx.alloc().buffer(0);
                }
                ctx.fireChannelRead(new DefaultHttp2DataFrame(decompressed, true));
            }
            // Non-endStream frames with encoding are accumulated, not forwarded
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private static byte[] decompress(byte[] data, String encoding) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        java.io.InputStream decompressor;
        if (encoding.contains("gzip")) {
            decompressor = new GZIPInputStream(bais);
        } else {
            decompressor = new InflaterInputStream(bais, new Inflater(true));
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = decompressor.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        decompressor.close();
        return baos.toByteArray();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (accumulator != null) {
            accumulator.release();
            accumulator = null;
        }
    }
}
