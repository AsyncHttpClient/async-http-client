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
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
