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
package com.ning.http.client.providers.netty.request.body;

import static java.nio.charset.StandardCharsets.*;

import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@link BodyGenerator} which may return just part of the payload at the time handler is requesting it.
 * If it happens, PartialBodyGenerator becomes responsible for finishing payload transferring asynchronously.
 */
public class FeedableBodyGenerator implements BodyGenerator {
    private final static byte[] END_PADDING = "\r\n".getBytes(US_ASCII);
    private final static byte[] ZERO = "0".getBytes(US_ASCII);
    private final Queue<BodyPart> queue = new ConcurrentLinkedQueue<>();
    private FeedListener listener;

    // must be set to true when using Netty 3 where native chunking is broken
    private boolean writeChunkBoundaries = false;

    @Override
    public Body createBody() throws IOException {
        return new PushBody();
    }

    public void feed(final ByteBuffer buffer, final boolean isLast) throws IOException {
        queue.offer(new BodyPart(buffer, isLast));
        if (listener != null) {
            listener.onContentAdded();
        }
    }

    public static interface FeedListener {
        void onContentAdded();
    }

    public void setListener(FeedListener listener) {
        this.listener = listener;
    }

    public void writeChunkBoundaries() {
        this.writeChunkBoundaries = true;
    }

    private static enum PushBodyState {
        ONGOING, CLOSING, FINISHED;
    }
    
    public final class PushBody implements Body {

        private PushBodyState state = PushBodyState.ONGOING;

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public long read(final ByteBuffer buffer) throws IOException {
            BodyPart nextPart = queue.peek();
            if (nextPart == null) {
                // Nothing in the queue
                switch (state) {
                case ONGOING:
                    return 0;
                case CLOSING:
                    buffer.put(ZERO);
                    buffer.put(END_PADDING);
                    buffer.put(END_PADDING);
                    state = PushBodyState.FINISHED;
                    return buffer.position();
                case FINISHED:
                    return -1;
                }
            }
            if (nextPart.buffer.remaining() == 0) {
                // skip empty buffers
                // if we return 0 here it would suspend the stream - we don't want that
                queue.remove();
                if (nextPart.isLast) {
                    state = writeChunkBoundaries ? PushBodyState.CLOSING : PushBodyState.FINISHED;
                }
                return read(buffer);
            }
            int capacity = buffer.remaining() - 10; // be safe (we'll have to add size, ending, etc.)
            int size = Math.min(nextPart.buffer.remaining(), capacity);
            if (size != 0) {
                if (writeChunkBoundaries) {
                    buffer.put(Integer.toHexString(size).getBytes(US_ASCII));
                    buffer.put(END_PADDING);
                }
                for (int i = 0; i < size; i++) {
                    buffer.put(nextPart.buffer.get());
                }
                if (writeChunkBoundaries)
                    buffer.put(END_PADDING);
            }
            if (!nextPart.buffer.hasRemaining()) {
                if (nextPart.isLast) {
                    state = writeChunkBoundaries ? PushBodyState.CLOSING : PushBodyState.FINISHED;
                }
                queue.remove();
            }
            return size;
        }

        @Override
        public void close() {
        }

    }

    private final static class BodyPart {
        private final boolean isLast;
        private final ByteBuffer buffer;

        public BodyPart(final ByteBuffer buffer, final boolean isLast) {
            this.buffer = buffer;
            this.isLast = isLast;
        }
    }
}
