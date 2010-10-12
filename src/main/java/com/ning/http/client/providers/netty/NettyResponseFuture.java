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
package com.ning.http.client.providers.netty;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.FutureImpl;
import com.ning.http.client.Request;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request has been fully processed.
 *
 * @param <V>
 */
public final class NettyResponseFuture<V> implements FutureImpl<V> {

    enum STATE {
        NEW,
        POOLED,
        RECONNECTED,
        CLOSED,
    }

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AsyncHandler<V> asyncHandler;
    private final int responseTimeoutInMs;
    private final Request request;
    private HttpRequest nettyRequest;
    private final AtomicReference<V> content = new AtomicReference<V>();
    private URI uri;
    private boolean keepAlive = true;
    private HttpResponse httpResponse;
    private final AtomicReference<ExecutionException> exEx = new AtomicReference<ExecutionException>();
    private final AtomicInteger redirectCount = new AtomicInteger();
    private Future<?> reaperFuture;
    private final AtomicBoolean inAuth = new AtomicBoolean(false);
    private final AtomicBoolean statusReceived = new AtomicBoolean(false);
    private final AtomicLong touch = new AtomicLong(System.currentTimeMillis());
    private final NettyAsyncHttpProvider asyncHttpProvider;
    private final AtomicReference<STATE> state = new AtomicReference<STATE>(STATE.NEW);
    private final AtomicBoolean contentProcessed = new AtomicBoolean(false);

    public NettyResponseFuture(URI uri,
                               Request request,
                               AsyncHandler<V> asyncHandler,
                               HttpRequest nettyRequest,
                               int responseTimeoutInMs,
                               NettyAsyncHttpProvider asyncHttpProvider) {

        this.asyncHandler = asyncHandler;
        this.responseTimeoutInMs = responseTimeoutInMs;
        this.request = request;
        this.nettyRequest = nettyRequest;
        this.uri = uri;
        this.asyncHttpProvider = asyncHttpProvider;
    }

    public URI getURI() throws MalformedURLException {
        return uri;
    }

    public void setURI(URI uri){
        this.uri = uri;
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
        if (reaperFuture != null) reaperFuture.cancel(true);
        return true;
    }

    /**
     * Is the Future still valid
     *
     * @return <code>true</code> iff response has expired and should be terminated.
     */
    public boolean hasExpired(){
        return responseTimeoutInMs != -1 && ((System.currentTimeMillis() - touch.get()) > responseTimeoutInMs);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public V get() throws InterruptedException, ExecutionException{
        try {
            return get(responseTimeoutInMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public V get(long l, TimeUnit tu) throws InterruptedException, TimeoutException, ExecutionException {
        if (!isDone() && !isCancelled()) {
            boolean failed = false;
            if (l == -1) {
                latch.await();
            } else {
                failed = !latch.await(l, tu);
            }
            if (failed) {
                isCancelled.set(true);
                TimeoutException te = new TimeoutException("No response received");
                try {
                    asyncHandler.onThrowable(te);
                } finally {
                    throw te;
                }
            }
            isDone.set(true);

            ExecutionException e = exEx.getAndSet(null);
            if (e != null){
                throw e;
            }
        }
        return getContent();
    }

    V getContent() {
        V update = content.get();
        if (!contentProcessed.getAndSet(true)) {
            try {
                update = asyncHandler.onCompleted();
            } catch (Throwable ex) {
                try {
                    asyncHandler.onThrowable(ex);
                } finally {
                    throw new RuntimeException(ex);
                }
            }
            content.compareAndSet(null, update);
        }
        return update;
    }

    public final void done(Callable callable) {
        try {
            if (exEx.get() != null){
                return;
            }
            if (reaperFuture != null) reaperFuture.cancel(true);
            isDone.set(true);
            getContent();
            if (callable != null) {
                try {
                    callable.call();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        } finally {
            latch.countDown();
        }
    }

    public final void abort(final Throwable t) {
        if (reaperFuture != null) reaperFuture.cancel(true);

        if (isDone.get() || isCancelled.get()) return;

        exEx.compareAndSet(null, new ExecutionException(t));
        try {
            asyncHandler.onThrowable(t);
        } finally {
            isDone.set(true);
            latch.countDown();
        }
    }

    public final Request getRequest() {
        return request;
    }

    public final HttpRequest getNettyRequest() {
        return nettyRequest;
    }

    protected final void setNettyRequest(HttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
    }

    public final AsyncHandler<V> getAsyncHandler() {
        return asyncHandler;
    }

    public final boolean getKeepAlive() {
        return keepAlive;
    }

    public final void setKeepAlive(final boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public final HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public final void setHttpResponse(final HttpResponse httpResponse) {
        this.httpResponse = httpResponse;
    }

    public int incrementAndGetCurrentRedirectCount(){
        return redirectCount.incrementAndGet();
    }

    public void setReaperFuture(Future<?> reaperFuture) {
        this.reaperFuture = reaperFuture;
    }

    public boolean isInAuth() {
        return inAuth.get();
    }

    public boolean getAndSetAuth(boolean inDigestAuth) {
        return inAuth.getAndSet(inDigestAuth);
    }

    public STATE getState() {
        return state.get();
    }

    public void setState(STATE state) {
        this.state.set(state);
    }

    public boolean getAndSetStatusReceived(boolean sr) {
        return statusReceived.getAndSet(sr);
    }

    protected void touch() {
        touch.set(System.currentTimeMillis());
    }

    protected NettyAsyncHttpProvider provider() {
        return asyncHttpProvider;
    }

    @Override
    public String toString() {
        return "NettyResponseFuture{" +
                "latch=" + latch +
                ", isDone=" + isDone +
                ", isCancelled=" + isCancelled +
                ", asyncHandler=" + asyncHandler +
                ", responseTimeoutInMs=" + responseTimeoutInMs +
                ", request=" + request +
                ", nettyRequest=" + nettyRequest +
                ", content=" + content +
                ", uri=" + uri +
                ", keepAlive=" + keepAlive +
                ", httpResponse=" + httpResponse +
                ", exEx=" + exEx +
                ", redirectCount=" + redirectCount +
                ", reaperFuture=" + reaperFuture +
                ", inAuth=" + inAuth +
                ", statusReceived=" + statusReceived +
                ", touch=" + touch +
                '}';
    }

}
