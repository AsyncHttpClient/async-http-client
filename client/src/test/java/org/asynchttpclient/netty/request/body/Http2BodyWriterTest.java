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
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        when(channel.write(any())).thenAnswer(invocation -> {
            ReferenceCountUtil.release(invocation.getArgument(0));
            return succeededFuture(channel);
        });

        SuspendingChunkSource source = new SuspendingChunkSource();
        Http2BodyWriter.start(channel, source);

        assertEquals(0, source.closed);
        verify(channel, times(1)).write(any(DefaultHttp2DataFrame.class));
        verify(channel, times(1)).flush();

        source.finish();

        assertEquals(1, source.closed);
        verify(channel, times(2)).write(any(DefaultHttp2DataFrame.class));
        verify(channel, times(2)).flush();
    }

    @Test
    public void unwritableChannelFlushesAndResumesWhenWritable() throws Exception {
        Http2StreamChannel channel = mock(Http2StreamChannel.class);
        EventLoop eventLoop = mock(EventLoop.class);
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        ChannelHandlerContext pipelineContext = mock(ChannelHandlerContext.class);
        AtomicReference<ChannelHandler> resumeHandler = new AtomicReference<>();
        when(eventLoop.inEventLoop()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(eventLoop);
        when(channel.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
        when(channel.isWritable()).thenReturn(false, false, true);
        when(channel.closeFuture()).thenReturn(new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE));
        when(channel.pipeline()).thenReturn(pipeline);
        when(pipeline.addLast(any(ChannelHandler.class))).thenAnswer(invocation -> {
            resumeHandler.set(invocation.getArgument(0));
            return pipeline;
        });
        when(pipeline.context(any(ChannelHandler.class))).thenReturn(pipelineContext);
        when(channel.write(any())).thenAnswer(invocation -> {
            ReferenceCountUtil.release(invocation.getArgument(0));
            return succeededFuture(channel);
        });

        FixedChunkSource source = new FixedChunkSource(3);
        Http2BodyWriter.start(channel, source);

        assertEquals(0, source.closed);
        assertNotNull(resumeHandler.get());
        verify(channel, times(1)).write(any(DefaultHttp2DataFrame.class));
        verify(channel, times(1)).flush();

        ChannelHandlerContext eventContext = mock(ChannelHandlerContext.class);
        when(eventContext.channel()).thenReturn(channel);
        ((io.netty.channel.ChannelInboundHandlerAdapter) resumeHandler.get())
                .channelWritabilityChanged(eventContext);

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
        private int closed;

        FixedChunkSource(int chunks) {
            this.chunks = chunks;
        }

        @Override
        public ByteBuf nextChunk(ByteBufAllocator alloc) {
            if (chunks == 0) {
                return null;
            }
            chunks--;
            return alloc.buffer(1).writeByte(chunks);
        }

        @Override
        public void close() {
            closed++;
        }
    }

    private static final class SuspendingChunkSource implements Http2BodyWriter.ChunkSource {
        private int state;
        private int closed;
        private Runnable resume;

        @Override
        public ByteBuf nextChunk(ByteBufAllocator alloc) {
            if (state < 2) {
                state++;
                return alloc.buffer(1).writeByte(state);
            }
            return state == 2 ? Http2BodyWriter.SUSPEND : null;
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
}
