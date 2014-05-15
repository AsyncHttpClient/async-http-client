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

import static com.ning.http.util.DateUtil.millisTime;

import java.net.SocketAddress;
import java.net.URI;
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

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.ConnectionPoolKeyStrategy;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.listenable.AbstractListenableFuture;
import com.ning.http.client.providers.netty.timeout.TimeoutsHolder;

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request has been fully processed.
 * 
 * @param <V>
 */
public final class NettyResponseFuture<V> extends AbstractListenableFuture<V> {

    private final static Logger logger = LoggerFactory.getLogger(NettyResponseFuture.class);
    public final static String MAX_RETRY = "com.ning.http.client.providers.netty.maxRetry";

    enum STATE {
        NEW, POOLED, RECONNECTED, CLOSED,
    }

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private AsyncHandler<V> asyncHandler;
    private final int requestTimeoutInMs;
    private final int idleConnectionTimeoutInMs;
    private Request request;
    private HttpRequest nettyRequest;
    private final AtomicReference<V> content = new AtomicReference<V>();
    private URI uri;
    private boolean keepAlive = true;
    private HttpResponse httpResponse;
    private final AtomicReference<ExecutionException> exEx = new AtomicReference<ExecutionException>();
    private final AtomicInteger redirectCount = new AtomicInteger();
    private volatile boolean requestTimeoutReached;
    private volatile boolean idleConnectionTimeoutReached;
    private volatile TimeoutsHolder timeoutsHolder;
    private final AtomicBoolean inAuth = new AtomicBoolean(false);
    private final AtomicBoolean statusReceived = new AtomicBoolean(false);
    private final AtomicLong touch = new AtomicLong(millisTime());
    private final long start = millisTime();
    private final NettyAsyncHttpProvider asyncHttpProvider;
    private final AtomicReference<STATE> state = new AtomicReference<STATE>(STATE.NEW);
    private final AtomicBoolean contentProcessed = new AtomicBoolean(false);
    private Channel channel;
    private boolean reuseChannel = false;
    private final AtomicInteger currentRetry = new AtomicInteger(0);
    private final int maxRetry;
    private boolean writeHeaders;
    private boolean writeBody;
    private final AtomicBoolean onThrowableCalled = new AtomicBoolean(false);
    private boolean allowConnect = false;
    private final ConnectionPoolKeyStrategy connectionPoolKeyStrategy;
    private final ProxyServer proxyServer;

    public NettyResponseFuture(URI uri,//
            Request request,//
            AsyncHandler<V> asyncHandler,//
            HttpRequest nettyRequest,//
            int requestTimeoutInMs,//
            int idleConnectionTimeoutInMs,//
            NettyAsyncHttpProvider asyncHttpProvider,//
            ConnectionPoolKeyStrategy connectionPoolKeyStrategy,//
            ProxyServer proxyServer) {

        this.asyncHandler = asyncHandler;
        this.requestTimeoutInMs = requestTimeoutInMs;
        this.idleConnectionTimeoutInMs = idleConnectionTimeoutInMs;
        this.request = request;
        this.nettyRequest = nettyRequest;
        this.uri = uri;
        this.asyncHttpProvider = asyncHttpProvider;
        this.connectionPoolKeyStrategy = connectionPoolKeyStrategy;
        this.proxyServer = proxyServer;

        if (System.getProperty(MAX_RETRY) != null) {
            maxRetry = Integer.valueOf(System.getProperty(MAX_RETRY));
        } else {
            maxRetry = asyncHttpProvider.getConfig().getMaxRequestRetry();
        }
        writeHeaders = true;
        writeBody = true;
    }

    protected URI getURI() {
        return uri;
    }

    protected void setURI(URI uri) {
        this.uri = uri;
    }

    public ConnectionPoolKeyStrategy getConnectionPoolKeyStrategy() {
        return connectionPoolKeyStrategy;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean isDone() {
        return isDone.get() || isCancelled.get();
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
        cancelTimeouts();

        if (isCancelled.getAndSet(true))
            return false;

        try {
            channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());
            channel.close();
        } catch (Throwable t) {
            // Ignore
        }
        if (!onThrowableCalled.getAndSet(true)) {
            try {
                asyncHandler.onThrowable(new CancellationException());
            } catch (Throwable t) {
                logger.warn("cancel", t);
            }
        }
        latch.countDown();
        runListeners();
        return true;
    }

    /**
     * Is the Future still valid
     * 
     * @return <code>true</code> if response has expired and should be terminated.
     */
    public boolean hasExpired() {
        return requestTimeoutReached || idleConnectionTimeoutReached;
    }

    public void setRequestTimeoutReached() {
        this.requestTimeoutReached = true;
    }

    public boolean isRequestTimeoutReached() {
        return requestTimeoutReached;
    }

