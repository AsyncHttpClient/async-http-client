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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.utils.Futures;

import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProvider.getHttpTransactionContext;
import static java.lang.Boolean.TRUE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.glassfish.grizzly.utils.Exceptions.*;

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
    private volatile HttpContent.Builder contentBuilder;

    private final EmptyBody EMPTY_BODY = new EmptyBody();



    // ---------------------------------------------- Methods from BodyGenerator


    @Override
    public Body createBody() throws IOException {
        return EMPTY_BODY;
    }


    // ---------------------------------------------------------- Public Methods


    /**
     * Feeds the specified buffer.  This buffer may be queued to be sent later
     * or sent immediately.  Note that this method may block if data is being
     * fed faster than it is being consumed by the peer.
     *
     * The maximum duration that this method may block is dependent on
     * the current value of {@link org.glassfish.grizzly.Transport#getWriteTimeout(java.util.concurrent.TimeUnit)}.
     * This value can be customized by using a {@link TransportCustomizer} to
     * fine-tune the transport used by the client instance.
     *
     * @param buffer the {@link Buffer} to feed.
     * @param last flag indicating if this is the final buffer of the message.
     * @throws IOException if an I/O error occurs.
     *
     * @see TransportCustomizer
     * @see GrizzlyAsyncHttpProviderConfig#addProperty(com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property, Object)
     * @see GrizzlyAsyncHttpProviderConfig.Property#TRANSPORT_CUSTOMIZER
     */
    @SuppressWarnings("UnusedDeclaration")
    public void feed(final Buffer buffer, final boolean last)
    throws IOException {
        queue.offer(new BodyPart(buffer, last));
        queueSize.incrementAndGet();
        
        if (context != null) {
            flushQueue(true);
        }
    }


    // ------------------------------------------------- Package Private Methods

    
    void initializeAsynchronousTransfer(final FilterChainContext context, 
                                        final HttpRequestPacket requestPacket)
    throws IOException {
        this.context = context;
        this.requestPacket = requestPacket;
        this.contentBuilder = HttpContent.builder(requestPacket);
        // don't block here.  If queue is full at the time of the next feed()
        // call, it will block.
        flushQueue(false);
    }


    // --------------------------------------------------------- Private Methods


    @SuppressWarnings("unchecked")
    private void flushQueue(final boolean allowBlocking) throws IOException {
        if (queueSize.get() > 0) {
            synchronized(this) {
                final Connection c = context.getConnection();
                while(queueSize.get() > 0) {
                    if (allowBlocking) {
                        blockUntilQueueFree(c);
                    }
                    final BodyPart bodyPart = queue.poll();
                    queueSize.decrementAndGet();
                    final HttpContent content =
                            contentBuilder.content(bodyPart.buffer)
                                    .last(bodyPart.isLast)
                            .build();
                    context.write(content,
                                  ((!requestPacket.isCommitted())
                                          ? context.getTransportContext()
                                                .getCompletionHandler()
                                          : null));
                }
            }
        }
    }

    /**
     * This method will block if the async write queue is currently larger
     * than the configured maximum.  The amount of time that this method
     * will block is dependent on the write timeout of the transport
     * associated with the specified connection.
     */
    private void blockUntilQueueFree(final Connection c) {
        if (!c.canWrite()) {
            final FutureImpl<Boolean> future =
                    Futures.createSafeFuture();

            // Connection may be obtained by calling FilterChainContext.getConnection().
            c.notifyCanWrite(new WriteHandler() {

                @Override
                public void onWritePossible() throws Exception {
                    future.result(TRUE);
                }

                @Override
                public void onError(Throwable t) {
                    future.failure(makeIOException(t));
                }
            });

            block(c, future);
        }
    }

    private void block(final Connection c,
                       final FutureImpl<Boolean> future) {
        try {
            final long writeTimeout =
                    c.getTransport().getWriteTimeout(MILLISECONDS);
            if (writeTimeout != -1) {
                future.get(writeTimeout, MILLISECONDS);
            } else {
                future.get();
            }
        } catch (ExecutionException e) {
            GrizzlyAsyncHttpProvider.HttpTransactionContext httpCtx =
                    getHttpTransactionContext(c);
            httpCtx.abort(e.getCause());
        } catch (Exception e) {
            GrizzlyAsyncHttpProvider.HttpTransactionContext httpCtx =
                    getHttpTransactionContext(c);
            httpCtx.abort(e);
        }
    }


    // ----------------------------------------------------------- Inner Classes


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
            contentBuilder = null;
        }
    }


    // ---------------------------------------------------------- Nested Classes


    private final static class BodyPart {
        private final boolean isLast;
        private final Buffer buffer;

        public BodyPart(final Buffer buffer, final boolean isLast) {
            this.buffer = buffer;
            this.isLast = isLast;
        }
    }
}
