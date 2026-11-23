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
import io.netty.handler.stream.ChunkedWriteHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.WriteProgressListener;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.BodyState;
import org.asynchttpclient.request.body.RandomAccessBody;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.generator.FeedListener;
import org.asynchttpclient.request.body.generator.FeedableBodyGenerator;

import java.io.IOException;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

public class NettyBodyBody implements NettyBody {

    private final Body body;
    private final AsyncHttpClientConfig config;

    public NettyBodyBody(Body body, AsyncHttpClientConfig config) {
        this.body = body;
        this.config = config;
    }

    public Body getBody() {
        return body;
    }

    @Override
    public long getContentLength() {
        return body.getContentLength();
    }

    @Override
    public void write(final Channel channel, NettyResponseFuture<?> future) {

        Object msg;
        if (body instanceof RandomAccessBody && !ChannelManager.isSslHandlerConfigured(channel.pipeline()) && !config.isDisableZeroCopy() && getContentLength() > 0) {
            msg = new BodyFileRegion((RandomAccessBody) body);

        } else {
            msg = new BodyChunkedInput(body);

            BodyGenerator bg = future.getTargetRequest().getBodyGenerator();
            if (bg instanceof FeedableBodyGenerator) {
                final ChunkedWriteHandler chunkedWriteHandler = channel.pipeline().get(ChunkedWriteHandler.class);
                ((FeedableBodyGenerator) bg).setListener(new FeedListener() {
                    @Override
                    public void onContentAdded() {
                        chunkedWriteHandler.resumeTransfer();
                    }

                    @Override
                    public void onError(Throwable t) {
                    }
                });
            }
        }

        channel.write(msg, channel.newProgressivePromise())
                .addListener(new WriteProgressListener(future, false, getContentLength()) {
                    @Override
                    public void operationComplete(ChannelProgressiveFuture cf) {
                        closeSilently(body);
                        super.operationComplete(cf);
                    }
                });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, channel.voidPromise());
    }

    @Override
    public void writeHttp2(Http2StreamChannel channel, NettyResponseFuture<?> future) throws IOException {
        // Stream the body one bounded chunk at a time with HTTP/2 flow control / writability backpressure,
        // so a large body does not buffer in heap or get drained inline on the event loop. Cleanup
        // (closeSilently(body)) happens when the async pump completes — see BodyChunkSource.close.
        BodyGenerator bg = future.getTargetRequest().getBodyGenerator();
        FeedableBodyGenerator feedable = bg instanceof FeedableBodyGenerator ? (FeedableBodyGenerator) bg : null;
        Http2BodyWriter.start(channel, new BodyChunkSource(body, feedable), future);
    }

    /**
     * Drains a {@link Body} in {@link #CHUNK_SIZE}-bounded chunks for {@link Http2BodyWriter}. A
     * {@link FeedableBodyGenerator} that has no data yet ({@link BodyState#SUSPEND}) parks the pump; the
     * generator's {@link FeedListener} resumes it when more content is fed — mirroring how the HTTP/1.1 path
     * uses {@code ChunkedWriteHandler.resumeTransfer()}.
     */
    private static final class BodyChunkSource implements Http2BodyWriter.ChunkSource {

        private static final int CHUNK_SIZE = 8192;

        private final Body body;
        private final FeedableBodyGenerator feedable;

        BodyChunkSource(Body body, FeedableBodyGenerator feedable) {
            this.body = body;
            this.feedable = feedable;
        }

        @Override
        public ByteBuf nextChunk(ByteBufAllocator alloc) throws IOException {
            ByteBuf buf = alloc.buffer(CHUNK_SIZE);
            try {
                while (true) {
                    buf.clear();
                    BodyState state = body.transferTo(buf);
                    if (buf.isReadable()) {
                        ByteBuf chunk = buf;
                        buf = null; // ownership transferred to caller
                        return chunk;
                    }
                    switch (state) {
                        case STOP:
                            return null;
                        case SUSPEND:
                            return Http2BodyWriter.SUSPEND;
                        case CONTINUE:
                            // No data produced this turn but the body continues; retry. Finite in-memory
                            // bodies never hit this — it only matters for generators that momentarily yield
                            // nothing without suspending.
                            break;
                        default:
                            throw new IllegalStateException("Unknown body state: " + state);
                    }
                }
            } finally {
                if (buf != null) {
                    buf.release();
                }
            }
        }

        @Override
        public void onResume(Runnable resume) {
            // Only feedable generators can return SUSPEND; wire their feed notification to resume the pump.
            if (feedable != null) {
                feedable.setListener(new FeedListener() {
                    @Override
                    public void onContentAdded() {
                        resume.run();
                    }

                    @Override
                    public void onError(Throwable t) {
                        // The pump's own read/write error handling closes the stream; nothing to do here.
                    }
                });
            }
        }

        @Override
        public void close() {
            closeSilently(body);
        }
    }
}
