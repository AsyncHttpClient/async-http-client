/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
import java.util.concurrent.ConcurrentLinkedQueue;

import org.asynchttpclient.request.body.Body;

public final class SimpleFeedableBodyGenerator implements FeedableBodyGenerator, BodyGenerator {
    private final Queue<BodyPart> queue = new ConcurrentLinkedQueue<>();
    private FeedListener listener;

    @Override
    public Body createBody() {
        return new PushBody();
    }

    @Override
    public void feed(final ByteBuffer buffer, final boolean isLast) {
        queue.offer(new BodyPart(buffer, isLast));
        if (listener != null) {
            listener.onContentAdded();
        }
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
                    return readNextPart(target);
                case STOP:
                    return BodyState.STOP;
                default:
                    throw new IllegalStateException("Illegal process state.");
            }
        }

        private BodyState readNextPart(ByteBuf target) throws IOException {
            BodyState res = BodyState.SUSPEND;
            while (target.isWritable() && state != BodyState.STOP) {
                BodyPart nextPart = queue.peek();
                if (nextPart == null) {
                    // Nothing in the queue. suspend stream if nothing was read. (reads == 0)
                    return res;
                } else if (!nextPart.buffer.hasRemaining() && !nextPart.isLast) {
                    // skip empty buffers
                    queue.remove();
                } else {
                    res = BodyState.CONTINUE;
                    readBodyPart(target, nextPart);
                }
            }
            return res;
        }

        private void readBodyPart(ByteBuf target, BodyPart part) {
            move(target, part.buffer);

            if (!part.buffer.hasRemaining()) {
                if (part.isLast) {
                    state = BodyState.STOP;
                }
                queue.remove();
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

    private final class BodyPart {
        private final boolean isLast;
        private final ByteBuffer buffer;

        public BodyPart(final ByteBuffer buffer, final boolean isLast) {
            this.buffer = buffer;
            this.isLast = isLast;
        }
    }
}
