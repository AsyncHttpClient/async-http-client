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
import com.ning.http.client.Request;
import com.ning.http.client.listenable.AbstractListenableFuture;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
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
public final class NettyResponseFuture<V> extends AbstractListenableFuture<V> {

    private final static Logger logger = LoggerFactory.getLogger(NettyResponseFuture.class);
    public final static String MAX_RETRY = "com.ning.http.client.providers.netty.maxRetry";

    enum STATE {
        NEW,
        POOLED,
        RECONNECTED,
        CLOSED,
    }

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private AsyncHandler<V> asyncHandler;
    private final int responseTimeoutInMs;
    private final int idleConnectionTimeoutInMs;
    private Request request;
    private HttpRequest nettyRequest;
    private final AtomicReference<V> content = new AtomicReference<V>();
    private URI uri;
    private boolean keepAlive = true;
    private HttpResponse httpResponse;
    private final AtomicReference<ExecutionException> exEx = new AtomicReference<ExecutionException>();
    private final AtomicInteger redirectCount = new AtomicInteger();
    private volatile Future<?> reaperFuture;
    private final AtomicBoolean inAuth = new AtomicBoolean(false);
    private final AtomicBoolean statusReceived = new AtomicBoolean(false);
    private final AtomicLong touch = new AtomicLong(System.currentTimeMillis());
    private final long start = System.currentTimeMillis();
    private final NettyAsyncHttpProvider asyncHttpProvider;
    private final AtomicReference<STATE> state = new AtomicReference<STATE>(STATE.NEW);
    private final AtomicBoolean contentProcessed = new AtomicBoolean(false);
    private Channel channel;
    private boolean reuseChannel = false;
    private final AtomicInteger currentRetry = new AtomicInteger(0);
    private final int maxRetry;
    private boolean writeHeaders;
    private boolean writeBody;
    private final AtomicBoolean throwableCalled = new AtomicBoolean(false);
    private boolean allowConnect = false;

    public NettyResponseFuture(URI uri,
                               Request request,
                               AsyncHandler<V> asyncHandler,
                               HttpRequest nettyRequest,
                               int responseTimeoutInMs,
                               int idleConnectionTimeoutInMs,
                               NettyAsyncHttpProvider asyncHttpProvider) {

        this.asyncHandler = asyncHandler;
        this.responseTimeoutInMs = responseTimeoutInMs;
        this.idleConnectionTimeoutInMs = idleConnectionTimeoutInMs;
        this.request = request;
        this.nettyRequest = nettyRequest;
        this.uri = uri;
        this.asyncHttpProvider = asyncHttpProvider;

        if (System.getProperty(MAX_RETRY) != null) {
            maxRetry = Integer.valueOf(System.getProperty(MAX_RETRY));
        } else {
            maxRetry = asyncHttpProvider.getConfig().getMaxRequestRetry();
        }
        writeHeaders = true;
        writeBody = true;
    }

    protected URI getURI() throws MalformedURLException {
        return uri;
    }

