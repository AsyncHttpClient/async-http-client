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
package org.asynchttpclient.providers.grizzly;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.asynchttpclient.Body;
import org.asynchttpclient.BodyGenerator;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.SelectorRunner;
import org.glassfish.grizzly.utils.Futures;

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
     * <p/>
     * Once all data has been fed, the connection's max pending bytes configuration
     * will be restored to its original value.
     *
     * @param maxPendingBytes maximum number of bytes that may be queued to
     *                        be written to the wire.
     * @throws IllegalStateException    if called after {@link #initializeAsynchronousTransfer(FilterChainContext, HttpRequestPacket)}
     *                                  has been called by the {@link GrizzlyAsyncHttpProvider}.
     * @throws IllegalArgumentException if maxPendingBytes is less than zero and is
     *                                  not {@link #UNBOUND} or {@link #DEFAULT}.
     */
    @SuppressWarnings("UnusedDeclaration")
    public synchronized void setMaxPendingBytes(final int maxPendingBytes) {
        if (maxPendingBytes < DEFAULT) {
            throw new IllegalArgumentException(
                    "Invalid maxPendingBytes value: " + maxPendingBytes);
        }
        if (asyncTransferInitiated) {
            throw new IllegalStateException(
                    "Unable to set max pending bytes after async data transfer has been initiated.");
        }
        configuredMaxPendingBytes = maxPendingBytes;
    }


    /**
     * Add a {@link Feeder} implementation that will be invoked when writing
     * without blocking is possible.  This method must be set before dispatching
     * the request this feeder is associated with.
     *
     * @param feeder the {@link Feeder} responsible for providing data.
     * @throws IllegalStateException    if called after {@link #initializeAsynchronousTransfer(FilterChainContext, HttpRequestPacket)}
     *                                  has been called by the {@link GrizzlyAsyncHttpProvider}.
     * @throws IllegalArgumentException if <code>feeder</code> is <code>null</code>
     */
    @SuppressWarnings("UnusedDeclaration")
    public synchronized void setFeeder(final Feeder feeder) {
        if (asyncTransferInitiated) {
            throw new IllegalStateException(
                    "Unable to set Feeder after async data transfer has been initiated.");
        }
        if (feeder == null) {
            throw new IllegalArgumentException(
                    "Feeder argument cannot be null.");
        }
        this.feeder = feeder;
    }


    // ------------------------------------------------- Package Private Methods


    /**
     * Even though this method is public, it's not intended to be called by
     * Developers directly.  Please avoid doing so.
     */
    public synchronized void initializeAsynchronousTransfer(final FilterChainContext context,
                                                            final HttpRequestPacket requestPacket)
    throws IOException {

        if (asyncTransferInitiated) {
            throw new IllegalStateException(
                    "Async transfer has already been initiated.");
        }
        if (feeder == null) {
            throw new IllegalStateException(
                    "No feeder available to perform the transfer.");
        }
        assert (context != null);
        assert (requestPacket != null);

        this.requestPacket = requestPacket;
        this.contentBuilder = HttpContent.builder(requestPacket);
        final Connection c = context.getConnection();
        origMaxPendingBytes = c.getMaxAsyncWriteQueueSize();
        if (configuredMaxPendingBytes != DEFAULT) {
            c.setMaxAsyncWriteQueueSize(configuredMaxPendingBytes);
        }
        this.context = context;
        asyncTransferInitiated = true;
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    feeder.flush();
                } catch (IOException ioe) {
                    HttpTransactionContext ctx = HttpTransactionContext.get(c);
                    ctx.abort(ioe);
                }
            }
        };

        // If the current thread is a selector thread, we need to execute
        // the remainder of the task on the worker thread to prevent
        // it from being blocked.
        if (isCurrentThreadSelectorRunner()) {
            c.getTransport().getWorkerThreadPool().execute(r);
        } else {
            r.run();
        }
    }


    // --------------------------------------------------------- Private Methods


    private boolean isCurrentThreadSelectorRunner() {
        final NIOConnection c = (NIOConnection) context.getConnection();
        final SelectorRunner runner = c.getSelectorRunner();
        return (Thread.currentThread() == runner.getRunnerThread());
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


    // ---------------------------------------------------------- Nested Classes


    /**
     * Specifies the functionality all Feeders must implement.  Typically,
     * developers need not worry about implementing this interface directly.
     * It should be sufficient, for most use-cases, to simply use the {@link NonBlockingFeeder}
     * or {@link SimpleFeeder} implementations.
     */
    public interface Feeder {

        /**
         * This method will be invoked when it's possible to begin feeding
         * data downstream.  Implementations of this method must use {@link #feed(Buffer, boolean)}
         * to perform the actual write.
         *
         * @throws IOException if an I/O error occurs.
         */
        void flush() throws IOException;

        /**
         * This method will write the specified {@link Buffer} to the connection.
         * Be aware that this method may block depending if data is being fed
         * faster than it can write.  How much data may be queued is dictated
         * by {@link #setMaxPendingBytes(int)}.  Once this threshold is exceeded,
         * the method will block until the write queue length drops below the
         * aforementioned threshold.
         *
         * @param buffer the {@link Buffer} to write.
         * @param last   flag indicating if this is the last buffer to send.
         * @throws IOException                        if an I/O error occurs.
         * @throws java.lang.IllegalArgumentException if <code>buffer</code>
         *                                            is <code>null</code>.
         * @throws java.lang.IllegalStateException    if this method is invoked
         *                                            before asynchronous transferring has been initiated.
         * @see #setMaxPendingBytes(int)
         */
        @SuppressWarnings("UnusedDeclaration")
        void feed(final Buffer buffer, final boolean last) throws IOException;

    } // END Feeder


    /**
     * Base class for {@link Feeder} implementations.  This class provides
     * an implementation for the contract defined by the {@link #feed} method.
     */
    public static abstract class BaseFeeder implements Feeder {

        protected final FeedableBodyGenerator feedableBodyGenerator;


        // -------------------------------------------------------- Constructors


        protected BaseFeeder(FeedableBodyGenerator feedableBodyGenerator) {
            this.feedableBodyGenerator = feedableBodyGenerator;
        }


        // --------------------------------------------- Package Private Methods


        /**
         * {@inheritDoc}
         */
        @SuppressWarnings("UnusedDeclaration")
        public final synchronized void feed(final Buffer buffer, final boolean last)
                throws IOException {
            if (buffer == null) {
                throw new IllegalArgumentException(
                        "Buffer argument cannot be null.");
            }
            if (!feedableBodyGenerator.asyncTransferInitiated) {
                throw new IllegalStateException(
                        "Asynchronous transfer has not been initiated.");
            }
            blockUntilQueueFree(feedableBodyGenerator.context.getConnection());
            final HttpContent content =
                    feedableBodyGenerator.contentBuilder
                            .content(buffer)
                            .last(last)
                            .build();
            final CompletionHandler<WriteResult> handler =
                    ((last) ? new LastPacketCompletionHandler() : null);
            feedableBodyGenerator.context.write(content, handler);
        }

        /**
         * This method will block if the async write queue is currently larger
         * than the configured maximum.  The amount of time that this method
         * will block is dependent on the write timeout of the transport
         * associated with the specified connection.
         */
        private static void blockUntilQueueFree(final Connection c) {
            if (!c.canWrite()) {
                final FutureImpl<Boolean> future =
                        Futures.createSafeFuture();
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

        private static void block(final Connection c,
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
                HttpTransactionContext ctx = HttpTransactionContext.get(c);
                ctx.abort(e.getCause());
            } catch (Exception e) {
                HttpTransactionContext ctx = HttpTransactionContext.get(c);
                ctx.abort(e);
            }
        }


        // ------------------------------------------------------- Inner Classes


        private final class LastPacketCompletionHandler
                implements CompletionHandler<WriteResult> {

            private final CompletionHandler<WriteResult> delegate;
            private final Connection c;
            private final int origMaxPendingBytes;

            // -------------------------------------------------------- Constructors


            @SuppressWarnings("unchecked")
            private LastPacketCompletionHandler() {
                delegate = ((!feedableBodyGenerator.requestPacket.isCommitted())
                        ? feedableBodyGenerator.context
                        .getTransportContext()
                        .getCompletionHandler()
                        : null);
                c = feedableBodyGenerator.context.getConnection();
                origMaxPendingBytes = feedableBodyGenerator.origMaxPendingBytes;
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

    } // END Feeder


    /**
     * Implementations of this class provide the framework to read data from
     * some source and feed data to the {@link FeedableBodyGenerator}
     * without blocking.
     */
    @SuppressWarnings("UnusedDeclaration")
    public static abstract class NonBlockingFeeder extends BaseFeeder {


        // -------------------------------------------------------- Constructors


        /**
         * Constructs the <code>NonBlockingFeeder</code> with the associated
         * {@link FeedableBodyGenerator}.
         */
        public NonBlockingFeeder(final FeedableBodyGenerator feedableBodyGenerator) {
            super(feedableBodyGenerator);
        }


        // ------------------------------------------------------ Public Methods


        /**
         * Notification that it's possible to send another block of data via
         * {@link #feed(org.glassfish.grizzly.Buffer, boolean)}.
         * <p/>
         * It's important to only invoke {@link #feed(Buffer, boolean)}
         * once per invocation of {@link #canFeed()}.
         */
        public abstract void canFeed();

        /**
         * @return <code>true</code> if all data has been fed by this feeder,
         * otherwise returns <code>false</code>.
         */
        public abstract boolean isDone();

        /**
         * @return <code>true</code> if data is available to be fed, otherwise
         * returns <code>false</code>.  When this method returns <code>false</code>,
         * the {@link FeedableBodyGenerator} will call {@link #notifyReadyToFeed(ReadyToFeedListener)}
         * by which this {@link NonBlockingFeeder} implementation may signal data is once
         * again available to be fed.
         */
        public abstract boolean isReady();

        /**
         * Callback registration to signal the {@link FeedableBodyGenerator} that
         * data is available once again to continue feeding.  Once this listener
         * has been invoked, the NonBlockingFeeder implementation should no longer maintain
         * a reference to the listener.
         */
        public abstract void notifyReadyToFeed(final ReadyToFeedListener listener);


        // ------------------------------------------------- Methods from Feeder


        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void flush() {
            final Connection c = feedableBodyGenerator.context.getConnection();
            if (isReady()) {
                writeUntilFullOrDone(c);
                if (!isDone()) {
                    if (!isReady()) {
                        notifyReadyToFeed(new ReadyToFeedListenerImpl());
                    }
                    if (!c.canWrite()) {
                        // write queue is full, leverage WriteListener to let us know
                        // when it is safe to write again.
                        c.notifyCanWrite(new WriteHandlerImpl());
                    }
                }
            } else {
                notifyReadyToFeed(new ReadyToFeedListenerImpl());
            }
        }


        // ----------------------------------------------------- Private Methods


        private void writeUntilFullOrDone(final Connection c) {
            while (c.canWrite()) {
                if (isReady()) {
                    canFeed();
                }
                if (!isReady()) {
                    break;
                }
            }
        }


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


        private final class WriteHandlerImpl implements WriteHandler {


            private final Connection c;


            // -------------------------------------------------------- Constructors


            private WriteHandlerImpl() {
                this.c = feedableBodyGenerator.context.getConnection();
            }


            // ------------------------------------------ Methods from WriteListener

            @Override
            public void onWritePossible() throws Exception {
                writeUntilFullOrDone(c);
                if (!isDone()) {
                    if (!isReady()) {
                        notifyReadyToFeed(new ReadyToFeedListenerImpl());
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
                c.setMaxAsyncWriteQueueSize(
                        feedableBodyGenerator.origMaxPendingBytes);
                HttpTransactionContext ctx = HttpTransactionContext.get(c);
                ctx.abort(t);
            }

        } // END WriteHandlerImpl


        private final class ReadyToFeedListenerImpl
                implements NonBlockingFeeder.ReadyToFeedListener {


            // ------------------------------------ Methods from ReadyToFeedListener


            @Override
            public void ready() {
                flush();
            }

        } // END ReadToFeedListenerImpl

    } // END NonBlockingFeeder


    /**
     * This simple {@link Feeder} implementation allows the implementation to
     * feed data in whatever fashion is deemed appropriate.
     */
    @SuppressWarnings("UnusedDeclaration")
    public abstract static class SimpleFeeder extends BaseFeeder {


        // -------------------------------------------------------- Constructors


        /**
         * Constructs the <code>SimpleFeeder</code> with the associated
         * {@link FeedableBodyGenerator}.
         */
        public SimpleFeeder(FeedableBodyGenerator feedableBodyGenerator) {
            super(feedableBodyGenerator);
        }


    } // END SimpleFeeder
}
