/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.future;

import static com.ning.http.util.DateUtils.millisTime;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.ConnectionPoolPartitioning;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.listenable.AbstractListenableFuture;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.request.NettyRequest;
import com.ning.http.client.providers.netty.request.timeout.TimeoutsHolder;
import com.ning.http.client.uri.Uri;

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

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request has been fully processed.
 * 
 * @param <V>
 */
public final class NettyResponseFuture<V> extends AbstractListenableFuture<V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyResponseFuture.class);

    public enum STATE {
        NEW, POOLED, RECONNECTED, CLOSED,
    }
    
    private volatile boolean requestTimeoutReached;
    private volatile boolean idleConnectionTimeoutReached;
    private final long start = millisTime();
    private final ConnectionPoolPartitioning connectionPoolPartitioning;
    private final ProxyServer proxyServer;
    private final int maxRetry;
    private final CountDownLatch latch = new CountDownLatch(1);

    // state mutated from outside the event loop
    // TODO check if they are indeed mutated outside the event loop
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicInteger redirectCount = new AtomicInteger();
    private final AtomicBoolean inAuth = new AtomicBoolean(false);
    private final AtomicBoolean statusReceived = new AtomicBoolean(false);
    private final AtomicLong touch = new AtomicLong(millisTime());
    private final AtomicReference<STATE> state = new AtomicReference<STATE>(STATE.NEW);
    private final AtomicBoolean contentProcessed = new AtomicBoolean(false);
    private final AtomicInteger currentRetry = new AtomicInteger(0);
    private final AtomicBoolean onThrowableCalled = new AtomicBoolean(false);
    private final AtomicReference<V> content = new AtomicReference<V>();
    private final AtomicReference<ExecutionException> exEx = new AtomicReference<ExecutionException>();
    private volatile TimeoutsHolder timeoutsHolder;

    // state mutated only inside the event loop
    private Channel channel;
    private Uri uri;
    private boolean keepAlive = true;
    private Request request;
    private NettyRequest nettyRequest;
    private HttpHeaders httpHeaders;
    private AsyncHandler<V> asyncHandler;
    private boolean streamWasAlreadyConsumed;
    private boolean reuseChannel;
    private boolean headersAlreadyWrittenOnContinue;
    private boolean dontWriteBodyBecauseExpectContinue;
    private boolean allowConnect;

    public NettyResponseFuture(Uri uri,//
            Request request,//
            AsyncHandler<V> asyncHandler,//
            NettyRequest nettyRequest,//
            int maxRetry,//
            ConnectionPoolPartitioning connectionPoolPartitioning,//
            ProxyServer proxyServer) {

        this.asyncHandler = asyncHandler;
        this.request = request;
        this.nettyRequest = nettyRequest;
        this.uri = uri;
        this.connectionPoolPartitioning = connectionPoolPartitioning;
        this.proxyServer = proxyServer;
        this.maxRetry = maxRetry;
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

        Channels.setDiscard(channel);
        Channels.silentlyCloseChannel(channel);

        if (!onThrowableCalled.getAndSet(true)) {
            try {
                asyncHandler.onThrowable(new CancellationException());
            } catch (Throwable t) {
                LOGGER.warn("cancel", t);
            }
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

    private V getContent() throws ExecutionException {

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
    /**   org.asynchttpclient.ListenableFuture  **/
    /*********************************************/

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

    public final void abort(final Throwable t) {

        cancelTimeouts();

        if (isDone.get() || isCancelled.getAndSet(true))
            return;

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
    public void touch() {
        touch.set(millisTime());
    }

    /*********************************************/
    /**                 INTERNAL                **/
    /*********************************************/

    public Uri getUri() {
        return uri;
    }

    public void setUri(Uri uri) {
        this.uri = uri;
    }

    public ConnectionPoolPartitioning getConnectionPoolPartitioning() {
        return connectionPoolPartitioning;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public void setAsyncHandler(AsyncHandler<V> asyncHandler) {
        this.asyncHandler = asyncHandler;
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

    public void cancelTimeouts() {
        if (timeoutsHolder != null) {
            timeoutsHolder.cancel();
            timeoutsHolder = null;
        }
    }

    public final Request getRequest() {
        return request;
    }

    public final NettyRequest getNettyRequest() {
        return nettyRequest;
    }

    public final void setNettyRequest(NettyRequest nettyRequest) {
        this.nettyRequest = nettyRequest;
    }

    public final AsyncHandler<V> getAsyncHandler() {
        return asyncHandler;
    }

    public final boolean isKeepAlive() {
        return keepAlive;
    }

    public final void setKeepAlive(final boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public final HttpHeaders getHttpHeaders() {
        return httpHeaders;
    }

    public final void setHttpHeaders(HttpHeaders httpHeaders) {
        this.httpHeaders = httpHeaders;
    }

    public int incrementAndGetCurrentRedirectCount() {
        return redirectCount.incrementAndGet();
    }

    public void setTimeoutsHolder(TimeoutsHolder timeoutsHolder) {
        this.timeoutsHolder = timeoutsHolder;
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

    public boolean isStreamWasAlreadyConsumed() {
        return streamWasAlreadyConsumed;
    }

    public void setStreamWasAlreadyConsumed(boolean streamWasAlreadyConsumed) {
        this.streamWasAlreadyConsumed = streamWasAlreadyConsumed;
    }

    public long getLastTouch() {
        return touch.get();
    }

    public void setHeadersAlreadyWrittenOnContinue(boolean headersAlreadyWrittenOnContinue) {
        this.headersAlreadyWrittenOnContinue = headersAlreadyWrittenOnContinue;
    }

    public boolean isHeadersAlreadyWrittenOnContinue() {
        return headersAlreadyWrittenOnContinue;
    }

    public void setDontWriteBodyBecauseExpectContinue(boolean dontWriteBodyBecauseExpectContinue) {
        this.dontWriteBodyBecauseExpectContinue = dontWriteBodyBecauseExpectContinue;
    }

    public boolean isDontWriteBodyBecauseExpectContinue() {
        return dontWriteBodyBecauseExpectContinue;
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

    public void attachChannel(Channel channel, boolean reuseChannel) {
        this.channel = channel;
        this.reuseChannel = reuseChannel;
    }

    public Channel channel() {
        return channel;
    }

    public boolean reuseChannel() {
        return reuseChannel;
    }

    public boolean canRetry() {
        return maxRetry > 0 && currentRetry.incrementAndGet() <= maxRetry;
    }

    public SocketAddress getChannelRemoteAddress() {
        return channel != null ? channel.getRemoteAddress() : null;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * Return true if the {@link Future} can be recovered. There is some scenario where a connection can be closed by an
     * unexpected IOException, and in some situation we can recover from that exception.
     * 
     * @return true if that {@link Future} cannot be recovered.
     */
    public boolean canBeReplayed() {
        return !isDone() && canRetry()
                && !(Channels.isChannelValid(channel) && !uri.getScheme().equalsIgnoreCase("https")) && !isInAuth();
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
                ",\n\thttpHeaders=" + httpHeaders + //
                ",\n\texEx=" + exEx + //
                ",\n\tredirectCount=" + redirectCount + //
                ",\n\ttimeoutsHolder=" + timeoutsHolder + //
                ",\n\tinAuth=" + inAuth + //
                ",\n\tstatusReceived=" + statusReceived + //
                ",\n\ttouch=" + touch + //
                '}';
    }
}
