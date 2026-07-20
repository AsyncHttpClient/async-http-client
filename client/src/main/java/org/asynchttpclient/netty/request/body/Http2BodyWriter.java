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
package org.asynchttpclient.netty.request.body;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Streams a request body to an HTTP/2 stream child channel as a sequence of
 * {@link DefaultHttp2DataFrame DATA frames}, honouring HTTP/2 flow control and channel writability.
 * <p>
 * The previous implementation looped over the whole body up front, queuing one DATA frame per chunk
 * in the channel's outbound buffer (heap) and flushing once at the end — O(body size) memory and an
 * inline read of the entire source on the event loop, which could OOM on large uploads.
 * <p>
 * This writer is a one-chunk-at-a-time pump driven entirely on the stream channel's event loop:
 * <ul>
 *   <li>It produces and writes exactly one chunk, then flushes, so frames actually leave the process
 *       instead of accumulating.</li>
 *   <li>It only produces the next chunk while {@link Http2StreamChannel#isWritable()} is {@code true}.
 *       A stream child channel becomes unwritable when the HTTP/2 flow-control window is exhausted or
 *       the local high-water mark is reached (child writes go through {@code incrementPendingOutboundBytes}).
 *       When it goes unwritable the pump parks; a transient {@link ChannelInboundHandlerAdapter} added to
 *       the stream pipeline resumes it from {@code channelWritabilityChanged}.</li>
 *   <li>It reads one chunk ahead so the <em>final</em> DATA frame can carry {@code endStream=true};
 *       an empty body still sends a single empty DATA frame with {@code endStream=true}.</li>
 *   <li>This writer holds at most one buffered "pending" chunk across a writability wait. Production stops
 *       once the channel goes unwritable, so total in-flight heap is bounded by the channel's write
 *       high-water mark (the already-written chunks the channel's outbound buffer still owns) plus that one
 *       pending chunk — not by the size of the whole body.</li>
 * </ul>
 * <p>
 * <strong>Lifecycle / cleanup.</strong> Because the pump completes asynchronously (after {@code writeHttp2}
 * returns), source cleanup ({@link ChunkSource#close()}) happens when the pump finishes — on success after
 * the last write completes, or on any read/write error. {@link #finish(Throwable)} is idempotent: it closes
 * the source, removes the transient writability handler, releases any unwritten chunk it still owns, and on
 * error aborts the request future with the original cause and closes the stream channel, without touching
 * sibling multiplexed streams.
 * <p>
 * <strong>Reference counting.</strong> Each chunk {@link ByteBuf} is owned by this writer from allocation
 * until it is wrapped in a {@link DefaultHttp2DataFrame} and handed to {@link Http2StreamChannel#write},
 * after which Netty owns and releases it (including on write failure). The only buffer held across an await
 * is the single {@code pending} chunk, which {@link #finish(Throwable)} releases if it was never written.
 */
final class Http2BodyWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(Http2BodyWriter.class);

    /**
     * Sentinel returned by {@link ChunkSource#nextChunk(ByteBufAllocator)} to mean "no chunk is available
     * right now, but the body is not finished" (e.g. a {@code FeedableBodyGenerator} awaiting a feed). The
     * pump parks when it sees this; the source is responsible for arranging a resume via the {@link Runnable}
     * passed to {@link ChunkSource#onResume(Runnable)}. It is never written and never released.
     */
    static final ByteBuf SUSPEND = Unpooled.EMPTY_BUFFER;

    /**
     * Supplies the body one bounded chunk at a time and owns the underlying source's cleanup. Implementations
     * read at most one chunk per {@link #nextChunk(ByteBufAllocator)} call (no read-ahead of the whole source),
     * matching the bounded behaviour of the HTTP/1.1 {@code ChunkedInput} path.
     */
    interface ChunkSource {

        /**
         * Reads the next chunk of the body.
         *
         * @param alloc allocator to use for the returned buffer
         * @return a readable buffer with the next chunk; {@code null} when the body is fully consumed; or
         *         {@link #SUSPEND} when no chunk is available yet but more is expected. A returned data buffer
         *         is owned by the caller. If this method throws, it must not leak a buffer.
         * @throws IOException if the chunk could not be read
         */
        ByteBuf nextChunk(ByteBufAllocator alloc) throws IOException;

        /**
         * Registers a one-shot callback the source must invoke (on or off the event loop) when, after having
         * returned {@link #SUSPEND}, a chunk becomes available again. Only sources that can return
         * {@code SUSPEND} need to honour this; others may ignore it. The pump arranges for the callback to be
         * re-marshalled onto the event loop.
         *
         * @param resume callback that resumes the pump
         */
        default void onResume(Runnable resume) {
        }

        /**
         * Releases the underlying source (stream, file, body). Called exactly once when the pump finishes,
         * whether it succeeded or failed. Must not throw.
         */
        void close();
    }

    private final Http2StreamChannel channel;
    private final ChunkSource source;
    private final NettyResponseFuture<?> future;

    // Single buffered chunk read ahead so the final DATA frame can carry endStream=true. Owned by this
    // writer until written; released by finish() if still set when the pump ends.
    private ByteBuf pending;
    private boolean done;

    // Transient handler that resumes the pump when the channel becomes writable again. Added lazily the
    // first time the pump parks, removed by finish().
    private WritabilityResumeHandler resumeHandler;

    // Set while the pump is parked on a ChunkSource.SUSPEND, to ignore spurious/duplicate feed resumes.
    private boolean suspended;

    private Http2BodyWriter(Http2StreamChannel channel, ChunkSource source, NettyResponseFuture<?> future) {
        this.channel = channel;
        this.source = source;
        this.future = future;
        source.onResume(this::resumeFromSuspend);
        // Guarantee cleanup if the stream is closed out from under a PARKED pump — i.e. one waiting on
        // channelWritabilityChanged (flow-control window exhausted) or on a feed (ChunkSource.SUSPEND).
        // In those states the last write already completed, so no write-future listener will fire; an
        // external close (request timeout, server RST_STREAM, GOAWAY) would otherwise leave finish()
        // uncalled, leaking the body source (file descriptor / InputStream / Body) and the pending chunk.
        // finish() is idempotent and runs on the event loop (closeFuture fires there), so this is a no-op
        // once the pump has completed normally. cause=null: the stream is already closed, so finish() only
        // needs to release resources — the request future is failed separately via handleChannelInactive.
        channel.closeFuture().addListener(f -> finish(null));
    }

    /**
     * Resumes a pump parked on {@link #SUSPEND}. Safe to call from any thread; re-marshals onto the event
     * loop and is a no-op unless the pump is currently suspended.
     */
    private void resumeFromSuspend() {
        if (channel.eventLoop().inEventLoop()) {
            if (suspended && !done) {
                suspended = false;
                pump();
            }
        } else {
            channel.eventLoop().execute(this::resumeFromSuspend);
        }
    }

    /**
     * Starts streaming {@code source} to {@code channel}. Returns immediately; the body is written
     * asynchronously on the channel's event loop. The caller (which has already written the HEADERS frame
     * with {@code endStream=false}) must not write further frames on this stream.
     */
    static void start(Http2StreamChannel channel, ChunkSource source, NettyResponseFuture<?> future) {
        Http2BodyWriter writer = new Http2BodyWriter(channel, source, future);
        if (channel.eventLoop().inEventLoop()) {
            writer.pump();
        } else {
            channel.eventLoop().execute(writer::pump);
        }
    }

    /**
     * Produces and writes chunks until the channel goes unwritable (then parks for
     * {@code channelWritabilityChanged}) or the body is exhausted. Always runs on the event loop.
     */
    private void pump() {
        if (done) {
            return;
        }
        try {
            while (true) {
                if (done) {
                    // A write-failure listener can complete the pump synchronously (an already-failed write
                    // future fires inline); stop immediately rather than reading from the now-closed source.
                    return;
                }
                // Read one chunk ahead of what we write so we can mark the last DATA frame endStream=true.
                ByteBuf next = source.nextChunk(channel.alloc());

                if (next == SUSPEND) {
                    // No data available yet but the body is not finished (feedable body). Park until the
                    // source signals more via the onResume callback. Any already-buffered `pending` chunk is
                    // retained (O(1)); we deliberately do not flush an early endStream.
                    suspended = true;
                    return;
                }

                if (next == null) {
                    // End of body. `pending` (if any) is the last real chunk and must carry endStream=true;
                    // it can only be null here when the body was empty (no chunk was ever read).
                    ByteBuf last = pending;
                    pending = null;
                    ByteBuf terminal = last != null ? last
                            // Empty body — preserve existing behaviour: a single empty DATA frame ends the stream.
                            : channel.alloc().buffer(0);
                    writeLastFrame(terminal);
                    channel.flush();
                    return;
                }

                // We now hold two chunks at most: the previously buffered `pending` and the freshly read
                // `next`. Write `pending` (not last, since `next` exists) and keep `next` buffered.
                ByteBuf toWrite = pending;
                pending = next;
                if (toWrite != null) {
                    writeFrame(toWrite, false);
                    channel.flush();

                    if (!channel.isWritable()) {
                        // Flow-control window exhausted / high-water mark reached: stop producing and resume
                        // from channelWritabilityChanged. `pending` (one chunk) is retained until then.
                        ensureResumeHandler();
                        return;
                    }
                }
                // First iteration (toWrite == null): nothing written yet, loop to read the second chunk.
            }
        } catch (Throwable t) {
            finish(t);
        }
    }

    private void writeFrame(ByteBuf buf, boolean endStream) {
        // Netty takes ownership of `buf` here and releases it (including on write failure). We only react
        // to failure to fail the request future and clean up the source.
        ChannelFuture wf = channel.write(new DefaultHttp2DataFrame(buf, endStream));
        wf.addListener(f -> {
            if (!f.isSuccess()) {
                finish(f.cause());
            }
        });
    }

    /**
     * Writes the terminal DATA frame (endStream=true) and defers cleanup until that write completes, mirroring
     * the HTTP/1.1 path's {@code WriteProgressListener.operationComplete -> closeSilently}. On success the
     * source is closed; on failure the stream is failed via {@link #finish(Throwable)}.
     */
    private void writeLastFrame(ByteBuf buf) {
        ChannelFuture wf = channel.write(new DefaultHttp2DataFrame(buf, true));
        wf.addListener(f -> finish(f.isSuccess() ? null : f.cause()));
    }

    private void ensureResumeHandler() {
        if (resumeHandler == null) {
            resumeHandler = new WritabilityResumeHandler();
            channel.pipeline().addLast(resumeHandler);
        }
    }

    /**
     * Completes the pump exactly once. Closes the source (this is where the body / input stream / file is
     * released), removes the transient writability handler, releases any unwritten chunk, and on error closes
     * the stream channel so the request future is failed via {@code handleChannelInactive}.
     */
    private void finish(Throwable cause) {
        if (done) {
            return;
        }
        done = true;

        if (pending != null) {
            pending.release();
            pending = null;
        }

        if (resumeHandler != null) {
            // removeLast/remove may be called from within the handler's own callback; Netty handles this.
            if (channel.pipeline().context(resumeHandler) != null) {
                channel.pipeline().remove(resumeHandler);
            }
            resumeHandler = null;
        }

        try {
            source.close();
        } catch (Throwable t) {
            LOGGER.warn("Failed to close HTTP/2 request body source", t);
        }

        if (cause != null) {
            LOGGER.debug("HTTP/2 request body streaming failed; closing stream", cause);
            future.abort(cause);
            channel.close();
        }
    }

    /**
     * Resumes the parked pump when the stream channel becomes writable again.
     */
    private final class WritabilityResumeHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            if (!done && ctx.channel().isWritable()) {
                pump();
            }
            ctx.fireChannelWritabilityChanged();
        }
    }
}
