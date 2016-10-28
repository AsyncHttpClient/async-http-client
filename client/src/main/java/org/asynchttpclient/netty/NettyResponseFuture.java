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
package org.asynchttpclient.netty;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;
import static io.netty.util.internal.PlatformDependent.*;
import io.netty.channel.Channel;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request has been fully processed.
 * 
 * @param <V> the result type
 */
public final class NettyResponseFuture<V> implements ListenableFuture<V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyResponseFuture.class);

    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> REDIRECT_COUNT_UPDATER = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "redirectCount");
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> CURRENT_RETRY_UPDATER = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "currentRetry");

    private final long start = unpreciseMillisTime();
    private final ChannelPoolPartitioning connectionPoolPartitioning;
    private final ProxyServer proxyServer;
    private final int maxRetry;
    private final CompletableFuture<V> future = new CompletableFuture<>();

    // state mutated from outside the event loop
    // TODO check if they are indeed mutated outside the event loop
    private volatile int isDone = 0;
    private volatile int isCancelled = 0;
    private volatile int inAuth = 0;
    private volatile int inProxyAuth = 0;
    private volatile int statusReceived = 0;
    @SuppressWarnings("unused")
    private volatile int contentProcessed = 0;
    @SuppressWarnings("unused")
    private volatile int onThrowableCalled = 0;

    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> isDoneField = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "isDone");
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> isCancelledField = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "isCancelled");
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> inAuthField = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "inAuth");
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> inProxyAuthField = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "inProxyAuth");
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> statusReceivedField = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "statusReceived");
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> contentProcessedField = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "contentProcessed");
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> onThrowableCalledField = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "onThrowableCalled");

    // volatile where we need CAS ops
    private volatile int redirectCount = 0;
    private volatile int currentRetry = 0;

    // volatile where we don't need CAS ops
    private volatile long touch = unpreciseMillisTime();
    private volatile TimeoutsHolder timeoutsHolder;
    private volatile ChannelState channelState = ChannelState.NEW;

    // state mutated only inside the event loop
    private Channel channel;
    private boolean keepAlive = true;
    private Request targetRequest;
    private Request currentRequest;
    private NettyRequest nettyRequest;
    private AsyncHandler<V> asyncHandler;
    private boolean streamAlreadyConsumed;
    private boolean reuseChannel;
    private boolean headersAlreadyWrittenOnContinue;
    private boolean dontWriteBodyBecauseExpectContinue;
    private boolean allowConnect;
    private Realm realm;
    private Realm proxyRealm;
    public Throwable pendingException;

    public NettyResponseFuture(Request originalRequest,//
            AsyncHandler<V> asyncHandler,//
            NettyRequest nettyRequest,//
            int maxRetry,//
            ChannelPoolPartitioning connectionPoolPartitioning,//
            ProxyServer proxyServer) {

        this.asyncHandler = asyncHandler;
        this.targetRequest = currentRequest = originalRequest;
        this.nettyRequest = nettyRequest;
        this.connectionPoolPartitioning = connectionPoolPartitioning;
        this.proxyServer = proxyServer;
        this.maxRetry = maxRetry;
    }

    // java.util.concurrent.Future

    @Override
    public boolean isDone() {
        return isDone != 0 || isCancelled();
    }

    @Override
    public boolean isCancelled() {
        return isCancelled != 0;
    }

    @Override
    public boolean cancel(boolean force) {
        cancelTimeouts();

        if (isCancelledField.getAndSet(this, 1) != 0)
            return false;

        // cancel could happen before channel was attached
        if (channel != null) {
            Channels.setDiscard(channel);
            Channels.silentlyCloseChannel(channel);
        }

        if (onThrowableCalledField.getAndSet(this, 1) == 0) {
            try {
                asyncHandler.onThrowable(new CancellationException());
            } catch (Throwable t) {
                LOGGER.warn("cancel", t);
            }
        }

        future.cancel(false);
        return true;
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
        return future.get();
    }

    @Override
    public V get(long l, TimeUnit tu) throws InterruptedException, TimeoutException, ExecutionException {
        return future.get(l, tu);
    }

    private V getContent() throws ExecutionException {
        if (future.isDone()) {
            try {
                return future.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("unreachable", e);
            }
        }

        // No more retry
        CURRENT_RETRY_UPDATER.set(this, maxRetry);
        if (contentProcessedField.getAndSet(this, 1) == 0) {
            try {
                future.complete(asyncHandler.onCompleted());
            } catch (Throwable ex) {
                if (onThrowableCalledField.getAndSet(this, 1) == 0) {
                    try {
                        try {
                            asyncHandler.onThrowable(ex);
                        } catch (Throwable t) {
                            LOGGER.debug("asyncHandler.onThrowable", t);
                        }
                    } finally {
                        cancelTimeouts();
                    }
                }
                future.completeExceptionally(ex);
            }
        }
        return future.getNow(null);
    }

    // org.asynchttpclient.ListenableFuture

    private boolean terminateAndExit() {
        cancelTimeouts();
        this.channel = null;
        this.reuseChannel = false;
        return isDoneField.getAndSet(this, 1) != 0 || isCancelled != 0;
    }

    public final void done() {

        if (terminateAndExit())
            return;

        try {
            getContent();
        } catch (ExecutionException ignored) {

        } catch (RuntimeException t) {
            future.completeExceptionally(t);
        } catch (Throwable t) {
            future.completeExceptionally(t);
            throw t;
        }
    }

    public final void abort(final Throwable t) {

        future.completeExceptionally(t);

        if (terminateAndExit())
            return;

        if (onThrowableCalledField.compareAndSet(this, 0, 1)) {
            try {
                asyncHandler.onThrowable(t);
            } catch (Throwable te) {
                LOGGER.debug("asyncHandler.onThrowable", te);
            }
        }
    }

    @Override
    public void touch() {
        touch = unpreciseMillisTime();
    }

    @Override
    public ListenableFuture<V> addListener(Runnable listener, Executor exec) {
        future.whenCompleteAsync((r, v) -> listener.run(), exec);
        return this;
    }

    @Override
    public CompletableFuture<V> toCompletableFuture() {
        return future;
    }

    // INTERNAL

    public Uri getUri() {
        return targetRequest.getUri();
    }

    public ChannelPoolPartitioning getConnectionPoolPartitioning() {
        return connectionPoolPartitioning;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public void setAsyncHandler(AsyncHandler<V> asyncHandler) {
        this.asyncHandler = asyncHandler;
    }

    public void cancelTimeouts() {
        if (timeoutsHolder != null) {
            timeoutsHolder.cancel();
            timeoutsHolder = null;
        }
    }

    public final Request getTargetRequest() {
        return targetRequest;
    }

    public final Request getCurrentRequest() {
        return currentRequest;
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

    public int incrementAndGetCurrentRedirectCount() {
        return REDIRECT_COUNT_UPDATER.incrementAndGet(this);
    }

    public void setTimeoutsHolder(TimeoutsHolder timeoutsHolder) {
        this.timeoutsHolder = timeoutsHolder;
    }

    public TimeoutsHolder getTimeoutsHolder() {
        return timeoutsHolder;
    }

    public boolean isInAuth() {
        return inAuth != 0;
    }

    public void setInAuth(boolean inAuth) {
        this.inAuth = inAuth ? 1 : 0;
    }

    public boolean isAndSetInAuth(boolean set) {
        return inAuthField.getAndSet(this, set ? 1 : 0) != 0;
    }

    public boolean isInProxyAuth() {
        return inProxyAuth != 0;
    }

    public void setInProxyAuth(boolean inProxyAuth) {
        this.inProxyAuth = inProxyAuth ? 1 : 0;
    }

    public boolean isAndSetInProxyAuth(boolean inProxyAuth) {
        return inProxyAuthField.getAndSet(this, inProxyAuth ? 1 : 0) != 0;
    }

    public ChannelState getChannelState() {
        return channelState;
    }

    public void setChannelState(ChannelState channelState) {
        this.channelState = channelState;
    }

    public boolean isAndSetStatusReceived(boolean sr) {
        return statusReceivedField.getAndSet(this, sr ? 1 : 0) != 0;
    }

    public boolean isStreamConsumed() {
        return streamAlreadyConsumed;
    }

    public void setStreamConsumed(boolean streamConsumed) {
        this.streamAlreadyConsumed = streamConsumed;
    }

    public long getLastTouch() {
        return touch;
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

        // future could have been cancelled first
        if (isDone()) {
            Channels.silentlyCloseChannel(channel);
        }

        this.channel = channel;
        this.reuseChannel = reuseChannel;
    }

    public Channel channel() {
        return channel;
    }

    public boolean isReuseChannel() {
        return reuseChannel;
    }

    public boolean incrementRetryAndCheck() {
        return maxRetry > 0 && CURRENT_RETRY_UPDATER.incrementAndGet(this) <= maxRetry;
    }

    public void setTargetRequest(Request targetRequest) {
        this.targetRequest = targetRequest;
    }

    public void setCurrentRequest(Request currentRequest) {
        this.currentRequest = currentRequest;
    }

    /**
     * Return true if the {@link Future} can be recovered. There is some scenario where a connection can be closed by an unexpected IOException, and in some situation we can
     * recover from that exception.
     * 
     * @return true if that {@link Future} cannot be recovered.
     */
    public boolean isReplayPossible() {
        return !isDone() && !(Channels.isChannelValid(channel) && !getUri().getScheme().equalsIgnoreCase("https")) && inAuth == 0 && inProxyAuth == 0;
    }

    public long getStart() {
        return start;
    }

    public Object getPartitionKey() {
        return connectionPoolPartitioning.getPartitionKey(targetRequest.getUri(), targetRequest.getVirtualHost(), proxyServer);
    }

    public Realm getRealm() {
        return realm;
    }

    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    public Realm getProxyRealm() {
        return proxyRealm;
    }

    public void setProxyRealm(Realm proxyRealm) {
        this.proxyRealm = proxyRealm;
    }

    @Override
    public String toString() {
        return "NettyResponseFuture{" + //
                "currentRetry=" + currentRetry + //
                ",\n\tisDone=" + isDone + //
                ",\n\tisCancelled=" + isCancelled + //
                ",\n\tasyncHandler=" + asyncHandler + //
                ",\n\tnettyRequest=" + nettyRequest + //
                ",\n\tfuture=" + future + //
                ",\n\turi=" + getUri() + //
                ",\n\tkeepAlive=" + keepAlive + //
                ",\n\tredirectCount=" + redirectCount + //
                ",\n\ttimeoutsHolder=" + timeoutsHolder + //
                ",\n\tinAuth=" + inAuth + //
                ",\n\tstatusReceived=" + statusReceived + //
                ",\n\ttouch=" + touch + //
                '}';
    }
}
