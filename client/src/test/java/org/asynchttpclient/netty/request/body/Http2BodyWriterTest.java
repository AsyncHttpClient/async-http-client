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
package org.asynchttpclient.netty.request.body;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class Http2BodyWriterTest {

    @Test
    public void multiChunkBodyBatchesFlushUntilTerminalFrame() {
        Http2StreamChannel channel = mock(Http2StreamChannel.class);
        EventLoop eventLoop = mock(EventLoop.class);
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(eventLoop);
        when(channel.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
        when(channel.isWritable()).thenReturn(true);
        when(channel.closeFuture()).thenReturn(new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE));

        AtomicInteger terminalFrames = new AtomicInteger();
        when(channel.write(any())).thenAnswer(invocation -> {
            Object msg = invocation.getArgument(0);
            if (((DefaultHttp2DataFrame) msg).isEndStream()) {
                terminalFrames.incrementAndGet();
            }
            ReferenceCountUtil.release(msg);
            return succeededFuture(channel);
        });

        FixedChunkSource source = new FixedChunkSource(4);

        Http2BodyWriter.start(channel, source);

        assertEquals(1, source.closed);
        assertEquals(1, terminalFrames.get());
        verify(channel, times(4)).write(any(DefaultHttp2DataFrame.class));
        verify(channel, times(1)).flush();
    }

    @Test
    public void sourceSuspensionFlushesBeforeParking() {
        Http2StreamChannel channel = mock(Http2StreamChannel.class);
        EventLoop eventLoop = mock(EventLoop.class);
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(eventLoop);
        when(channel.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
        when(channel.isWritable()).thenReturn(true);
        when(channel.closeFuture()).thenReturn(new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE));
        AtomicInteger flushes = new AtomicInteger();
        when(channel.write(any())).thenAnswer(invocation -> {
            ReferenceCountUtil.release(invocation.getArgument(0));
            return succeededFuture(channel);
        });
        when(channel.flush()).thenAnswer(invocation -> {
            flushes.incrementAndGet();
            return channel;
        });

        SuspendingChunkSource source = new SuspendingChunkSource(flushes);
        Http2BodyWriter.start(channel, source);

        assertEquals(0, source.closed);
        assertEquals(0, source.flushesBeforeSuspend);
        assertEquals(1, flushes.get());
        verify(channel, times(1)).write(any(DefaultHttp2DataFrame.class));

        source.finish();

        assertEquals(1, source.closed);
        verify(channel, times(2)).write(any(DefaultHttp2DataFrame.class));
        assertEquals(2, flushes.get());
    }

    @Test
    public void synchronousResumeDuringSuspensionFlushIsNotLost() {
        Http2StreamChannel channel = mock(Http2StreamChannel.class);
        EventLoop eventLoop = mock(EventLoop.class);
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(eventLoop);
        when(channel.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
        when(channel.isWritable()).thenReturn(true);
        when(channel.closeFuture()).thenReturn(new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE));
        when(channel.write(any())).thenAnswer(invocation -> {
            ReferenceCountUtil.release(invocation.getArgument(0));
            return succeededFuture(channel);
        });
        AtomicInteger flushes = new AtomicInteger();
        SuspendingChunkSource source = new SuspendingChunkSource(flushes);
        when(channel.flush()).thenAnswer(invocation -> {
            if (flushes.incrementAndGet() == 1) {
                source.finish();
            }
            return channel;
        });

        Http2BodyWriter.start(channel, source);

        assertEquals(1, source.closed);
        assertEquals(2, flushes.get());
        verify(channel, times(2)).write(any(DefaultHttp2DataFrame.class));
    }

    @Test
    public void realStreamBoundsUnflushedBytesByWaterMark() {
        UnflushedBytesTracker tracker = new UnflushedBytesTracker();
        EmbeddedChannel parent = new EmbeddedChannel(
                Http2FrameCodecBuilder.forClient().build(),
                new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
        Http2StreamChannel stream = new Http2StreamChannelBootstrap(parent)
                .handler(tracker)
                .open()
                .syncUninterruptibly()
                .getNow();
        int chunkSize = 1024;
        int highWaterMark = 8 * 1024;
        stream.config().setWriteBufferWaterMark(new WriteBufferWaterMark(highWaterMark / 2, highWaterMark));
        FixedChunkSource source = new FixedChunkSource(highWaterMark / chunkSize * 2, chunkSize);

        try {
            stream.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                    .method("POST")
                    .scheme("https")
                    .authority("localhost")
                    .path("/"))).syncUninterruptibly();

            Http2BodyWriter.start(stream, source);
            parent.runPendingTasks();

            assertEquals(1, source.closed);
            assertTrue(tracker.peakUnflushedBytes >= highWaterMark,
                    "the real stream must reach its configured high-water mark");
            assertTrue(tracker.peakUnflushedBytes <= highWaterMark + chunkSize,
                    "unflushed DATA must stay within the high-water mark plus one chunk");
        } finally {
            stream.close().syncUninterruptibly();
            parent.runPendingTasks();
            parent.finishAndReleaseAll();
        }
    }

    @Test
    public void unwritableChannelResumesWithoutReentrantTerminalWrite() throws Exception {
        Http2StreamChannel channel = mock(Http2StreamChannel.class);
        EventLoop eventLoop = mock(EventLoop.class);
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        ChannelHandlerContext pipelineContext = mock(ChannelHandlerContext.class);
        ChannelHandlerContext eventContext = mock(ChannelHandlerContext.class);
        AtomicReference<ChannelHandler> resumeHandler = new AtomicReference<>();
        AtomicBoolean nestedWritabilityCallbackFired = new AtomicBoolean();
        AtomicInteger terminalFrames = new AtomicInteger();
        DefaultChannelPromise terminalWrite = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(eventLoop);
        when(channel.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
        AtomicBoolean writable = new AtomicBoolean();
        when(channel.isWritable()).thenAnswer(invocation -> writable.get());
        when(channel.closeFuture()).thenReturn(new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE));
        when(channel.pipeline()).thenReturn(pipeline);
        when(pipeline.addLast(any(ChannelHandler.class))).thenAnswer(invocation -> {
            resumeHandler.set(invocation.getArgument(0));
            return pipeline;
        });
        when(pipeline.context(any(ChannelHandler.class))).thenReturn(pipelineContext);
        when(eventContext.channel()).thenReturn(channel);
        when(channel.write(any())).thenAnswer(invocation -> {
            DefaultHttp2DataFrame frame = invocation.getArgument(0);
            if (frame.isEndStream()) {
                terminalFrames.incrementAndGet();
                ReferenceCountUtil.release(frame);
                return terminalWrite;
            }
            ReferenceCountUtil.release(frame);
            return succeededFuture(channel);
        });
        when(channel.flush()).thenAnswer(invocation -> {
            ChannelHandler handler = resumeHandler.get();
            if (handler != null
                    && terminalFrames.get() != 0
                    && nestedWritabilityCallbackFired.compareAndSet(false, true)) {
                ((ChannelInboundHandlerAdapter) handler).channelWritabilityChanged(eventContext);
            }
            return channel;
        });

        FixedChunkSource source = new FixedChunkSource(3);
        Http2BodyWriter.start(channel, source);

        assertEquals(0, source.closed);
        assertNotNull(resumeHandler.get());
        verify(channel, times(1)).write(any(DefaultHttp2DataFrame.class));
        verify(channel, times(1)).flush();

        writable.set(true);
        ((io.netty.channel.ChannelInboundHandlerAdapter) resumeHandler.get())
                .channelWritabilityChanged(eventContext);

        assertEquals(0, source.closed);
        assertEquals(1, terminalFrames.get());
        terminalWrite.setSuccess();

        assertEquals(1, source.closed);
        verify(channel, times(3)).write(any(DefaultHttp2DataFrame.class));
        verify(channel, times(2)).flush();
        verify(pipeline).remove(resumeHandler.get());
    }

    private static ChannelFuture succeededFuture(Http2StreamChannel channel) {
        return new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE).setSuccess();
    }

    private static final class FixedChunkSource implements Http2BodyWriter.ChunkSource {
        private int chunks;
        private final int chunkSize;
        private int closed;

        FixedChunkSource(int chunks) {
            this(chunks, 1);
        }

        FixedChunkSource(int chunks, int chunkSize) {
            this.chunks = chunks;
            this.chunkSize = chunkSize;
        }

        @Override
        public ByteBuf nextChunk(ByteBufAllocator alloc) {
            if (chunks == 0) {
                return null;
            }
            chunks--;
            return alloc.buffer(chunkSize).writeZero(chunkSize);
        }

        @Override
        public void close() {
            closed++;
        }
    }

    private static final class SuspendingChunkSource implements Http2BodyWriter.ChunkSource {
        private final AtomicInteger flushes;
        private int state;
        private int closed;
        private int flushesBeforeSuspend;
        private Runnable resume;

        SuspendingChunkSource(AtomicInteger flushes) {
            this.flushes = flushes;
        }

        @Override
        public ByteBuf nextChunk(ByteBufAllocator alloc) {
            if (state < 2) {
                state++;
                return alloc.buffer(1).writeByte(state);
            }
            if (state == 2) {
                flushesBeforeSuspend = flushes.get();
                return Http2BodyWriter.SUSPEND;
            }
            return null;
        }

        @Override
        public void onResume(Runnable resume) {
            this.resume = resume;
        }

        void finish() {
            state = 3;
            resume.run();
        }

        @Override
        public void close() {
            closed++;
        }
    }

    private static final class UnflushedBytesTracker extends ChannelOutboundHandlerAdapter {
        private int unflushedBytes;
        private int peakUnflushedBytes;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof Http2DataFrame) {
                unflushedBytes += ((Http2DataFrame) msg).content().readableBytes();
                peakUnflushedBytes = Math.max(peakUnflushedBytes, unflushedBytes);
            }
            ctx.write(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            unflushedBytes = 0;
            ctx.flush();
        }
    }
}