    public void setIdleConnectionTimeoutReached() {
        this.idleConnectionTimeoutReached = true;
    }

    public boolean isIdleConnectionTimeoutReached() {
        return idleConnectionTimeoutReached;
    }

    public void setTimeoutsHolder(TimeoutsHolder timeoutsHolder) {
        this.timeoutsHolder = timeoutsHolder;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public V get() throws InterruptedException, ExecutionException {
        try {
            return get(requestTimeoutInMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelTimeouts();
            throw new ExecutionException(e);
        }
    }

    public void cancelTimeouts() {
        if (timeoutsHolder != null) {
            timeoutsHolder.cancel();
            timeoutsHolder = null;
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
                try {
                    channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(new NettyAsyncHttpProvider.DiscardEvent());
                    channel.close();
                } catch (Throwable t) {
                    // Ignore
                }
                if (!onThrowableCalled.getAndSet(true)) {
                    try {
                        TimeoutException te = new TimeoutException(String.format("No response received after %s", l));
                        try {
                            asyncHandler.onThrowable(te);
                        } catch (Throwable t) {
                            logger.debug("asyncHandler.onThrowable", t);
                        }
                        throw new ExecutionException(te);
                    } finally {
                        cancelTimeouts();
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
                if (!onThrowableCalled.getAndSet(true)) {
                    try {
                        try {
                            asyncHandler.onThrowable(ex);
                        } catch (Throwable t) {
                            logger.debug("asyncHandler.onThrowable", t);
                        }
                        throw new RuntimeException(ex);
                    } finally {
                        cancelTimeouts();
                    }
                }
            }
            content.compareAndSet(null, update);
        }
        return update;
    }

    public final void done() {
        cancelTimeouts();

        try {
            if (exEx.get() != null) {
                return;
            }
            getContent();
            isDone.set(true);
        } catch (ExecutionException t) {
            return;
        } catch (RuntimeException t) {
        	Throwable exception = t.getCause() != null ? t.getCause() : t;
        	exEx.compareAndSet(null, new ExecutionException(exception));

        } finally {
            latch.countDown();
        }

        runListeners();
    }

    public final void abort(final Throwable t) {
        cancelTimeouts();

        if (isDone.get() || isCancelled.getAndSet(true))
            return;

        isCancelled.set(true);
        exEx.compareAndSet(null, new ExecutionException(t));
        if (onThrowableCalled.compareAndSet(false, true)) {
            try {
                asyncHandler.onThrowable(t);
            } catch (Throwable te) {
                logger.debug("asyncHandler.onThrowable", te);
            }
        }
        latch.countDown();
        runListeners();
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
    public void touch() {
        touch.set(millisTime());
    }

    public long getLastTouch() {
        return touch.get();
    }

    /**
     * {@inheritDoc}
     */
    public boolean getAndSetWriteHeaders(boolean writeHeaders) {
        boolean b = this.writeHeaders;
        this.writeHeaders = writeHeaders;
        return b;
    }

    /**
     * {@inheritDoc}
     */
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

    public SocketAddress getChannelRemoteAddress() {
        return channel() != null? channel().getRemoteAddress(): null;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * Return true if the {@link Future} cannot be recovered. There is some scenario where a connection can be closed by an unexpected IOException, and in some situation we can recover from that exception.
     * 
     * @return true if that {@link Future} cannot be recovered.
     */
    public boolean cannotBeReplay() {
        return isDone() || !canRetry() || isCancelled() || (channel() != null && channel().isOpen() && uri.getScheme().compareToIgnoreCase("https") != 0) || isInAuth();
    }

    public long getStart() {
        return start;
    }

    public long getRequestTimeoutInMs() {
        return requestTimeoutInMs;
    }

    public long getIdleConnectionTimeoutInMs() {
        return idleConnectionTimeoutInMs;
    }

    @Override
    public String toString() {
        return "NettyResponseFuture{" + //
                "currentRetry=" + currentRetry + //
                ",\n\tisDone=" + isDone + //
                ",\n\tisCancelled=" + isCancelled + //
                ",\n\tasyncHandler=" + asyncHandler + //
                ",\n\trequestTimeoutInMs=" + requestTimeoutInMs + //
                ",\n\tnettyRequest=" + nettyRequest + //
                ",\n\tcontent=" + content + //
                ",\n\turi=" + uri + //
                ",\n\tkeepAlive=" + keepAlive + //
                ",\n\thttpResponse=" + httpResponse + //
                ",\n\texEx=" + exEx + //
                ",\n\tredirectCount=" + redirectCount + //
                ",\n\ttimeoutsHolder=" + timeoutsHolder + //
                ",\n\tinAuth=" + inAuth + //
                ",\n\tstatusReceived=" + statusReceived + //
                ",\n\ttouch=" + touch + //
                '}';
    }

}
