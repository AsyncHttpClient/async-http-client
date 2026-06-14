/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request.body;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.stream.ChunkedStream;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.WriteProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

public class NettyInputStreamBody implements NettyBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyInputStreamBody.class);

    private final InputStream inputStream;
    private final long contentLength;

    public NettyInputStreamBody(InputStream inputStream) {
        this(inputStream, -1L);
    }

    public NettyInputStreamBody(InputStream inputStream, long contentLength) {
        this.inputStream = inputStream;
        this.contentLength = contentLength;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    public void write(Channel channel, NettyResponseFuture<?> future) throws IOException {
        final InputStream is = inputStream;

        if (future.isStreamConsumed()) {
            if (is.markSupported()) {
                is.reset();
            } else {
                LOGGER.warn("Stream has already been consumed and cannot be reset");
                return;
            }
        } else {
            future.setStreamConsumed(true);
        }

        channel.write(new ChunkedStream(is), channel.newProgressivePromise()).addListener(
                new WriteProgressListener(future, false, getContentLength()) {
                    @Override
                    public void operationComplete(ChannelProgressiveFuture cf) {
                        closeSilently(is);
                        super.operationComplete(cf);
                    }
                });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, channel.voidPromise());
    }

    @Override
    public void writeHttp2(Http2StreamChannel channel, NettyResponseFuture<?> future) throws IOException {
        final InputStream is = inputStream;

        if (future.isStreamConsumed()) {
            if (is.markSupported()) {
                is.reset();
            } else {
                // The HEADERS frame was already written with endStream=false (sendHttp2Frames), so silently
                // returning would leave the stream half-open with no terminating DATA frame — the request
                // would then hang until it times out (the Issue #2160 silent-timeout class). A non-resettable
                // InputStream cannot be replayed (retry / redirect / auth), so fail the stream explicitly:
                // the caller (openHttp2Stream / sendHttp2RequestBody) aborts this single stream on the
                // IOException, leaving sibling multiplexed streams untouched.
                throw new IOException("HTTP/2 request body InputStream already consumed and cannot be reset for a retry");
            }
        } else {
            future.setStreamConsumed(true);
        }

        // Stream the InputStream one bounded chunk at a time with HTTP/2 flow control / writability
        // backpressure, so a large upload does not buffer the whole stream in heap or read it all inline on
        // the event loop. Cleanup (closeSilently) happens when the async pump completes — see
        // InputStreamChunkSource.close.
        Http2BodyWriter.start(channel, new InputStreamChunkSource(is));
    }

    /**
     * Reads an {@link InputStream} in fixed-size chunks for {@link Http2BodyWriter}.
     */
    private static final class InputStreamChunkSource implements Http2BodyWriter.ChunkSource {

        private static final int CHUNK_SIZE = 8192;

        private final InputStream is;
        private final byte[] buffer = new byte[CHUNK_SIZE];

        InputStreamChunkSource(InputStream is) {
            this.is = is;
        }

        @Override
        public ByteBuf nextChunk(ByteBufAllocator alloc) throws IOException {
            // Blocking InputStream.read returns >0 (data), or -1 (EOF). A transient 0 is possible for some
            // streams; loop on it so we never emit an empty non-final DATA frame nor end the stream early. The
            // read blocks until data or EOF, so this does not busy-spin.
            int read;
            do {
                read = is.read(buffer);
            } while (read == 0);

            if (read == -1) {
                return null;
            }
            ByteBuf buf = alloc.buffer(read);
            try {
                buf.writeBytes(buffer, 0, read);
                return buf;
            } catch (RuntimeException e) {
                buf.release();
                throw e;
            }
        }

        @Override
        public void close() {
            closeSilently(is);
        }
    }
}
