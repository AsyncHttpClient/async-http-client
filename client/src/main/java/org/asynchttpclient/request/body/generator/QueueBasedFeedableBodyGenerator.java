/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;

import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.generator.QueueBasedFeedableBodyGenerator.BodyChunk;

public abstract class QueueBasedFeedableBodyGenerator<T extends Queue<BodyChunk>> implements FeedableBodyGenerator, BodyGenerator {

    private FeedListener listener;

    @Override
    public Body createBody() {
        return new PushBody();
    }

    protected abstract boolean offer(BodyChunk chunk) throws Exception;
    protected abstract Queue<BodyChunk> queue();
    
    @Override
    public boolean feed(final ByteBuffer buffer, final boolean isLast) throws Exception {
        boolean offered = offer(new BodyChunk(buffer, isLast));
        if (offered && listener != null) {
            listener.onContentAdded();
        }
        return offered;
    }

    @Override
    public void setListener(FeedListener listener) {
        this.listener = listener;
    }

    public final class PushBody implements Body {

        private BodyState state = BodyState.CONTINUE;

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public BodyState transferTo(final ByteBuf target) throws IOException {
            switch (state) {
            case CONTINUE:
                return readNextChunk(target);
            case STOP:
                return BodyState.STOP;
            default:
                throw new IllegalStateException("Illegal process state.");
            }
        }

        private BodyState readNextChunk(ByteBuf target) throws IOException {
            BodyState res = BodyState.SUSPEND;
            while (target.isWritable() && state != BodyState.STOP) {
                BodyChunk nextChunk = queue().peek();
                if (nextChunk == null) {
                    // Nothing in the queue. suspend stream if nothing was read. (reads == 0)
                    return res;
                } else if (!nextChunk.buffer.hasRemaining() && !nextChunk.isLast) {
                    // skip empty buffers
                    queue().remove();
                } else {
                    res = BodyState.CONTINUE;
                    readChunk(target, nextChunk);
                }
            }
            return res;
        }

        private void readChunk(ByteBuf target, BodyChunk part) {
            move(target, part.buffer);

            if (!part.buffer.hasRemaining()) {
                if (part.isLast) {
                    state = BodyState.STOP;
                }
                queue().remove();
            }
        }

        @Override
        public void close() {
        }
    }

    private void move(ByteBuf target, ByteBuffer source) {
        int size = Math.min(target.writableBytes(), source.remaining());
        if (size > 0) {
            ByteBuffer slice = source.slice();
            slice.limit(size);
            target.writeBytes(slice);
            source.position(source.position() + size);
        }
    }

    public static final class BodyChunk {
        private final boolean isLast;
        private final ByteBuffer buffer;

        public BodyChunk(final ByteBuffer buffer, final boolean isLast) {
            this.buffer = buffer;
            this.isLast = isLast;
        }
    }
}
