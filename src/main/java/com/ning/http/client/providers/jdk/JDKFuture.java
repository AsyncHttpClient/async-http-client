/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.providers.jdk;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.listenable.AbstractListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


public class JDKFuture<V> extends AbstractListenableFuture<V> {

    private final static Logger logger = LoggerFactory.getLogger(JDKFuture.class);

    protected Future<V> innerFuture;
    protected final AsyncHandler<V> asyncHandler;
    protected final int responseTimeoutInMs;
    protected final AtomicBoolean cancelled = new AtomicBoolean(false);
    protected final AtomicBoolean timedOut = new AtomicBoolean(false);
    protected final AtomicBoolean isDone = new AtomicBoolean(false);
    protected final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();
    protected final AtomicLong touch = new AtomicLong(System.currentTimeMillis());
    protected final AtomicBoolean contentProcessed = new AtomicBoolean(false);
    protected final HttpURLConnection urlConnection;
    private boolean writeHeaders;
    private boolean writeBody;

    public JDKFuture(AsyncHandler<V> asyncHandler, int responseTimeoutInMs, HttpURLConnection urlConnection) {
        this.asyncHandler = asyncHandler;
        this.responseTimeoutInMs = responseTimeoutInMs;
        this.urlConnection = urlConnection;
        writeHeaders = true;
        writeBody = true;
    }

    protected void setInnerFuture(Future<V> innerFuture) {
        this.innerFuture = innerFuture;
    }

    public void done(Callable callable) {
        isDone.set(true);
        super.done();
    }

    public void abort(Throwable t) {
        exception.set(t);
        if (innerFuture != null) {
            innerFuture.cancel(true);
        }
        if (!timedOut.get() && !cancelled.get()) {
            try {
                asyncHandler.onThrowable(t);
            } catch (Throwable te) {
                logger.debug("asyncHandler.onThrowable", te);
            }
        }
        super.done();
    }

    public void content(V v) {
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!cancelled.get() && innerFuture != null) {
            urlConnection.disconnect();
            try {
                asyncHandler.onThrowable(new CancellationException());
            } catch (Throwable te) {
                logger.debug("asyncHandler.onThrowable", te);
            }
            cancelled.set(true);
            super.done();
            return innerFuture.cancel(mayInterruptIfRunning);
        } else {
            super.done();
            return false;
        }
    }

    public boolean isCancelled() {
        if (innerFuture != null) {
            return innerFuture.isCancelled();
        } else {
            return false;
        }
    }

    public boolean isDone() {
        contentProcessed.set(true);
        return innerFuture.isDone();
    }

    public V get() throws InterruptedException, ExecutionException {
        try {
            return get(responseTimeoutInMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ExecutionException(e);
        }
    }

    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        V content = null;
        try {
            if (innerFuture != null) {
                content = innerFuture.get(timeout, unit);
            }
        } catch (TimeoutException t) {
            if (!contentProcessed.get() && timeout != -1 && ((System.currentTimeMillis() - touch.get()) <= responseTimeoutInMs)) {
                return get(timeout, unit);
            }

            if (exception.get() == null) {
                timedOut.set(true);
                throw new ExecutionException(new TimeoutException(String.format("No response received after %s", responseTimeoutInMs)));
            }
        } catch (CancellationException ce) {
        }

        if (exception.get() != null) {
            throw new ExecutionException(exception.get());
        }
        return content;
    }

    /**
     * Is the Future still valid
     *
     * @return <code>true</code> if response has expired and should be terminated.
     */
    public boolean hasExpired() {
        return responseTimeoutInMs != -1 && ((System.currentTimeMillis() - touch.get()) > responseTimeoutInMs);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override  */
    public void touch() {
        touch.set(System.currentTimeMillis());
    }

    /**
     * {@inheritDoc}
     */
    /* @Override  */
    public boolean getAndSetWriteHeaders(boolean writeHeaders) {
        boolean b = this.writeHeaders;
        this.writeHeaders = writeHeaders;
        return b;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean getAndSetWriteBody(boolean writeBody) {
        boolean b = this.writeBody;
        this.writeBody = writeBody;
        return b;
    }
}
