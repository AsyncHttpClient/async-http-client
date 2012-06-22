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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;

/**
 * {@link BodyGenerator} which may return just part of the payload at the time
 * handler is requesting it. If it happens - PartialBodyGenerator becomes responsible
 * for finishing payload transferring asynchronously.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class FeedableBodyGenerator implements BodyGenerator {
    private final Queue<BodyPart> queue = new ConcurrentLinkedQueue<BodyPart>();
    private final AtomicInteger queueSize = new AtomicInteger();
    
    private volatile HttpRequestPacket requestPacket;
    private volatile FilterChainContext context;
    
    @Override
    public Body createBody() throws IOException {
        return new EmptyBody();
    }
    
    public void feed(final Buffer buffer, final boolean isLast)
            throws IOException {
        queue.offer(new BodyPart(buffer, isLast));
        queueSize.incrementAndGet();
        
        if (context != null) {
            flushQueue();            
        }
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
}
