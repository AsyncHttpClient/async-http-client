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
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.EventExecutor;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.WriteProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static java.util.Objects.requireNonNull;
import static org.asynchttpclient.util.MiscUtils.closeSilently;

public class NettyInputStreamBody implements NettyBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyInputStreamBody.class);

    private final InputStream inputStream;
    private final long contentLength;
    private final Executor blockingBodyReadExecutor;

    public NettyInputStreamBody(InputStream inputStream) {
        this(inputStream, -1L);
    }

    public NettyInputStreamBody(InputStream inputStream, long contentLength) {
        this(inputStream, contentLength, Runnable::run);
    }

    public NettyInputStreamBody(InputStream inputStream, Executor blockingBodyReadExecutor) {
        this(inputStream, -1L, blockingBodyReadExecutor);
    }

    public NettyInputStreamBody(InputStream inputStream, long contentLength, Executor blockingBodyReadExecutor) {
        this.inputStream = inputStream;
        this.contentLength = contentLength;
        this.blockingBodyReadExecutor = requireNonNull(blockingBodyReadExecutor, "blockingBodyReadExecutor");
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

        ChunkedWriteHandler chunkedWriteHandler = requireNonNull(channel.pipeline().get(ChunkedWriteHandler.class),
                "chunkedWriteHandler");
        OffloadedInputStreamChunkedInput chunkedInput = new OffloadedInputStreamChunkedInput(is, getContentLength(),
                channel.eventLoop(), chunkedWriteHandler, blockingBodyReadExecutor);
        channel.write(chunkedInput, channel.newProgressivePromise()).addListener(
                new WriteProgressListener(future, false, getContentLength()) {
                    @Override
                    public void operationComplete(ChannelProgressiveFuture cf) {
                        chunkedInput.close();
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
        Http2BodyWriter.start(channel, new InputStreamChunkSource(is, channel.eventLoop(), blockingBodyReadExecutor));
    }

    /**
     * Reads an {@link InputStream} off the event loop and exposes completed chunks to
     * {@link Http2BodyWriter}.
     */
    private static final class InputStreamChunkSource implements Http2BodyWriter.ChunkSource {

        private static final int CHUNK_SIZE = 8192;

        private final InputStream is;
        private final EventExecutor eventLoop;
        private final Executor blockingBodyReadExecutor;
        private final byte[] buffer = new byte[CHUNK_SIZE];
        private boolean readInProgress;
        private boolean endOfInput;
        private boolean closed;
        private int readableBytes;
        private IOException failure;
        private Runnable resume;

        InputStreamChunkSource(InputStream is, EventExecutor eventLoop, Executor blockingBodyReadExecutor) {
            this.is = is;
            this.eventLoop = eventLoop;
            this.blockingBodyReadExecutor = blockingBodyReadExecutor;
        }

        @Override
        public ByteBuf nextChunk(ByteBufAllocator alloc) throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (readableBytes > 0) {
                ByteBuf buf = alloc.buffer(readableBytes);
                try {
                    buf.writeBytes(buffer, 0, readableBytes);
                    readableBytes = 0;
                    return buf;
                } catch (RuntimeException e) {
                    buf.release();
                    throw e;
                }
            }
            if (endOfInput) {
                return null;
            }
            if (!readInProgress) {
                submitReadAfterSuspend();
            }
            return Http2BodyWriter.SUSPEND;
        }

        @Override
        public void onResume(Runnable resume) {
            this.resume = resume;
        }

        @Override
        public void close() {
            closed = true;
            closeSilently(is);
        }

        private void submitReadAfterSuspend() throws IOException {
            readInProgress = true;
            try {
                eventLoop.execute(this::submitRead);
            } catch (RejectedExecutionException e) {
                readInProgress = false;
                throw new IOException("HTTP/2 request body read executor is unavailable", e);
            }
        }

        private void submitRead() {
            try {
                blockingBodyReadExecutor.execute(this::readOffEventLoop);
            } catch (RejectedExecutionException e) {
                completeRead(-1, new IOException("HTTP/2 request body read executor rejected the read", e));
            }
        }

        private void readOffEventLoop() {
            int read = -1;
            IOException thrown = null;
            try {
                read = read();
            } catch (IOException e) {
                thrown = e;
            }
            completeRead(read, thrown);
        }

        private int read() throws IOException {
            int read;
            do {
                read = is.read(buffer);
            } while (read == 0 && !closed);
            return read;
        }

        private void completeRead(int read, IOException thrown) {
            try {
                eventLoop.execute(() -> {
                    readInProgress = false;
                    if (closed) {
                        return;
                    }
                    if (thrown != null) {
                        failure = thrown;
                    } else if (read < 0) {
                        endOfInput = true;
                    } else {
                        readableBytes = read;
                    }
                    if (resume != null) {
                        resume.run();
                    }
                });
            } catch (RejectedExecutionException e) {
                close();
            }
        }
    }

    private static final class OffloadedInputStreamChunkedInput implements ChunkedInput<ByteBuf> {

        private static final int CHUNK_SIZE = 8192;

        private final InputStream is;
        private final long length;
        private final EventExecutor eventLoop;
        private final ChunkedWriteHandler chunkedWriteHandler;
        private final Executor blockingBodyReadExecutor;
        private final byte[] buffer = new byte[CHUNK_SIZE];
        private boolean readInProgress;
        private boolean endOfInput;
        private boolean closed;
        private int readableBytes;
        private long progress;
        private IOException failure;

        OffloadedInputStreamChunkedInput(InputStream is,
                                         long length,
                                         EventExecutor eventLoop,
                                         ChunkedWriteHandler chunkedWriteHandler,
                                         Executor blockingBodyReadExecutor) {
            this.is = is;
            this.length = length;
            this.eventLoop = eventLoop;
            this.chunkedWriteHandler = chunkedWriteHandler;
            this.blockingBodyReadExecutor = blockingBodyReadExecutor;
        }

        @Override
        @Deprecated
        public ByteBuf readChunk(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
            return readChunk(ctx.alloc());
        }

        @Override
        public ByteBuf readChunk(ByteBufAllocator alloc) throws Exception {
            if (failure != null) {
                throw failure;
            }
            if (readableBytes > 0) {
                ByteBuf buf = alloc.buffer(readableBytes);
                try {
                    buf.writeBytes(buffer, 0, readableBytes);
                    progress += readableBytes;
                    readableBytes = 0;
                    return buf;
                } catch (RuntimeException e) {
                    buf.release();
                    throw e;
                }
            }
            if (endOfInput) {
                return null;
            }
            if (!readInProgress) {
                submitReadAfterSuspend();
            }
            return null;
        }

        @Override
        public boolean isEndOfInput() {
            return endOfInput;
        }

        @Override
        public void close() {
            closed = true;
            closeSilently(is);
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public long progress() {
            return progress;
        }

        private void submitReadAfterSuspend() throws IOException {
            readInProgress = true;
            try {
                eventLoop.execute(this::submitRead);
            } catch (RejectedExecutionException e) {
                readInProgress = false;
                throw new IOException("HTTP/1 request body read executor is unavailable", e);
            }
        }

        private void submitRead() {
            try {
                blockingBodyReadExecutor.execute(this::readOffEventLoop);
            } catch (RejectedExecutionException e) {
                completeRead(-1, new IOException("HTTP/1 request body read executor rejected the read", e));
            }
        }

        private void readOffEventLoop() {
            int read = -1;
            IOException thrown = null;
            try {
                read = read();
            } catch (IOException e) {
                thrown = e;
            }
            completeRead(read, thrown);
        }

        private int read() throws IOException {
            int read;
            do {
                read = is.read(buffer);
            } while (read == 0 && !closed);
            return read;
        }

        private void completeRead(int read, IOException thrown) {
            try {
                eventLoop.execute(() -> {
                    readInProgress = false;
                    if (closed) {
                        return;
                    }
                    if (thrown != null) {
                        failure = thrown;
                    } else if (read < 0) {
                        endOfInput = true;
                    } else {
                        readableBytes = read;
                    }
                    chunkedWriteHandler.resumeTransfer();
                });
            } catch (RejectedExecutionException e) {
                close();
            }
        }
    }
}
