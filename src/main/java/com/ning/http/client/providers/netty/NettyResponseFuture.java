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

import static com.ning.http.util.DateUtils.millisTime;

import java.net.SocketAddress;
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
import com.ning.http.client.uri.UriComponents;

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request has been fully processed.
 * 
 * @param <V>
 */
public final class NettyResponseFuture<V> extends AbstractListenableFuture<V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyResponseFuture.class);

    enum STATE {
        NEW, POOLED, RECONNECTED, CLOSED,
    }

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private AsyncHandler<V> asyncHandler;
    private Request request;
    private HttpRequest nettyRequest;
    private final AtomicReference<V> content = new AtomicReference<V>();
    private UriComponents uri;
    private boolean keepAlive = true;
    private HttpResponse httpResponse;
    private final AtomicReference<ExecutionException> exEx = new AtomicReference<ExecutionException>();
    private final AtomicInteger redirectCount = new AtomicInteger();
    private volatile TimeoutsHolder timeoutsHolder;
    private final AtomicBoolean inAuth = new AtomicBoolean(false);
    private final AtomicBoolean statusReceived = new AtomicBoolean(false);
    private final AtomicLong touch = new AtomicLong(millisTime());
    private final long start = millisTime();
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

    public NettyResponseFuture(UriComponents uri,//
            Request request,//
            AsyncHandler<V> asyncHandler,//
            HttpRequest nettyRequest,//
            int maxRetry,//
            ConnectionPoolKeyStrategy connectionPoolKeyStrategy,//
            ProxyServer proxyServer) {

        this.asyncHandler = asyncHandler;
        this.request = request;
        this.nettyRequest = nettyRequest;
        this.uri = uri;
        this.connectionPoolKeyStrategy = connectionPoolKeyStrategy;
        this.proxyServer = proxyServer;
        this.maxRetry = maxRetry;
        writeHeaders = true;
        writeBody = true;
    }

    /*********************************************/
    /**       java.util.concurrent.Future       **/
    /*********************************************/

    @Override
    public boolean isDone() {
        return isDone.get() || isCancelled.get();
    }

    @Override
    public boolean isCancelled() {
        return isCancelled.get();
    }

    @Override
    public boolean cancel(boolean force) {

        cancelTimeouts();

        if (isCancelled.getAndSet(true))
            return false;

        try {
            Channels.setDiscard(channel);
            channel.close();
        } catch (Exception t) {
            // Ignore
        }

        if (!onThrowableCalled.getAndSet(true))
            try {
                // FIXME should we set the future exception once and for all
                asyncHandler.onThrowable(new CancellationException());
            } catch (Exception t) {
                // Ignore
            }

        latch.countDown();
        runListeners();
        return true;
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
        latch.await();
        return getContent();
    }

    @Override
    public V get(long l, TimeUnit tu) throws InterruptedException, TimeoutException, ExecutionException {
        if (!latch.await(l, tu))
            throw new TimeoutException();
        return getContent();
    }

    V getContent() throws ExecutionException {

        ExecutionException e = exEx.get();
        if (e != null)
            throw e;

        V update = content.get();
        // No more retry
        currentRetry.set(maxRetry);
        if (!contentProcessed.getAndSet(true)) {
            try {
                update = asyncHandler.onCompleted();
            } catch (Throwable ex) {
                if (!onThrowableCalled.getAndSet(true)) {
                    try {
                        try {
                            asyncHandler.onThrowable(ex);
                        } catch (Throwable t) {
                            LOGGER.debug("asyncHandler.onThrowable", t);
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

    /*********************************************/
    /**   com.ning.http.clientListenableFuture  **/
    /*********************************************/
    @Override
    public final void done() {

        cancelTimeouts();

        if (isDone.getAndSet(true) || isCancelled.get())
            return;

        try {
            getContent();

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

    @Override
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
                LOGGER.debug("asyncHandler.onThrowable", te);
            }
        }
        latch.countDown();
        runListeners();
    }

    @Override
    public void content(V v) {
        content.set(v);
    }

    @Override
    public void touch() {
        touch.set(millisTime());
    }

    public long getLastTouch() {
        return touch.get();
    }

    @Override
    public boolean getAndSetWriteHeaders(boolean writeHeaders) {
        boolean b = this.writeHeaders;
        this.writeHeaders = writeHeaders;
        return b;
    }

    @Override
    public boolean getAndSetWriteBody(boolean writeBody) {
        boolean b = this.writeBody;
        this.writeBody = writeBody;
        return b;
    }

    /*********************************************/
    /**                 INTERNAL                **/
    /*********************************************/
    
    protected UriComponents getURI() {
        return uri;
    }

    protected void setURI(UriComponents uri) {
        this.uri = uri;
    }

    public ConnectionPoolKeyStrategy getConnectionPoolKeyStrategy() {
        return connectionPoolKeyStrategy;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    void setAsyncHandler(AsyncHandler<V> asyncHandler) {
        this.asyncHandler = asyncHandler;
    }

    public void setTimeoutsHolder(TimeoutsHolder timeoutsHolder) {
        this.timeoutsHolder = timeoutsHolder;
    }

    public void cancelTimeouts() {
        if (timeoutsHolder != null) {
            timeoutsHolder.cancel();
            timeoutsHolder = null;
        }
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

    protected final boolean isKeepAlive() {
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
        return channel() != null ? channel().getRemoteAddress() : null;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * Return true if the {@link Future} cannot be recovered. There is some scenario where a connection can be closed by an unexpected IOException, and in some situation we can recover from that exception.
     * 
     * @return true if that {@link Future} cannot be recovered.
     */
    public boolean canBeReplay() {
        return !isDone() && canRetry() && !(channel != null && channel.isOpen() && uri.getScheme().compareToIgnoreCase("https") != 0)
                && !isInAuth();
    }

    public long getStart() {
        return start;
    }

    @Override
    public String toString() {
        return "NettyResponseFuture{" + //
                "currentRetry=" + currentRetry + //
                ",\n\tisDone=" + isDone + //
                ",\n\tisCancelled=" + isCancelled + //
                ",\n\tasyncHandler=" + asyncHandler + //
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
