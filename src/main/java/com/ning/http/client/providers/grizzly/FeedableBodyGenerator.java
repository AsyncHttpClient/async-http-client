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
package com.ning.http.client.providers.grizzly;

import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.HeapBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link BodyGenerator} which may return just part of the payload at the time
 * handler is requesting it. If it happens - PartialBodyGenerator becomes responsible
 * for finishing payload transferring asynchronously.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class FeedableBodyGenerator implements BodyGenerator {

    private static final HeapBuffer EMPTY_BUFFER = HeapBuffer.wrap(new byte[0]);

    private final Queue<BodyPart> queue;
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AddBodyPartToQueueStrategy addBodyPartToQueueStrategy;

    public FeedableBodyGenerator(Queue<BodyPart> queueImplementation,AddBodyPartToQueueStrategy addBodyPartToQueueStrategy) {
        if ( queueImplementation == null ) {
            throw new IllegalArgumentException("No queue implementation provided!");
        }
        if ( addBodyPartToQueueStrategy == null ) {
            throw new IllegalArgumentException("No implementation provided for addBodyPartToQueueStrategy");
        }
        this.queue = queueImplementation;
        this.addBodyPartToQueueStrategy = addBodyPartToQueueStrategy;
    }

    /**
     * Permits to create an unbounded queue.
     * The thread feeding the body generator will never block.
     * This can cause memory problems.
     */
    public FeedableBodyGenerator() {
        this(new ConcurrentLinkedQueue<BodyPart>(),AddBodyPartToQueueStrategy.DEFAULT);
    }

    /**
     * Permits to create a bounded queue.
     * If the queue is full of BodyParts, the thread feeding the body generator will block, until the queue is consumed.
     * This permits to limit the memory consumption of the feeding, because if the producing/feeding thread feeds faster
     * that the BodyParts are consumed/written, then a lot of BodyPart/Buffer may be stay in memory.
     *
     * This is an useful feature to handle a large file upload and to guarantee a low memory consumption.
     *
     * @param maxQueueSize
     */
    public FeedableBodyGenerator(int maxQueueSize) {
        this(new LinkedBlockingQueue<BodyPart>(maxQueueSize),AddBodyPartToQueueStrategy.DEFAULT);
    }


    private volatile HttpRequestPacket requestPacket;
    private volatile FilterChainContext context;

    @Override
    public Body createBody() throws IOException {
        return new EmptyBody();
    }

    /**
     * The Buffer you want to send.
     *
     * @param buffer
     * @param isLast should be true for the last chunk of data to send
     * @throws IOException
     */
    public void feed(final Buffer buffer, final boolean isLast) throws IOException {
        BodyPart bodyPart = new BodyPart(buffer, isLast);
        addBodyPartToQueueStrategy.add(bodyPart,queue);
        queueSize.incrementAndGet();
        if (context != null) {
            flushQueue();
        }
    }

    /**
     * This permits to the the isLast signal without writing any new data to the body.
     * If you called {@link #feed(Buffer, boolean)} you don't need to call this method.
     * @throws IOException
     */
    public void isLast() throws IOException {
        feed(EMPTY_BUFFER, true);
    }

    void initializeAsynchronousTransfer(final FilterChainContext context,
                                        final HttpRequestPacket requestPacket) throws IOException {
        this.context = context;
        this.requestPacket = requestPacket;
        flushQueue();
    }

    private void flushQueue() throws IOException {
        if (queueSize.get() > 0) {
            synchronized(this) {
                while(queueSize.get() > 0) {
                    final BodyPart bodyPart = queue.poll();
                    queueSize.decrementAndGet();
                    final HttpContent content =
                            requestPacket.httpContentBuilder()
                                    .content(bodyPart.buffer)
                                    .last(bodyPart.isLast)
                                    .build();
                    context.write(content, ((!requestPacket.isCommitted()) ?
                            context.getTransportContext().getCompletionHandler() :
                            null));

                }
            }
        }
    }

    private final class EmptyBody implements Body {

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public long read(final ByteBuffer buffer) throws IOException {
            return 0;
        }

        @Override
        public void close() throws IOException {
            context.completeAndRecycle();
            context = null;
            requestPacket = null;
        }
    }

    private final static class BodyPart {
        private final boolean isLast;
        private final Buffer buffer;

        public BodyPart(final Buffer buffer, final boolean isLast) {
            this.buffer = buffer;
            this.isLast = isLast;
        }
    }

    /**
     * Strategy to customize the behavior to add a bodypart to the queue.
     * This may be useful to customize according to the implementation you choose.
     */
    public interface AddBodyPartToQueueStrategy {

        public void add(BodyPart bodyPart,Queue<BodyPart> queue);

        AddBodyPartToQueueStrategy DEFAULT = new AddBodyPartToQueueStrategy() {
            @Override
            public void add(BodyPart bodyPart, Queue<BodyPart> queue) {
                if ( queue instanceof BlockingQueue ) {
                    BlockingQueue<BodyPart> blockingQueue = (BlockingQueue<BodyPart>)queue;
                    try {
                        blockingQueue.put(bodyPart); // this call blocks until there is more space
                    } catch (InterruptedException e) {
                        throw new IllegalStateException("Could not insert BodyPart to the blocking queue",e);
                    }
                } else {
                    boolean added = queue.offer(bodyPart);
                    if ( !added ) {
                        throw new IllegalStateException("Could not insert BodyPart to non blocking queue.");
                    }
                }
            }
        };
    }

}

