/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
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

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.listenable.AbstractListenableFuture;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.utils.Futures;

/**
 * {@link AbstractListenableFuture} implementation adaptation of Grizzly's
 * {@link FutureImpl}.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
final class GrizzlyResponseFuture<V> extends AbstractListenableFuture<V>
        implements CompletionHandler<V> {

    private final FutureImpl<V> delegate;
//    private final GrizzlyAsyncHttpProvider provider;
//    private Request request;
//    private Connection connection;
    private AsyncHandler asyncHandler;
    
    // transaction context. Not null if connection is established
    private volatile HttpTransactionContext transactionCtx;


    // ------------------------------------------------------------ Constructors


    GrizzlyResponseFuture(final AsyncHandler asyncHandler) {
        this.asyncHandler = asyncHandler;
        
        delegate = Futures.<V>createSafeFuture();
        delegate.addCompletionHandler(this);
    }


    // ----------------------------------- Methods from AbstractListenableFuture


    public void done() {
        done(null);
    }

    public void done(V result) {
        delegate.result(result);
    }

    public void abort(Throwable t) {

        delegate.failure(t);

    }

    public void touch() {
        final HttpTransactionContext tx = transactionCtx;
        if (tx != null) {
            tx.touchConnection();
        }

    }


    public boolean getAndSetWriteHeaders(boolean writeHeaders) {

        // TODO This doesn't currently do anything - and may not make sense
        // with our implementation.  Needs further analysis.
        return writeHeaders;

    }


    public boolean getAndSetWriteBody(boolean writeBody) {

        // TODO This doesn't currently do anything - and may not make sense
        // with our implementation.  Needs further analysis.
        return writeBody;

    }


    // ----------------------------------------------------- Methods from Future


    public boolean cancel(boolean mayInterruptIfRunning) {
        return delegate.cancel(mayInterruptIfRunning);
    }


    public boolean isCancelled() {

        return delegate.isCancelled();

    }


    public boolean isDone() {

        return delegate.isDone();

    }


    public V get() throws InterruptedException, ExecutionException {

        return delegate.get();

    }


    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {

        return delegate.get(timeout, unit);

    }

    // ----------------------------------------------------- Methods from CompletionHandler

    @Override
    public void cancelled() {
        final AsyncHandler ah = asyncHandler;
        if (ah != null) {
            try {
                ah.onThrowable(new CancellationException());
            } catch (Throwable ignore) {
            }
        }
        
        runListeners();
    }

    @Override
    public void failed(final Throwable t) {
        final AsyncHandler ah = asyncHandler;
        if (ah != null) {
            try {
                ah.onThrowable(t);
            } catch (Throwable ignore) {
            }
        }
            
        final HttpTransactionContext tx = transactionCtx;
        if (tx != null) {
            tx.closeConnection();
        }

        runListeners();
    }

    @Override
    public void completed(V result) {
        runListeners();
    }

    @Override
    public void updated(V result) {
    }        

    // ------------------------------------------------- Package Private Methods

    AsyncHandler getAsyncHandler() {
        return asyncHandler;
    }

    void setAsyncHandler(final AsyncHandler asyncHandler) {
        this.asyncHandler = asyncHandler;
    }

    /**
     * @return {@link HttpTransactionContext}, or <tt>null</tt> if connection is
     *          not established
     */
    HttpTransactionContext getHttpTransactionCtx() {
        return transactionCtx;
    }

    /**
     * @param transactionCtx
     * @return <tt>true</tt> if we can continue request/response processing,
     *          or <tt>false</tt> if future has been aborted
     */
    boolean setHttpTransactionCtx(
            final HttpTransactionContext transactionCtx) {
        this.transactionCtx = transactionCtx;
        return !delegate.isDone();
    }
}
