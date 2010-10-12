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
import java.util.concurrent.atomic.AtomicReference;


public class JDKFuture<V> implements FutureImpl<V> {

    private Future<V> innerFuture;
    private final AsyncHandler<V> asyncHandler;
    private final int responseTimeoutInMs;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean timedOut = new AtomicBoolean(false);
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicReference<Throwable> exception = new AtomicReference<Throwable>();

    public JDKFuture(AsyncHandler<V> asyncHandler, int responseTimeoutInMs) {
        this.asyncHandler = asyncHandler;
        this.responseTimeoutInMs = responseTimeoutInMs;
    }

    protected void setInnerFuture(Future<V> innerFuture) {
        this.innerFuture = innerFuture;
    }

    public void done(Callable callable) {
        isDone.set(true);
    }

    public void abort(Throwable t) {
        exception.set(t);
        if (!timedOut.get() && !cancelled.get()) {
            asyncHandler.onThrowable(t);
        }

        innerFuture.cancel(true);
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        cancelled.set(true);
        return innerFuture.cancel(mayInterruptIfRunning);
    }

    public boolean isCancelled() {
        return innerFuture.isCancelled();
    }

    public boolean isDone() {
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
            content = innerFuture.get(timeout, unit);
        } catch (TimeoutException t) {
            timedOut.set(true);
            throw new TimeoutException("Request timed out.");
        } catch (CancellationException ce) {
        }

        if (exception.get() != null) {
            throw new ExecutionException(exception.get());
        }
        return content;
    }
}
