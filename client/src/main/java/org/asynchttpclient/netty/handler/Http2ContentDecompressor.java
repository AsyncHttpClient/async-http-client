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
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;

/**
 * HTTP/2 content decompressor that transparently decompresses gzip/deflate response bodies.
 * Installed on stream child channels when automatic decompression is enabled.
 * <p>
 * Uses Netty's {@link JdkZlibDecoder} via an {@link EmbeddedChannel} for streaming decompression,
 * forwarding decompressed data frames as they arrive rather than buffering the entire response.
 */
public class Http2ContentDecompressor extends ChannelInboundHandlerAdapter {

    private final boolean keepEncodingHeader;
    private EmbeddedChannel decompressor;

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
                    ZlibWrapper wrapper = enc.contains("gzip") ? ZlibWrapper.GZIP : ZlibWrapper.ZLIB_OR_NONE;
                    decompressor = new EmbeddedChannel(false, new JdkZlibDecoder(wrapper));
                    if (!keepEncodingHeader) {
                        headersFrame.headers().remove("content-encoding");
                    }
                    headersFrame.headers().remove("content-length");
                }
            }
            ctx.fireChannelRead(msg);
        } else if (msg instanceof Http2DataFrame && decompressor != null) {
            Http2DataFrame dataFrame = (Http2DataFrame) msg;
            ByteBuf content = dataFrame.content();
            boolean endStream = dataFrame.isEndStream();

            if (content.isReadable()) {
                decompressor.writeInbound(content.retain());
            }

            // Release the original frame
            dataFrame.release();

            // Read all decompressed output from the embedded channel
            CompositeByteBuf decompressed = ctx.alloc().compositeBuffer();
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
