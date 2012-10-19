/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;

/**
 * {@link BodyGenerator} which may return just part of the payload at the time
 * handler is requesting it. If it happens - PartialBodyGenerator becomes responsible
 * for finishing payload transferring asynchronously.
 */
public class FeedableBodyGenerator implements BodyGenerator {
    private final static byte[] END_PADDING = "\r\n".getBytes();
    private final static byte[] ZERO = "0".getBytes();
    private final Queue<BodyPart> queue = new ConcurrentLinkedQueue<BodyPart>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private FeedListener listener;

    @Override
    public Body createBody() throws IOException {
        return new PushBody();
    }

    public void feed(final ByteBuffer buffer, final boolean isLast) throws IOException {
        queue.offer(new BodyPart(buffer, isLast));
        queueSize.incrementAndGet();
        if (listener != null) {
            listener.onContentAdded();
        }
    }

    public static interface FeedListener {
        public void onContentAdded();
    }

    public void setListener(FeedListener listener) {
        this.listener = listener;
    }

    private final class PushBody implements Body {
        private final int ONGOING = 0;
        private final int CLOSING = 1;
        private final int FINISHED = 2;

        private int finishState = 0;

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public long read(final ByteBuffer buffer) throws IOException {
            BodyPart nextPart = queue.peek();
            if (nextPart == null) {
                // Nothing in the queue
                switch (finishState) {
                case ONGOING:
                    return 0;
                case CLOSING:
                    buffer.put(ZERO);
                    buffer.put(END_PADDING);
                    finishState = FINISHED;
                    return buffer.position();
                case FINISHED:
                    buffer.put(END_PADDING);
                    return -1;
                }
            }
            int capacity = buffer.remaining() - 10; // be safe (we'll have to add size, ending, etc.)
            int size = Math.min(nextPart.buffer.remaining(), capacity);
            buffer.put(Integer.toHexString(size).getBytes());
            buffer.put(END_PADDING);
            for (int i=0; i < size; i++) {
              buffer.put(nextPart.buffer.get());
            }
            buffer.put(END_PADDING);
            if (!nextPart.buffer.hasRemaining()) {
                if (nextPart.isLast) {
                    finishState = CLOSING;
                }
                queue.remove();
            }
            return size;
        }

        @Override
        public void close() throws IOException {
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
