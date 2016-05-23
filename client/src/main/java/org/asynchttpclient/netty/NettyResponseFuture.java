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
import static org.asynchttpclient.util.MiscUtils.getCause;
import static io.netty.util.internal.PlatformDependent.*;
import io.netty.channel.Channel;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.future.AbstractListenableFuture;
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
public final class NettyResponseFuture<V> extends AbstractListenableFuture<V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyResponseFuture.class);

    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> REDIRECT_COUNT_UPDATER = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "redirectCount");
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture<?>> CURRENT_RETRY_UPDATER = newAtomicIntegerFieldUpdater(NettyResponseFuture.class, "currentRetry");
    @SuppressWarnings("rawtypes")
    // FIXME see https://github.com/netty/netty/pull/4669
    private static final AtomicReferenceFieldUpdater<NettyResponseFuture, Object> CONTENT_UPDATER = newAtomicReferenceFieldUpdater(NettyResponseFuture.class, "content");
    @SuppressWarnings("rawtypes")
    // FIXME see https://github.com/netty/netty/pull/4669
    private static final AtomicReferenceFieldUpdater<NettyResponseFuture, ExecutionException> EX_EX_UPDATER = newAtomicReferenceFieldUpdater(NettyResponseFuture.class, "exEx");

    private final long start = unpreciseMillisTime();
    private final ChannelPoolPartitioning connectionPoolPartitioning;
    private final ProxyServer proxyServer;
    private final int maxRetry;
    private final CountDownLatch latch = new CountDownLatch(1);

    // state mutated from outside the event loop
    // TODO check if they are indeed mutated outside the event loop
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicBoolean inAuth = new AtomicBoolean(false);
    private final AtomicBoolean inProxyAuth = new AtomicBoolean(false);
    private final AtomicBoolean statusReceived = new AtomicBoolean(false);
    private final AtomicBoolean contentProcessed = new AtomicBoolean(false);
    private final AtomicBoolean onThrowableCalled = new AtomicBoolean(false);

    // volatile where we need CAS ops
    private volatile int redirectCount = 0;
    private volatile int currentRetry = 0;
    private volatile V content;
    private volatile ExecutionException exEx;

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
    private boolean streamWasAlreadyConsumed;
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
        return isDone.get() || isCancelled();
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

        // cancel could happen before channel was attached
        if (channel != null) {
            Channels.setDiscard(channel);
            Channels.silentlyCloseChannel(channel);
        }

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

        if (isCancelled())
            throw new CancellationException();

        ExecutionException e = EX_EX_UPDATER.get(this);
        if (e != null)
            throw e;

        @SuppressWarnings("unchecked")
        V update = (V) CONTENT_UPDATER.get(this);
        // No more retry
        CURRENT_RETRY_UPDATER.set(this, maxRetry);
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
            CONTENT_UPDATER.compareAndSet(this, null, update);
        }
        return update;
    }

    // org.asynchttpclient.ListenableFuture

    private boolean terminateAndExit() {
        cancelTimeouts();
        this.channel = null;
        this.reuseChannel = false;
        return isDone.getAndSet(true) || isCancelled.get();
    }

    public final void done() {

        if (terminateAndExit())
            return;

        try {
            getContent();

        } catch (ExecutionException t) {
            return;
        } catch (RuntimeException t) {
            EX_EX_UPDATER.compareAndSet(this, null, new ExecutionException(getCause(t)));

        } finally {
            latch.countDown();
        }

        runListeners();
    }

    public final void abort(final Throwable t) {

        EX_EX_UPDATER.compareAndSet(this, null, new ExecutionException(t));

        if (terminateAndExit())
            return;

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
        touch = unpreciseMillisTime();
    }

    @Override
    public CompletableFuture<V> toCompletableFuture() {
        CompletableFuture<V> completable = new CompletableFuture<>();
        addListener(new Runnable() {
            @Override
            @SuppressWarnings("unchecked")
            public void run() {
                ExecutionException e = EX_EX_UPDATER.get(NettyResponseFuture.this);
                if (e != null)
                    completable.completeExceptionally(e.getCause());
                else
                    completable.complete((V) CONTENT_UPDATER.get(NettyResponseFuture.this));
            }

        }, new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        });

        return completable;
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

    public AtomicBoolean getInAuth() {
        return inAuth;
    }

    public AtomicBoolean getInProxyAuth() {
        return inProxyAuth;
    }

    public ChannelState getChannelState() {
        return channelState;
    }

    public void setChannelState(ChannelState channelState) {
        this.channelState = channelState;
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

    public boolean reuseChannel() {
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
    public boolean canBeReplayed() {
        return !isDone() && !(Channels.isChannelValid(channel) && !getUri().getScheme().equalsIgnoreCase("https")) && !inAuth.get() && !inProxyAuth.get();
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
                ",\n\tcontent=" + content + //
                ",\n\turi=" + getUri() + //
                ",\n\tkeepAlive=" + keepAlive + //
                ",\n\texEx=" + exEx + //
                ",\n\tredirectCount=" + redirectCount + //
                ",\n\ttimeoutsHolder=" + timeoutsHolder + //
                ",\n\tinAuth=" + inAuth + //
                ",\n\tstatusReceived=" + statusReceived + //
                ",\n\ttouch=" + touch + //
                '}';
    }
}
