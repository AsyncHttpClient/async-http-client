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

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.asynchttpclient.request.body.Body;

public final class SimpleFeedableBodyGenerator implements FeedableBodyGenerator, BodyGenerator {
    private final static byte[] END_PADDING = "\r\n".getBytes(US_ASCII);
    private final static byte[] ZERO = "0".getBytes(US_ASCII);
    private final static ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
    private final Queue<BodyPart> queue = new ConcurrentLinkedQueue<>();
    private FeedListener listener;

    // must be set to true when using Netty 3 where native chunking is broken
    private boolean writeChunkBoundaries = false;

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

    @Override
    public void writeChunkBoundaries() {
        this.writeChunkBoundaries = true;
    }

    public final class PushBody implements Body {

        private State state = State.Continue;

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public State read(final ByteBuffer buffer) throws IOException {
            switch (state) {
                case Continue:
                    return readNextPart(buffer);
                case Stop:
                    return State.Stop;
                default:
                    throw new IllegalStateException("Illegal process state.");
            }
        }

        private State readNextPart(ByteBuffer buffer) throws IOException {
            State res = State.Suspend;
            while (buffer.hasRemaining() && state != State.Stop) {
                BodyPart nextPart = queue.peek();
                if (nextPart == null) {
                    // Nothing in the queue. suspend stream if nothing was read. (reads == 0)
                    return res;
                } else if (!nextPart.buffer.hasRemaining() && !nextPart.isLast) {
                    // skip empty buffers
                    queue.remove();
                } else {
                    res = State.Continue;
                    readBodyPart(buffer, nextPart);
                }
            }
            return res;
        }

        private void readBodyPart(ByteBuffer buffer, BodyPart part) {
            part.initBoundaries();
            move(buffer, part.size);
            move(buffer, part.buffer);
            move(buffer, part.endPadding);

            if (!part.buffer.hasRemaining() && !part.endPadding.hasRemaining()) {
                if (part.isLast) {
                    state = State.Stop;
                }
                queue.remove();
            }
        }

        @Override
        public void close() {
        }
    }

    private void move(ByteBuffer destination, ByteBuffer source) {
        int size = Math.min(destination.remaining(), source.remaining());
        if (size > 0) {
            ByteBuffer slice = source.slice();
            slice.limit(size);
            destination.put(slice);
            source.position(source.position() + size);
        }
    }

    private final class BodyPart {
        private final boolean isLast;
        private ByteBuffer size = null;
        private final ByteBuffer buffer;
        private ByteBuffer endPadding = null;

        public BodyPart(final ByteBuffer buffer, final boolean isLast) {
            this.buffer = buffer;
            this.isLast = isLast;
        }

        private void initBoundaries() {
            if(size == null && endPadding == null) {
                if (SimpleFeedableBodyGenerator.this.writeChunkBoundaries) {
                    if(buffer.hasRemaining()) {
                        final byte[] sizeAsHex = Integer.toHexString(buffer.remaining()).getBytes(US_ASCII);
                        size = ByteBuffer.allocate(sizeAsHex.length + END_PADDING.length);
                        size.put(sizeAsHex);
                        size.put(END_PADDING);
                        size.flip();
                    } else {
                        size = EMPTY_BUFFER;
                    }

                    if(isLast) {
                        endPadding = ByteBuffer.allocate(END_PADDING.length * 3 + ZERO.length);
                        if(buffer.hasRemaining()) {
                            endPadding.put(END_PADDING);
                        }

                        //add last empty
                        endPadding.put(ZERO);
                        endPadding.put(END_PADDING);
                        endPadding.put(END_PADDING);
                        endPadding.flip();
                    } else {
                        endPadding = ByteBuffer.wrap(END_PADDING);
                    }
                } else {
                    size = EMPTY_BUFFER;
                    endPadding = EMPTY_BUFFER;
                }
            }
        }
    }
}
