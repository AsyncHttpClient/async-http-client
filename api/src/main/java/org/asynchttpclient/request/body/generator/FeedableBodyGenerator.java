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

/**
 * {@link BodyGenerator} which may return just part of the payload at the time handler is requesting it.
 * If it happens, PartialBodyGenerator becomes responsible for finishing payload transferring asynchronously.
 */
public final class FeedableBodyGenerator implements BodyGenerator {
    private final static byte[] END_PADDING = "\r\n".getBytes(US_ASCII);
    private final static byte[] ZERO = "0".getBytes(US_ASCII);
    private final Queue<BodyPart> queue = new ConcurrentLinkedQueue<>();
    private FeedListener listener;

    @Override
    public Body createBody() {
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

    private static enum PushBodyState {
        ONGOING, CLOSING, FINISHED;
    }
    
    private final class PushBody implements Body {

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
            int capacity = buffer.remaining() - 10; // be safe (we'll have to add size, ending, etc.)
            int size = Math.min(nextPart.buffer.remaining(), capacity);
            if (size != 0) {
                buffer.put(Integer.toHexString(size).getBytes(US_ASCII));
                buffer.put(END_PADDING);
                for (int i = 0; i < size; i++) {
                    buffer.put(nextPart.buffer.get());
                }
                buffer.put(END_PADDING);
            }
            if (!nextPart.buffer.hasRemaining()) {
                if (nextPart.isLast) {
                    state = PushBodyState.CLOSING;
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
