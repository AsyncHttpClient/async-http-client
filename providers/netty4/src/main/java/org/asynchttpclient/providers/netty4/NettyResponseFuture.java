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
package org.asynchttpclient.providers.netty4;

import static org.asynchttpclient.util.DateUtil.millisTime;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

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

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.listenable.AbstractListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request has been fully processed.
 * 
 * @param <V>
 */
public final class NettyResponseFuture<V> extends AbstractListenableFuture<V> {

    private final static Logger logger = LoggerFactory.getLogger(NettyResponseFuture.class);
    public final static String MAX_RETRY = "org.asynchttpclient.providers.netty.maxRetry";

    public enum STATE {
        NEW, POOLED, RECONNECTED, CLOSED,
    }
    
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private AsyncHandler<V> asyncHandler;
    private final int requestTimeoutInMs;
    private final AsyncHttpClientConfig config;
    private Request request;
    private HttpRequest nettyRequest;
    private final AtomicReference<V> content = new AtomicReference<V>();
    private URI uri;
    private boolean keepAlive = true;
    private HttpResponse httpResponse;
    private final AtomicReference<ExecutionException> exEx = new AtomicReference<ExecutionException>();
    private final AtomicInteger redirectCount = new AtomicInteger();
    private volatile ReaperFuture reaperFuture;
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
    private final AtomicBoolean throwableCalled = new AtomicBoolean(false);
    private boolean allowConnect = false;
    private final ConnectionPoolKeyStrategy connectionPoolKeyStrategy;
    private final ProxyServer proxyServer;
    private final AtomicBoolean ignoreNextContents = new AtomicBoolean(false);
    private final AtomicBoolean streamWasAlreadyConsumed = new AtomicBoolean(false);

    public NettyResponseFuture(URI uri,//
            Request request,//
            AsyncHandler<V> asyncHandler,//
            HttpRequest nettyRequest,//
            int requestTimeoutInMs,//
            AsyncHttpClientConfig config,//
            ConnectionPoolKeyStrategy connectionPoolKeyStrategy,//
            ProxyServer proxyServer) {

        this.asyncHandler = asyncHandler;
        this.requestTimeoutInMs = requestTimeoutInMs;
        this.request = request;
        this.nettyRequest = nettyRequest;
        this.uri = uri;
        this.config = config;
        this.connectionPoolKeyStrategy = connectionPoolKeyStrategy;
        this.proxyServer = proxyServer;

        if (System.getProperty(MAX_RETRY) != null) {
            maxRetry = Integer.valueOf(System.getProperty(MAX_RETRY));
        } else {
            maxRetry = config.getMaxRequestRetry();
        }
        writeHeaders = true;
        writeBody = true;
    }

    public URI getURI() {
        return uri;
    }

    public void setURI(URI uri) {
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
        return isDone.get();
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
        cancelReaper();

        if (isCancelled.get())
            return false;

        try {
            Channels.setDefaultAttribute(channel, DiscardEvent.INSTANCE);
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
        runListeners();
        return true;
    }

    /**
     * Is the Future still valid
     * 
     * @return <code>true</code> if response has expired and should be terminated.
     */
    public boolean hasExpired() {
        long now = millisTime();
        return hasConnectionIdleTimedOut(now) || hasRequestTimedOut(now);
    }

    public boolean hasConnectionIdleTimedOut(long now) {
        return config.getIdleConnectionTimeoutInMs() != -1 && (now - touch.get()) >= config.getIdleConnectionTimeoutInMs();
    }

    public boolean hasRequestTimedOut(long now) {
        return requestTimeoutInMs != -1 && (now - start) >= requestTimeoutInMs;
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
        try {
            return get(requestTimeoutInMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelReaper();
            throw new ExecutionException(e);
        }
    }

    public void cancelReaper() {
        if (reaperFuture != null) {
            reaperFuture.cancel(false);
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

    public V getContent() throws ExecutionException {
        ExecutionException e = exEx.getAndSet(null);
        if (e != null) {
            throw e;
        }

        V update = content.get();
        // No more retry
        currentRetry.set(maxRetry);
        if (exEx.get() == null && !contentProcessed.getAndSet(true)) {
            try {
                update = (V) asyncHandler.onCompleted();
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

    public final void done() {

        try {
            cancelReaper();

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
        cancelReaper();

        if (isDone.get() || isCancelled.get())
            return;

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
        runListeners();
    }

    public void content(V v) {
        content.set(v);
    }

    public final Request getRequest() {
        return request;
    }

    public final HttpRequest getNettyRequest() {
        return nettyRequest;
    }

    public final void setNettyRequest(HttpRequest nettyRequest) {
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

    public final HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public final void setHttpResponse(final HttpResponse httpResponse) {
        this.httpResponse = httpResponse;
    }

    public int incrementAndGetCurrentRedirectCount() {
        return redirectCount.incrementAndGet();
    }

    public void setReaperFuture(ReaperFuture reaperFuture) {
        cancelReaper();
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

    public void setIgnoreNextContents(boolean b) {
        ignoreNextContents.set(b);
    }

    public boolean isIgnoreNextContents() {
        return ignoreNextContents.get();
    }
    
    public boolean getAndSetStreamWasAlreadyConsumed() {
        return streamWasAlreadyConsumed.getAndSet(true);
    }

    @Override
    public void touch() {
        touch.set(millisTime());
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

    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * Return true if the {@link Future} can be recovered. There is some scenario where a connection can be closed by an unexpected IOException, and in some situation we can recover from that exception.
     * 
     * @return true if that {@link Future} cannot be recovered.
     */
    public boolean canBeReplayed() {
        return !isDone() && canRetry() && !isCancelled() && !(channel != null && channel.isOpen() && !uri.getScheme().equalsIgnoreCase("https")) && !isInAuth();
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
                ",\n\thttpResponse=" + httpResponse + //
                ",\n\texEx=" + exEx + //
                ",\n\tredirectCount=" + redirectCount + //
                ",\n\treaperFuture=" + reaperFuture + //
                ",\n\tinAuth=" + inAuth + //
                ",\n\tstatusReceived=" + statusReceived + //
                ",\n\ttouch=" + touch + //
                '}';
    }
}
