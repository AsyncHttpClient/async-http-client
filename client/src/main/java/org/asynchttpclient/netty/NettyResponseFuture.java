/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty;

import io.netty.channel.Channel;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.channel.ConnectionSemaphore;
import org.asynchttpclient.netty.channel.RoundRobinPartitionKey;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.scram.ScramContext;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request
 * has been fully processed.
 *
 * @param <V> the result type
 */
public final class NettyResponseFuture<V> implements ListenableFuture<V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyResponseFuture.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture> REDIRECT_COUNT_UPDATER = AtomicIntegerFieldUpdater
            .newUpdater(NettyResponseFuture.class, "redirectCount");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture> CURRENT_RETRY_UPDATER = AtomicIntegerFieldUpdater
            .newUpdater(NettyResponseFuture.class, "currentRetry");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture> IS_DONE_FIELD = AtomicIntegerFieldUpdater
            .newUpdater(NettyResponseFuture.class, "isDone");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture> IS_CANCELLED_FIELD = AtomicIntegerFieldUpdater
            .newUpdater(NettyResponseFuture.class, "isCancelled");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture> IN_AUTH_FIELD = AtomicIntegerFieldUpdater
            .newUpdater(NettyResponseFuture.class, "inAuth");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture> IN_PROXY_AUTH_FIELD = AtomicIntegerFieldUpdater
            .newUpdater(NettyResponseFuture.class, "inProxyAuth");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture> CONTENT_PROCESSED_FIELD = AtomicIntegerFieldUpdater
            .newUpdater(NettyResponseFuture.class, "contentProcessed");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<NettyResponseFuture> ON_THROWABLE_CALLED_FIELD = AtomicIntegerFieldUpdater
            .newUpdater(NettyResponseFuture.class, "onThrowableCalled");
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<NettyResponseFuture, TimeoutsHolder> TIMEOUTS_HOLDER_FIELD = AtomicReferenceFieldUpdater
            .newUpdater(NettyResponseFuture.class, TimeoutsHolder.class, "timeoutsHolder");
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<NettyResponseFuture, Object> PARTITION_KEY_LOCK_FIELD = AtomicReferenceFieldUpdater
            .newUpdater(NettyResponseFuture.class, Object.class, "partitionKeyLock");

    private final long start = unpreciseMillisTime();
    private final ChannelPoolPartitioning connectionPoolPartitioning;
    private final ConnectionSemaphore connectionSemaphore;
    private final ProxyServer proxyServer;
    private final int maxRetry;
    private final CompletableFuture<V> future = new CompletableFuture<>();
    public Throwable pendingException;
    // state mutated from outside the event loop
    // TODO check if they are indeed mutated outside the event loop
    private volatile int isDone;
    private volatile int isCancelled;
    private volatile int inAuth;
    private volatile int inProxyAuth;
    @SuppressWarnings("unused")
    private volatile int contentProcessed;
    @SuppressWarnings("unused")
    private volatile int onThrowableCalled;
    @SuppressWarnings("unused")
    private volatile TimeoutsHolder timeoutsHolder;
    // partition key, when != null used to release lock in ChannelManager
    private volatile Object partitionKeyLock;
    // volatile where we need CAS ops
    private volatile int redirectCount;
    private volatile int currentRetry;
    // volatile where we don't need CAS ops
    private volatile long touch = unpreciseMillisTime();
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
    // RequestSendType.ROUND_ROBIN overrides; all null in DEFAULT mode
    private volatile Object partitionKeyOverride;
    private volatile List<InetSocketAddress> roundRobinAddresses;
    private volatile String roundRobinHost;
    private volatile ScramContext scramContext;

    public NettyResponseFuture(Request originalRequest,
                               AsyncHandler<V> asyncHandler,
                               NettyRequest nettyRequest,
                               int maxRetry,
                               ChannelPoolPartitioning connectionPoolPartitioning,
                               ConnectionSemaphore connectionSemaphore,
                               ProxyServer proxyServer) {

        this.asyncHandler = asyncHandler;
        targetRequest = currentRequest = originalRequest;
        this.nettyRequest = nettyRequest;
        this.connectionPoolPartitioning = connectionPoolPartitioning;
        this.connectionSemaphore = connectionSemaphore;
        this.proxyServer = proxyServer;
        this.maxRetry = maxRetry;
    }

    private void releasePartitionKeyLock() {
        if (connectionSemaphore == null) {
            return;
        }

        Object partitionKey = takePartitionKeyLock();
        if (partitionKey != null) {
            connectionSemaphore.releaseChannelLock(partitionKey);
        }
    }

    // Take partition key lock object,
    // but do not release channel lock.
    public Object takePartitionKeyLock() {
        // shortcut, much faster than getAndSet
        if (partitionKeyLock == null) {
            return null;
        }

        return PARTITION_KEY_LOCK_FIELD.getAndSet(this, null);
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
        releasePartitionKeyLock();
        cancelTimeouts();

        if (IS_CANCELLED_FIELD.getAndSet(this, 1) != 0) {
            return false;
        }

        releaseRequestIfNotHandedToChannel();

        final Channel ch = channel; //atomic read, so that it won't end up in TOCTOU
        if (ch != null) {
            Channels.setDiscard(ch);
            Channels.silentlyCloseChannel(ch);
        }

        if (ON_THROWABLE_CALLED_FIELD.getAndSet(this, 1) == 0) {
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

    private void loadContent() throws ExecutionException {
        if (future.isDone()) {
            try {
                future.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("unreachable", e);
            }
        }

        // No more retry
        CURRENT_RETRY_UPDATER.set(this, maxRetry);
        if (CONTENT_PROCESSED_FIELD.getAndSet(this, 1) == 0) {
            try {
                future.complete(asyncHandler.onCompleted());
            } catch (Throwable ex) {
                if (ON_THROWABLE_CALLED_FIELD.getAndSet(this, 1) == 0) {
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
        future.getNow(null);
    }

    // org.asynchttpclient.ListenableFuture

    private boolean terminateAndExit() {
        releasePartitionKeyLock();
        cancelTimeouts();
        channel = null;
        reuseChannel = false;
        boolean alreadyTerminated = IS_DONE_FIELD.getAndSet(this, 1) != 0 || isCancelled != 0;
        if (!alreadyTerminated) {
            releaseRequestIfNotHandedToChannel();
        }
        return alreadyTerminated;
    }

    /**
     * Frees the request body buffer when the request was never handed to a channel encoder. On the HTTP/1.1
     * success path Netty's encoder releases {@code httpRequest} after the write; but on an abort/cancel BEFORE
     * the write (connect failure, onRequestSend crash, pool closed, cancellation) — or on replacement during a
     * redirect/retry — nothing else would, leaking a {@code setBody(ByteBuf)} retained duplicate. In the
     * HTTP/2 path {@code httpRequest} is never written to a channel, so AHC always owns its release.
     * {@link NettyRequest#release()} is idempotent (CAS), so this never double-frees the encoder or the
     * explicit HTTP/2 releases.
     */
    private void releaseRequestIfNotHandedToChannel() {
        NettyRequest request = nettyRequest;
        if (request != null) {
            // release() atomically no-ops if the request was already handed to the channel encoder (which then
            // owns the release), so there is no check-then-act race with the concurrent event-loop write.
            request.release();
        }
    }

    @Override
    public void done() {

        if (terminateAndExit()) {
            return;
        }

        try {
            loadContent();
        } catch (ExecutionException ignored) {

        } catch (RuntimeException t) {
            future.completeExceptionally(t);
        } catch (Throwable t) {
            future.completeExceptionally(t);
            throw t;
        }
    }

    @Override
    public void abort(final Throwable t) {

        if (terminateAndExit()) {
            return;
        }

        future.completeExceptionally(t);

        if (ON_THROWABLE_CALLED_FIELD.compareAndSet(this, 0, 1)) {
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
        if (exec == null) {
            exec = Runnable::run;
        }
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

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public void cancelTimeouts() {
        TimeoutsHolder ref = TIMEOUTS_HOLDER_FIELD.getAndSet(this, null);
        if (ref != null) {
            ref.cancel();
        }
    }

    public Request getTargetRequest() {
        return targetRequest;
    }

    public void setTargetRequest(Request targetRequest) {
        this.targetRequest = targetRequest;
    }

    public Request getCurrentRequest() {
        return currentRequest;
    }

    public void setCurrentRequest(Request currentRequest) {
        this.currentRequest = currentRequest;
    }

    public NettyRequest getNettyRequest() {
        return nettyRequest;
    }

    public void setNettyRequest(NettyRequest nettyRequest) {
        // On a redirect/auth/retry the request is rebuilt; release the previous one if it was never written,
        // so its body buffer is not leaked. The replaced request is normally already written (handed to the
        // channel) or already released, in which case this is a no-op (release() is idempotent).
        NettyRequest previous = this.nettyRequest;
        if (previous != null && previous != nettyRequest && !previous.isHandedToChannel()) {
            previous.release();
        }
        this.nettyRequest = nettyRequest;
    }

    public AsyncHandler<V> getAsyncHandler() {
        return asyncHandler;
    }

    public void setAsyncHandler(AsyncHandler<V> asyncHandler) {
        this.asyncHandler = asyncHandler;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(final boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public int incrementAndGetCurrentRedirectCount() {
        return REDIRECT_COUNT_UPDATER.incrementAndGet(this);
    }

    public TimeoutsHolder getTimeoutsHolder() {
        return TIMEOUTS_HOLDER_FIELD.get(this);
    }

    public void setTimeoutsHolder(TimeoutsHolder timeoutsHolder) {
        TimeoutsHolder ref = TIMEOUTS_HOLDER_FIELD.getAndSet(this, timeoutsHolder);
        if (ref != null) {
            ref.cancel();
        }
    }

    public boolean isInAuth() {
        return inAuth != 0;
    }

    public void setInAuth(boolean inAuth) {
        this.inAuth = inAuth ? 1 : 0;
    }

    public boolean isAndSetInAuth(boolean set) {
        return IN_AUTH_FIELD.getAndSet(this, set ? 1 : 0) != 0;
    }

    public boolean isInProxyAuth() {
        return inProxyAuth != 0;
    }

    public void setInProxyAuth(boolean inProxyAuth) {
        this.inProxyAuth = inProxyAuth ? 1 : 0;
    }

    public boolean isAndSetInProxyAuth(boolean inProxyAuth) {
        return IN_PROXY_AUTH_FIELD.getAndSet(this, inProxyAuth ? 1 : 0) != 0;
    }

    public ChannelState getChannelState() {
        return channelState;
    }

    public void setChannelState(ChannelState channelState) {
        this.channelState = channelState;
    }

    public boolean isStreamConsumed() {
        return streamAlreadyConsumed;
    }

    public void setStreamConsumed(boolean streamConsumed) {
        streamAlreadyConsumed = streamConsumed;
    }

    public long getLastTouch() {
        return touch;
    }

    public boolean isHeadersAlreadyWrittenOnContinue() {
        return headersAlreadyWrittenOnContinue;
    }

    public void setHeadersAlreadyWrittenOnContinue(boolean headersAlreadyWrittenOnContinue) {
        this.headersAlreadyWrittenOnContinue = headersAlreadyWrittenOnContinue;
    }

    public boolean isDontWriteBodyBecauseExpectContinue() {
        return dontWriteBodyBecauseExpectContinue;
    }

    public void setDontWriteBodyBecauseExpectContinue(boolean dontWriteBodyBecauseExpectContinue) {
        this.dontWriteBodyBecauseExpectContinue = dontWriteBodyBecauseExpectContinue;
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

    public void setReuseChannel(boolean reuseChannel) {
        this.reuseChannel = reuseChannel;
    }

    public boolean incrementRetryAndCheck() {
        return maxRetry > 0 && CURRENT_RETRY_UPDATER.incrementAndGet(this) <= maxRetry;
    }

    /**
     * Return true if the {@link Future} can be recovered. There is some scenario
     * where a connection can be closed by an unexpected IOException, and in some
     * situation we can recover from that exception.
     *
     * @return true if that {@link Future} cannot be recovered.
     */
    public boolean isReplayPossible() {
        return !isDone() && !(Channels.isChannelActive(channel) && !"https".equalsIgnoreCase(getUri().getScheme()))
                && inAuth == 0 && inProxyAuth == 0;
    }

    public long getStart() {
        return start;
    }

    public Object getPartitionKey() {
        Object override = partitionKeyOverride;
        if (override != null) {
            return override;
        }
        return basePartitionKey();
    }

    /**
     * The per-host partition key, ignoring any round-robin IP-aware override. Used for the connection
     * semaphore so {@code maxConnectionsPerHost} stays per host (not per IP): the permit is taken
     * before the target IP is known and the connector may fail over to a different IP than the one
     * initially selected.
     */
    public Object basePartitionKey() {
        return connectionPoolPartitioning.getPartitionKey(targetRequest.getUri(), targetRequest.getVirtualHost(),
                proxyServer);
    }

    /**
     * @return the IP-aware partition key set for {@link org.asynchttpclient.RequestSendType#ROUND_ROBIN},
     * or {@code null} when not in round-robin mode
     */
    public Object getPartitionKeyOverride() {
        return partitionKeyOverride;
    }

    public void setPartitionKeyOverride(Object partitionKeyOverride) {
        this.partitionKeyOverride = partitionKeyOverride;
    }

    /**
     * @return the resolved addresses to connect to (round-robin-ordered), or {@code null} to resolve
     * lazily as usual
     */
    public List<InetSocketAddress> getRoundRobinAddresses() {
        return roundRobinAddresses;
    }

    public void setRoundRobinAddresses(List<InetSocketAddress> roundRobinAddresses) {
        this.roundRobinAddresses = roundRobinAddresses;
    }

    /**
     * @return the host the round-robin overrides were computed for, used to detect host changes on
     * redirects
     */
    public String getRoundRobinHost() {
        return roundRobinHost;
    }

    public void setRoundRobinHost(String roundRobinHost) {
        this.roundRobinHost = roundRobinHost;
    }

    /**
     * Drops any round-robin overrides, e.g. when this future is reused for a cross-host redirect whose
     * target is not eligible for round-robin.
     */
    public void clearRoundRobinOverrides() {
        partitionKeyOverride = null;
        roundRobinAddresses = null;
        roundRobinHost = null;
    }

    /**
     * Re-pins the round-robin partition key to the IP actually connected to. The connector may have
     * failed over from the initially selected IP to a later one; keying connection reuse by the real
     * peer IP keeps the pool / HTTP/2 registry correct. No-op outside round-robin mode.
     */
    public void repinRoundRobinAddress(InetAddress actualAddress) {
        Object override = partitionKeyOverride;
        if (actualAddress != null && override instanceof RoundRobinPartitionKey) {
            partitionKeyOverride = ((RoundRobinPartitionKey) override).withAddress(actualAddress);
        }
    }

    public void acquirePartitionLockLazily() throws IOException {
        if (connectionSemaphore == null || partitionKeyLock != null) {
            return;
        }

        // Semaphore is keyed per host (base key), not the round-robin per-IP override: the permit is
        // taken before the target IP is known and the connector may fail over to another IP.
        Object partitionKey = basePartitionKey();
        connectionSemaphore.acquireChannelLock(partitionKey);
        Object prevKey = PARTITION_KEY_LOCK_FIELD.getAndSet(this, partitionKey);
        if (prevKey != null) {
            // self-check

            connectionSemaphore.releaseChannelLock(prevKey);
            releasePartitionKeyLock();

            throw new IllegalStateException("Trying to acquire partition lock concurrently. Please report.");
        }

        if (isDone()) {
            // may be cancelled while we acquired a lock
            releasePartitionKeyLock();
        }
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

    public ScramContext getScramContext() {
        return scramContext;
    }

    public void setScramContext(ScramContext scramContext) {
        this.scramContext = scramContext;
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
                ",\n\ttimeoutsHolder=" + TIMEOUTS_HOLDER_FIELD.get(this) + //
                ",\n\tinAuth=" + inAuth + //
                ",\n\ttouch=" + touch + //
                '}';
    }
}
