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
import com.ning.http.client.ListenableFuture;

import java.net.HttpURLConnection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class JDKDelegateFuture<V> extends JDKFuture<V> {

    private final ListenableFuture<V> delegateFuture;

    public JDKDelegateFuture(AsyncHandler<V> asyncHandler, int responseTimeoutInMs, ListenableFuture<V> delegateFuture, HttpURLConnection urlConnection) {
        super(asyncHandler, responseTimeoutInMs, urlConnection);
        this.delegateFuture = delegateFuture;
    }

    public void done(Callable callable) {
        delegateFuture.done(callable);
        super.done(callable);
    }

    public void abort(Throwable t) {
        if (innerFuture != null) {
            innerFuture.cancel(true);
        }
        delegateFuture.abort(t);
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        delegateFuture.cancel(mayInterruptIfRunning);
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

    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        V content = null;
        try {
            if (innerFuture != null) {
                content = innerFuture.get(timeout, unit);
            }
        } catch (Throwable t) {
            if (!contentProcessed.get() && timeout != -1 && ((System.currentTimeMillis() - touch.get()) <= responseTimeoutInMs)) {
                return get(timeout, unit);
            }
            timedOut.set(true);
            delegateFuture.abort(t);
        }

        if (exception.get() != null) {
            delegateFuture.abort(new ExecutionException(exception.get()));
        }
        delegateFuture.content(content);
        delegateFuture.done(null);
        return content;
    }
}