    protected void setURI(URI uri) {
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

    void setAsyncHandler(AsyncHandler<V> asyncHandler) {
        this.asyncHandler = asyncHandler;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean cancel(boolean force) {
        cancelReaper();

        if (isCancelled.get()) return false;

        try {
            channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());
            channel.close();
        } catch (Throwable t) {
            // Ignore
        }
        if (!throwableCalled.getAndSet(true)) {
            try {
                asyncHandler.onThrowable(new CancellationException());
            } catch (Throwable t) {
                logger.warn("cancel", t);
            }
        }
        latch.countDown();
        isCancelled.set(true);
        super.done();
        return true;
    }

    /**
     * Is the Future still valid
     *
     * @return <code>true</code> if response has expired and should be terminated.
     */
    public boolean hasExpired() {
        long now = System.currentTimeMillis();
        return idleConnectionTimeoutInMs != -1 && ((now - touch.get()) >= idleConnectionTimeoutInMs)
                || responseTimeoutInMs != -1 && ((now - start) >= responseTimeoutInMs);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public V get() throws InterruptedException, ExecutionException {
        try {
            return get(responseTimeoutInMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelReaper();
            throw new ExecutionException(e);
        }
    }

    void cancelReaper() {
        if (reaperFuture != null) {
            reaperFuture.cancel(true);
        }
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public V get(long l, TimeUnit tu) throws InterruptedException, TimeoutException, ExecutionException {
        if (!isDone() && !isCancelled()) {
            boolean expired = false;
            if (l == -1) {
                latch.await();
            } else {
                expired = !latch.await(l, tu);
            }

            if (expired) {
                isCancelled.set(true);
                TimeoutException te = new TimeoutException(String.format("No response received after %s", l));
                if (!throwableCalled.getAndSet(true)) {
                    try {
                        asyncHandler.onThrowable(te);
                    } catch (Throwable t) {
                        logger.debug("asyncHandler.onThrowable", t);
                    } finally {
                        cancelReaper();
                        throw new ExecutionException(te);
                    }
                }
            }
            isDone.set(true);

            ExecutionException e = exEx.getAndSet(null);
            if (e != null) {
                throw e;
            }
        }
        return getContent();
    }

    V getContent() throws ExecutionException {
        ExecutionException e = exEx.getAndSet(null);
        if (e != null) {
            throw e;
        }

        V update = content.get();
        // No more retry
        currentRetry.set(maxRetry);
        if (exEx.get() == null && !contentProcessed.getAndSet(true)) {
            try {
                update = asyncHandler.onCompleted();
            } catch (Throwable ex) {
                if (!throwableCalled.getAndSet(true)) {
                    try {
                        asyncHandler.onThrowable(ex);
                    } catch (Throwable t) {
                        logger.debug("asyncHandler.onThrowable", t);
                    } finally {
                        cancelReaper();
                        throw new RuntimeException(ex);
                    }
                }
            }
            content.compareAndSet(null, update);
        }
        return update;
    }

    public final void done(Callable callable) {
        try {
            cancelReaper();

            if (exEx.get() != null) {
                return;
            }
            getContent();
            isDone.set(true);
            if (callable != null) {
                try {
                    callable.call();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        } catch (ExecutionException t) {
            return;
        } catch (RuntimeException t) {
            exEx.compareAndSet(null, new ExecutionException(t));
        } finally {
            latch.countDown();
        }
        super.done();
    }

    public final void abort(final Throwable t) {
        cancelReaper();

        if (isDone.get() || isCancelled.get()) return;

        exEx.compareAndSet(null, new ExecutionException(t));
        if (!throwableCalled.getAndSet(true)) {
            try {
                asyncHandler.onThrowable(t);
            } catch (Throwable te) {
                logger.debug("asyncHandler.onThrowable", te);
            } finally {
                isCancelled.set(true);
            }
        }
        latch.countDown();
        super.done();
    }

    public void content(V v) {
        content.set(v);
    }

    protected final Request getRequest() {
        return request;
    }

    public final HttpRequest getNettyRequest() {
        return nettyRequest;
    }

    protected final void setNettyRequest(HttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
    }

    protected final AsyncHandler<V> getAsyncHandler() {
        return asyncHandler;
    }

    protected final boolean getKeepAlive() {
        return keepAlive;
    }

    protected final void setKeepAlive(final boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    protected final HttpResponse getHttpResponse() {
        return httpResponse;
    }

    protected final void setHttpResponse(final HttpResponse httpResponse) {
        this.httpResponse = httpResponse;
    }

    protected int incrementAndGetCurrentRedirectCount() {
        return redirectCount.incrementAndGet();
    }

    protected void setReaperFuture(Future<?> reaperFuture) {
        cancelReaper();
        this.reaperFuture = reaperFuture;
    }

    protected boolean isInAuth() {
        return inAuth.get();
    }

    protected boolean getAndSetAuth(boolean inDigestAuth) {
        return inAuth.getAndSet(inDigestAuth);
    }

    protected STATE getState() {
        return state.get();
    }

    protected void setState(STATE state) {
        this.state.set(state);
    }

    public boolean getAndSetStatusReceived(boolean sr) {
        return statusReceived.getAndSet(sr);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void touch() {
        touch.set(System.currentTimeMillis());
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
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

    protected NettyAsyncHttpProvider provider() {
        return asyncHttpProvider;
    }

    protected void attachChannel(Channel channel) {
        this.channel = channel;
    }

    public void setReuseChannel(boolean reuseChannel) {
        this.reuseChannel = reuseChannel;
    }

    public boolean isConnectAllowed() {
        return allowConnect;
    }

    public void setConnectAllowed(boolean allowConnect) {
        this.allowConnect = allowConnect;
    }

    protected void attachChannel(Channel channel, boolean reuseChannel) {
        this.channel = channel;
        this.reuseChannel = reuseChannel;
    }

    protected Channel channel() {
        return channel;
    }

    protected boolean reuseChannel() {
        return reuseChannel;
    }

    protected boolean canRetry() {
        if (currentRetry.incrementAndGet() > maxRetry) {
            return false;
        }
        return true;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * Return true if the {@link Future} cannot be recovered. There is some scenario where a connection can be
     * closed by an unexpected IOException, and in some situation we can recover from that exception.
     *
     * @return true if that {@link Future} cannot be recovered.
     */
    public boolean cannotBeReplay() {
        return isDone()
                || !canRetry()
                || isCancelled()
                || (channel() != null && channel().isOpen() && uri.getScheme().compareToIgnoreCase("https") != 0)
                || isInAuth();
    }

    @Override
    public String toString() {
        return "NettyResponseFuture{" +
                "currentRetry=" + currentRetry +
                ",\n\tisDone=" + isDone +
                ",\n\tisCancelled=" + isCancelled +
                ",\n\tasyncHandler=" + asyncHandler +
                ",\n\tresponseTimeoutInMs=" + responseTimeoutInMs +
                ",\n\tnettyRequest=" + nettyRequest +
                ",\n\tcontent=" + content +
                ",\n\turi=" + uri +
                ",\n\tkeepAlive=" + keepAlive +
                ",\n\thttpResponse=" + httpResponse +
                ",\n\texEx=" + exEx +
                ",\n\tredirectCount=" + redirectCount +
                ",\n\treaperFuture=" + reaperFuture +
                ",\n\tinAuth=" + inAuth +
                ",\n\tstatusReceived=" + statusReceived +
                ",\n\ttouch=" + touch +
                '}';
    }

}
