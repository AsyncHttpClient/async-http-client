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
 */
package com.ning.http.client.providers;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.FutureImpl;
import com.ning.http.client.Request;
import com.ning.http.url.Url;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

import java.net.MalformedURLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request has been fully processed.
 *
 * @param <V>
 */
public final class NettyResponseFuture<V> implements FutureImpl<V> {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AsyncHandler<V> asyncHandler;
    private final int responseTimeoutInMs;
    private final Request request;
    private final HttpRequest nettyRequest;
    private final AtomicReference<V> content = new AtomicReference<V>();
    private final Url url;
    private boolean keepAlive = true;
    private HttpResponse httpResponse;
    
    public NettyResponseFuture(Url url,
                               Request request,
                               AsyncHandler<V> asyncHandler,
                               HttpRequest nettyRequest,
                               int responseTimeoutInMs) {

        this.asyncHandler = asyncHandler;
        this.responseTimeoutInMs = responseTimeoutInMs;
        this.request = request;
        this.nettyRequest = nettyRequest;
        this.url = url;
    }

    public Url getUrl() throws MalformedURLException {
        return url;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean isDone() {
        return isDone.get();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean isCancelled() {
        return isCancelled.get();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean cancel(boolean force) {
        latch.countDown();
        isCancelled.set(true);
        return true;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public V get() throws InterruptedException {
        try{
            if (!isDone() && !isCancelled()) {
                if (!latch.await(responseTimeoutInMs, TimeUnit.MILLISECONDS)) {
                    isCancelled.set(true);
                    TimeoutException te = new TimeoutException("No response received");
                    onThrowable(te);
                    throw te;
                }
                isDone.set(true);
            }
            return (V) getContent();
        } catch (TimeoutException ex) {
            /**
             * To prevent deadlock, we still throw an exception, but a Runtime one to fulfill the API contract.
             */
            throw new RuntimeException(ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public V get(long l, TimeUnit tu) throws InterruptedException, TimeoutException {
        if (!isDone() && !isCancelled()) {
            if (!latch.await(l, tu)) {
                isCancelled.set(true);
                TimeoutException te = new TimeoutException("No response received");
                onThrowable(te);
                throw te;
            }
        }

        return (V) getContent();
    }

    public void onThrowable(Throwable t) {
        asyncHandler.onThrowable(t);
    }

    V getContent() {
        if (content.get() == null) {
            try {
                content.set(asyncHandler.onCompleted());
            } catch (Throwable ex) {
                onThrowable(ex);
                throw new RuntimeException(ex);
            }
        }
        return content.get();
    }

    public void done() {
        isDone.set(true);
        getContent();
        latch.countDown();
    }

    public Request getRequest() {
        return request;
    }

    public HttpRequest getNettyRequest() {
        return nettyRequest;
    }

    public AsyncHandler<V> getAsyncHandler() {
        return asyncHandler;
    }

    public boolean getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(final boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public void setHttpResponse(final HttpResponse httpResponse) {
        this.httpResponse = httpResponse;
    }
}
