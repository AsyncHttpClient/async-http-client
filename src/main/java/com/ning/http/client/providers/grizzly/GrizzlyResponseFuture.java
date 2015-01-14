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
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.listenable.AbstractListenableFuture;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.impl.FutureImpl;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.glassfish.grizzly.CompletionHandler;
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

    private final AsyncHandler handler;
    private final GrizzlyAsyncHttpProvider provider;
    private final Request request;
    private final ProxyServer proxy;
    private Connection connection;

    private final FutureImpl<V> delegate;


    // ------------------------------------------------------------ Constructors


    GrizzlyResponseFuture(final GrizzlyAsyncHttpProvider provider,
                          final Request request,
                          final AsyncHandler handler,
                          final ProxyServer proxy) {

        this.provider = provider;
        this.request = request;
        this.handler = handler;
        this.proxy = proxy;
        
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

        return delegate.get(timeout, unit);

    }

    // ----------------------------------------------------- Methods from CompletionHandler

    @Override
    public void cancelled() {
        if (handler != null) {
            try {
                handler.onThrowable(new CancellationException());
            } catch (Throwable ignore) {
            }
        }
        
        runListeners();
    }

    @Override
    public void failed(final Throwable t) {
        if (handler != null) {
            try {
                handler.onThrowable(t);
            } catch (Throwable ignore) {
            }

        }
        closeConnection();
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


    void setConnection(final Connection connection) {

        this.connection = connection;

    }


    // --------------------------------------------------------- Private Methods


    private void closeConnection() {

        if (connection != null && connection.isOpen()) {
            connection.closeSilently();
        }

    }

    public ProxyServer getProxy() {
        return proxy;
    }
}
