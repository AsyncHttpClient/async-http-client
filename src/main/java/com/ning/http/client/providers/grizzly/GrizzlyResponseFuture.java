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

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import com.ning.http.client.listenable.AbstractListenableFuture;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.impl.FutureImpl;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AbstractListenableFuture} implementation adaptation of Grizzly's
 * {@link FutureImpl}.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponseFuture<V> extends AbstractListenableFuture<V> {

    private final AtomicBoolean done = new AtomicBoolean(false);
    private final AsyncHandler handler;
    private final GrizzlyAsyncHttpProvider provider;
    private final Request request;

    private Connection connection;

    FutureImpl<V> delegate;


    // ------------------------------------------------------------ Constructors


    GrizzlyResponseFuture(final GrizzlyAsyncHttpProvider provider,
                          final Request request,
                          final AsyncHandler handler) {

        this.provider = provider;
        this.request = request;
        this.handler = handler;

    }


    // ----------------------------------- Methods from AbstractListenableFuture


    public void done(Callable callable) {

        done.compareAndSet(false, true);
        super.done();

    }


    public void abort(Throwable t) {

        delegate.failure(t);
        if (handler != null) {
            handler.onThrowable(t);
        }
        closeConnection();
        done();

    }


    public void content(V v) {

        delegate.result(v);

    }


    public void touch() {

        provider.touchConnection(connection, request);

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

        handler.onThrowable(new CancellationException());
        done();
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


    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {

        if (!delegate.isCancelled() || !delegate.isDone()) {
            return delegate.get(timeout, unit);
        } else {
            return null;
        }

    }


    // ------------------------------------------------- Package Private Methods


    void setConnection(final Connection connection) {

        this.connection = connection;

    }


    void setDelegate(final FutureImpl<V> delegate) {

        this.delegate = delegate;

    }


    // --------------------------------------------------------- Private Methods


    private void closeConnection() {

        if (connection != null && !connection.isOpen()) {
            connection.close().markForRecycle(true);
        }

    }

}
