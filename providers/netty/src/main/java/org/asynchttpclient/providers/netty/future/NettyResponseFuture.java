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
package org.asynchttpclient.providers.netty.future;

import static org.asynchttpclient.util.DateUtils.millisTime;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.listenable.AbstractListenableFuture;
import org.asynchttpclient.providers.netty.DiscardEvent;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.request.NettyRequest;
import org.asynchttpclient.providers.netty.request.timeout.TimeoutsHolder;
import org.asynchttpclient.uri.UriComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;

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

    private static final Logger logger = LoggerFactory.getLogger(NettyResponseFuture.class);
    public static final String MAX_RETRY = "org.asynchttpclient.providers.netty.maxRetry";

    public enum STATE {
        NEW, POOLED, RECONNECTED, CLOSED,
    }

    private final int requestTimeoutInMs;
    private volatile boolean requestTimeoutReached;
    private volatile boolean idleConnectionTimeoutReached;
    private final long start = millisTime();
    private final ConnectionPoolKeyStrategy connectionPoolKeyStrategy;
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
    private UriComponents uri;
    private boolean keepAlive = true;
    private Request request;
    private NettyRequest nettyRequest;
    private HttpHeaders httpHeaders;
    private AsyncHandler<V> asyncHandler;
    private HttpResponse pendingResponse;
    private boolean streamWasAlreadyConsumed;
    private boolean reuseChannel;
    private boolean headersAlreadyWrittenOnContinue;
    private boolean dontWriteBodyBecauseExpectContinue;
    private boolean allowConnect;

    public NettyResponseFuture(UriComponents uri,//
            Request request,//
            AsyncHandler<V> asyncHandler,//
            NettyRequest nettyRequest,//
            int requestTimeoutInMs,//
            AsyncHttpClientConfig config,//
            ConnectionPoolKeyStrategy connectionPoolKeyStrategy,//
            ProxyServer proxyServer) {

        this.asyncHandler = asyncHandler;
        this.requestTimeoutInMs = requestTimeoutInMs;
        this.request = request;
        this.nettyRequest = nettyRequest;
        this.uri = uri;
        this.connectionPoolKeyStrategy = connectionPoolKeyStrategy;
        this.proxyServer = proxyServer;

        maxRetry = Integer.getInteger(MAX_RETRY, config.getMaxRequestRetry());
    }

    public UriComponents getURI() {
        return uri;
    }

    public void setURI(UriComponents uri) {
        this.uri = uri;
    }

    public ConnectionPoolKeyStrategy getConnectionPoolKeyStrategy() {
        return connectionPoolKeyStrategy;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    @Override
    public boolean isDone() {
        return isDone.get() || isCancelled.get();
    }

    @Override
    public boolean isCancelled() {
        return isCancelled.get();
    }

    public void setAsyncHandler(AsyncHandler<V> asyncHandler) {
        this.asyncHandler = asyncHandler;
    }

    @Override
    public boolean cancel(boolean force) {
        cancelTimeouts();

        if (isCancelled.getAndSet(true))
            return false;

        try {
            Channels.setDefaultAttribute(channel, DiscardEvent.INSTANCE);
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

    @Override
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

    @Override
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
                    Channels.setDefaultAttribute(channel, DiscardEvent.INSTANCE);
                    channel.close();
                } catch (Throwable t) {
                    // Ignore
                }
                TimeoutException te = new TimeoutException(String.format("No response received after %s %s", l, tu.name().toLowerCase()));
                if (!onThrowableCalled.getAndSet(true)) {
                    try {
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

    private V getContent() throws ExecutionException {
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

        try {
            cancelTimeouts();

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

    public HttpResponse getPendingResponse() {
        return pendingResponse;
    }

    public void setPendingResponse(HttpResponse pendingResponse) {
        this.pendingResponse = pendingResponse;
    }

    public boolean isStreamWasAlreadyConsumed() {
        return streamWasAlreadyConsumed;
    }

    public void setStreamWasAlreadyConsumed(boolean streamWasAlreadyConsumed) {
        this.streamWasAlreadyConsumed = streamWasAlreadyConsumed;
    }

    @Override
    public void touch() {
        touch.set(millisTime());
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

    public void attachChannel(Channel channel) {
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
        if (currentRetry.incrementAndGet() > maxRetry) {
            return false;
        }
        return true;
    }

    public SocketAddress getChannelRemoteAddress() {
        return channel() != null? channel().remoteAddress(): null;
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
        return !isDone() && canRetry() && !isCancelled()
                && !(channel != null && channel.isOpen() && !uri.getScheme().equalsIgnoreCase("https")) && !isInAuth();
    }

    public long getStart() {
        return start;
    }

    public long getRequestTimeoutInMs() {
        return requestTimeoutInMs;
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
                ",\n\thttpHeaders=" + httpHeaders + //
                ",\n\texEx=" + exEx + //
                ",\n\tredirectCount=" + redirectCount + //
                ",\n\timeoutsHolder=" + timeoutsHolder + //
                ",\n\tinAuth=" + inAuth + //
                ",\n\tstatusReceived=" + statusReceived + //
                ",\n\ttouch=" + touch + //
                '}';
    }
}
