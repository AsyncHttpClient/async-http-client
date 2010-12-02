/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package com.ning.http.client.providers.jdk;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.FutureImpl;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


public class JDKFuture<V> implements FutureImpl<V> {

    protected Future<V> innerFuture;
    protected final AsyncHandler<V> asyncHandler;
    protected final int responseTimeoutInMs;
    protected final AtomicBoolean cancelled = new AtomicBoolean(false);
    protected final AtomicBoolean timedOut = new AtomicBoolean(false);
    protected final AtomicBoolean isDone = new AtomicBoolean(false);
    protected final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();
    protected final AtomicLong touch = new AtomicLong(System.currentTimeMillis());
    protected final AtomicBoolean contentProcessed = new AtomicBoolean(false);
    private boolean writeHeaders;
    private boolean writeBody;

    public JDKFuture(AsyncHandler<V> asyncHandler, int responseTimeoutInMs) {
        this.asyncHandler = asyncHandler;
        this.responseTimeoutInMs = responseTimeoutInMs;
        writeHeaders = true;
        writeBody = true;
    }

    protected void setInnerFuture(Future<V> innerFuture) {
        this.innerFuture = innerFuture;
    }

    public void done(Callable callable) {
        isDone.set(true);
    }

    public void abort(Throwable t) {
        if (innerFuture != null) {
            innerFuture.cancel(true);
        }
        exception.set(t);
        if (!timedOut.get() && !cancelled.get()) {
            asyncHandler.onThrowable(t);
        }     
    }

    public void content(V v) {
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        cancelled.set(true);
        if (innerFuture != null) {
            return innerFuture.cancel(mayInterruptIfRunning);
        } else {
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
                return get(timeout,unit);
            }
            timedOut.set(true);
            throw new TimeoutException(String.format("No response received after %s", responseTimeoutInMs));
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
    public boolean hasExpired(){
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
