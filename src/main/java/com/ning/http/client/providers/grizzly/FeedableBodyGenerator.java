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
import java.util.concurrent.ExecutionException;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.ssl.SSLBaseFilter;
import org.glassfish.grizzly.ssl.SSLFilter;
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

    /**
     * There is no limit on bytes waiting to be written.  This configuration
     * value should be used with caution as it could lead to out-of-memory
     * conditions.
     */
    @SuppressWarnings("UnusedDeclaration")
    public static final int UNBOUND = -1;

    /**
     * Defer to whatever the connection has been configured for max pending bytes.
     */
    public static final int DEFAULT = -2;

    private volatile HttpRequestPacket requestPacket;
    private volatile FilterChainContext context;
    private volatile HttpContent.Builder contentBuilder;

    private final EmptyBody EMPTY_BODY = new EmptyBody();

    private Feeder feeder;
    private int origMaxPendingBytes;
    private int configuredMaxPendingBytes = DEFAULT;
    private boolean asyncTransferInitiated;
    private FutureImpl<Boolean> prematureFeed = Futures.createSafeFuture();


    // ---------------------------------------------- Methods from BodyGenerator


    /**
     * {@inheritDoc}
     */
    @Override
    public Body createBody() throws IOException {
        return EMPTY_BODY;
    }


    // ---------------------------------------------------------- Public Methods


    /**
     * Configured the maximum number of bytes that may be pending to be written
     * to the wire.  If not explicitly configured, the connection's current
     * configuration will be used instead.
     *
     * Once all data has been fed, the connection's max pending bytes configuration
     * will be restored to its original value.
     *
     * @param maxPendingBytes maximum number of bytes that may be queued to
     *                        be written to the wire.
     *
     * @throws IllegalStateException if called after {@link #initializeAsynchronousTransfer(FilterChainContext, HttpRequestPacket)}
     *  has been called by the {@link GrizzlyAsyncHttpProvider}.
     * @throws IllegalArgumentException if maxPendingBytes is less than zero and is
     *  not {@link #UNBOUND} or {@link #DEFAULT}.
     */
    public synchronized void setMaxPendingBytes(final int maxPendingBytes) {
        if (maxPendingBytes < DEFAULT) {
            throw new IllegalArgumentException("Invalid maxPendingBytes value: " + maxPendingBytes);
        }
        if (asyncTransferInitiated) {
            throw new IllegalStateException("Unable to set max pending bytes after async data transfer has been initiated.");
        }
        configuredMaxPendingBytes = maxPendingBytes;
    }


    /**
     * Add a {@link Feeder} implementation that will be invoked when writing
     * without blocking is possible.  This method must be set before dispatching
     * the request this feeder is associated with.
     *
     * @param feeder the {@link Feeder} responsible for providing data.
     *
     * @throws IllegalStateException if called after {@link #initializeAsynchronousTransfer(FilterChainContext, HttpRequestPacket)}
     *  has been called by the {@link GrizzlyAsyncHttpProvider}.
     * @throws IllegalArgumentException if <code>feeder</code> is <code>null</code>
     */
    @SuppressWarnings("UnusedDeclaration")
    public synchronized void setFeeder(final Feeder feeder) {
        if (asyncTransferInitiated) {
            throw new IllegalStateException("Unable to set Feeder after async data transfer has been initiated.");
        }
        if (feeder == null) {
            throw new IllegalArgumentException("Feeder argument cannot be null.");
        }
        this.feeder = feeder;
    }


    /**
     * Feeds the specified buffer.  Note that this method will block until
     * {@link #asyncTransferInitiated} has been invoked by the {@link GrizzlyAsyncHttpProvider}.
     * Once the request has been dispatched, the method will become unblocked, but
     * may block again if the amount of data fed exceeds the value as configured
     * by {@link #setMaxPendingBytes(int)}.
     *
     * The maximum duration that this method may block is dependent on
     * the current value of {@link org.glassfish.grizzly.Transport#getWriteTimeout(java.util.concurrent.TimeUnit)}.
     * This value can be customized by using a {@link TransportCustomizer} to
     * fine-tune the transport used by the client instance.
     *
     * Alternatively, it is <em>highly</em> recommended to only invoke this method
     * with in the context of {@link FeedableBodyGenerator.Feeder#canFeed()}.  By providing
     * an implementation of {@link Feeder} the runtime can eliminate blocking.
     *
     * @param buffer the {@link Buffer} to feed.
     * @param last flag indicating if this is the final buffer of the message.
     *
     * @throws IOException if an I/O error occurs.
     * @throws java.lang.IllegalArgumentException if <code>buffer</code> is <code>null</code>.
     *
     * @see TransportCustomizer
     * @see Feeder
     * @see GrizzlyAsyncHttpProviderConfig#addProperty(com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property, Object)
     * @see GrizzlyAsyncHttpProviderConfig.Property#TRANSPORT_CUSTOMIZER
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public synchronized void feed(final Buffer buffer, final boolean last)
    throws IOException {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer argument cannot be null.");
        }
        if (asyncTransferInitiated) {
            write(buffer, last);
        } else {
            try {
                prematureFeed.get();
            } catch (Exception e) {
                throw new IOException(e);
            }
            write(buffer, last);
        }
    }


    // ------------------------------------------------- Package Private Methods

    
    synchronized void initializeAsynchronousTransfer(final FilterChainContext context,
                                                     final HttpRequestPacket requestPacket)
    throws IOException {

        if (asyncTransferInitiated) {
            throw new IllegalStateException("Async transfer has already been initiated.");
        }
        assert (context != null);
        assert (requestPacket != null);

        asyncTransferInitiated = true;
        this.requestPacket = requestPacket;
        this.contentBuilder = HttpContent.builder(requestPacket);
        final Connection c = context.getConnection();
        origMaxPendingBytes = c.getMaxAsyncWriteQueueSize();
        if (configuredMaxPendingBytes != DEFAULT) {
            c.setMaxAsyncWriteQueueSize(configuredMaxPendingBytes);
        }
        this.context = context;

        if (feeder != null) {
            if (requestPacket.isSecure()) {
                flushOnSSLHandshakeComplete();
            } else {
                flushViaFeeder();
            }
        } else {
            prematureFeed.result(Boolean.TRUE);
        }
    }


    // --------------------------------------------------------- Private Methods


    private void flushOnSSLHandshakeComplete() throws IOException {
        final FilterChain filterChain = context.getFilterChain();
        final int idx = filterChain.indexOfType(SSLFilter.class);
        assert (idx != -1);
        final SSLFilter filter = (SSLFilter) filterChain.get(idx);
        filter.addHandshakeListener(new SSLBaseFilter.HandshakeListener() {
            public void onStart(Connection connection) {
                System.out.println("HANDSHAKE STARTED");
            }

            public void onComplete(Connection connection) {
                flushViaFeeder();
                filter.removeHandshakeListener(this);
            }
        });
        filter.handshake(context.getConnection(),  null);
    }


    @SuppressWarnings("unchecked")
    private void write(final Buffer buffer, final boolean last) {
        blockUntilQueueFree(context.getConnection());
        final HttpContent content =
                            contentBuilder.content(buffer).last(last).build();
        final CompletionHandler<WriteResult> handler =
                ((last) ? new LastPacketCompletionHandler() : null);
        context.write(content, handler);
    }

    private void flushViaFeeder() {
        final Connection c = context.getConnection();

        if (feeder.isReady()) {
            writeUntilFullOrDone(c);
            if (!feeder.isDone()) {
                if (!feeder.isReady()) {
                    feeder.notifyReadyToFeed(new ReadyToFeedListenerImpl());
                }
                if (!c.canWrite()) {
                    // write queue is full, leverage WriteListener to let us know
                    // when it is safe to write again.
                    c.notifyCanWrite(new WriteHandlerImpl());
                }
            }
        } else {
            feeder.notifyReadyToFeed(new ReadyToFeedListenerImpl());
        }
    }

    private void writeUntilFullOrDone(final Connection c) {
        while (c.canWrite()) {
            if (feeder.isReady()) {
                feeder.canFeed();
            }
            if (!feeder.isReady()) {
                break;
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

    } // END EmptyBody


    private final class LastPacketCompletionHandler implements CompletionHandler<WriteResult> {

        private final CompletionHandler<WriteResult> delegate;
        private final Connection c;

        // -------------------------------------------------------- Constructors


        @SuppressWarnings("unchecked")
        private LastPacketCompletionHandler() {
            delegate = ((!requestPacket.isCommitted())
                    ? context.getTransportContext().getCompletionHandler()
                    : null);
            c = context.getConnection();
        }


        // -------------------------------------- Methods from CompletionHandler


        @Override
        public void cancelled() {
            c.setMaxAsyncWriteQueueSize(origMaxPendingBytes);
            if (delegate != null) {
                delegate.cancelled();
            }
        }

        @Override
        public void failed(Throwable throwable) {
            c.setMaxAsyncWriteQueueSize(origMaxPendingBytes);
            if (delegate != null) {
                delegate.failed(throwable);
            }

        }

        @Override
        public void completed(WriteResult result) {
            c.setMaxAsyncWriteQueueSize(origMaxPendingBytes);
            if (delegate != null) {
                delegate.completed(result);
            }

        }

        @Override
        public void updated(WriteResult result) {
            if (delegate != null) {
                delegate.updated(result);
            }
        }

    } // END LastPacketCompletionHandler


    // ---------------------------------------------------------- Nested Classes


    /**
     * Developers may provide implementations of this class in order to
     * feed data to the {@link FeedableBodyGenerator} without blocking.
     */
    public static abstract class Feeder {


        protected final FeedableBodyGenerator feedableBodyGenerator;


        // -------------------------------------------------------- Constructors


        public Feeder(final FeedableBodyGenerator feedableBodyGenerator) {
            this.feedableBodyGenerator = feedableBodyGenerator;
        }


        // ------------------------------------------------------ Public Methods


        /**
         * Notification that it's possible to send another block of data via
         * {@link #feed(org.glassfish.grizzly.Buffer, boolean)}.
         *
         * It's important to only invoke {@link #feed(Buffer, boolean)}
         * once per invocation of {@link #canFeed()}.
         */
        public abstract void canFeed();

        /**
         * @return <code>true</code> if all data has been fed by this feeder,
         *  otherwise returns <code>false</code>.
         */
        public abstract boolean isDone();

        /**
         * @return <code>true</code> if data is available to be fed, otherwise
         *  returns <code>false</code>.  When this method returns <code>false</code>,
         *  the {@link FeedableBodyGenerator} will call {@link #notifyReadyToFeed(ReadyToFeedListener)}
         *  by which this {@link Feeder} implementation may signal data is once
         *  again available to be fed.
         */
        public abstract boolean isReady();

        /**
         * Callback registration to signal the {@link FeedableBodyGenerator} that
         * data is available once again to continue feeding.  Once this listener
         * has been invoked, the Feeder implementation should no longer maintain
         * a reference to the listener.
         */
        public abstract void notifyReadyToFeed(final ReadyToFeedListener listener);


        // ------------------------------------------------------- Inner Classes


        /**
         * Listener to signal that data is available to be fed.
         */
        public interface ReadyToFeedListener {

            /**
             * Data is once again ready to be fed.
             */
            @SuppressWarnings("UnusedDeclaration")
            void ready();

        } // END ReadyToFeedListener

    } // END Feeder


    private final class WriteHandlerImpl implements WriteHandler {


        private final Connection c;


        // -------------------------------------------------------- Constructors


        private WriteHandlerImpl() {
            this.c = context.getConnection();
        }


        // ------------------------------------------ Methods from WriteListener

        @Override
        public void onWritePossible() throws Exception {
            writeUntilFullOrDone(c);
            if (!feeder.isDone()) {
                if (!feeder.isReady()) {
                    feeder.notifyReadyToFeed(new ReadyToFeedListenerImpl());
                }
                if (!c.canWrite()) {
                    // write queue is full, leverage WriteListener to let us know
                    // when it is safe to write again.
                    c.notifyCanWrite(this);
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            c.setMaxAsyncWriteQueueSize(origMaxPendingBytes);
            GrizzlyAsyncHttpProvider.HttpTransactionContext ctx =
                    GrizzlyAsyncHttpProvider.getHttpTransactionContext(c);
            ctx.abort(t);
        }

    } // END WriteHandlerImpl


    private final class ReadyToFeedListenerImpl implements Feeder.ReadyToFeedListener {


        // ------------------------------------ Methods from ReadyToFeedListener


        @Override
        public void ready() {
            flushViaFeeder();
        }

    } // END ReadToFeedListenerImpl

}
