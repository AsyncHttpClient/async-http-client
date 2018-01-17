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

import io.netty.channel.Channel;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.channel.ConnectionSemaphore;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;
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
  private volatile int isDone = 0;
  private volatile int isCancelled = 0;
  private volatile int inAuth = 0;
  private volatile int inProxyAuth = 0;
  private volatile int statusReceived = 0;
  @SuppressWarnings("unused")
  private volatile int contentProcessed = 0;
  @SuppressWarnings("unused")
  private volatile int onThrowableCalled = 0;
  @SuppressWarnings("unused")
  private volatile TimeoutsHolder timeoutsHolder;
  // partition key, when != null used to release lock in ChannelManager
  private volatile Object partitionKeyLock;
  // volatile where we need CAS ops
  private volatile int redirectCount = 0;
  private volatile int currentRetry = 0;
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

  public NettyResponseFuture(Request originalRequest, //
                             AsyncHandler<V> asyncHandler, //
                             NettyRequest nettyRequest, //
                             int maxRetry, //
                             ChannelPoolPartitioning connectionPoolPartitioning, //
                             ConnectionSemaphore connectionSemaphore, //
                             ProxyServer proxyServer) {

    this.asyncHandler = asyncHandler;
    this.targetRequest = currentRequest = originalRequest;
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

    if (IS_CANCELLED_FIELD.getAndSet(this, 1) != 0)
      return false;

    // cancel could happen before channel was attached
    if (channel != null) {
      Channels.setDiscard(channel);
      Channels.silentlyCloseChannel(channel);
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
    this.channel = null;
    this.reuseChannel = false;
    return IS_DONE_FIELD.getAndSet(this, 1) != 0 || isCancelled != 0;
  }

  public final void done() {

    if (terminateAndExit())
      return;

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

  public final void abort(final Throwable t) {

    if (terminateAndExit())
      return;

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

  public final Request getTargetRequest() {
    return targetRequest;
  }

  public void setTargetRequest(Request targetRequest) {
    this.targetRequest = targetRequest;
  }

  public final Request getCurrentRequest() {
    return currentRequest;
  }

  public void setCurrentRequest(Request currentRequest) {
    this.currentRequest = currentRequest;
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

  public void setAsyncHandler(AsyncHandler<V> asyncHandler) {
    this.asyncHandler = asyncHandler;
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

  public TimeoutsHolder getTimeoutsHolder() {
    return TIMEOUTS_HOLDER_FIELD.get(this);
  }

  public void setTimeoutsHolder(TimeoutsHolder timeoutsHolder) {
    TIMEOUTS_HOLDER_FIELD.set(this, timeoutsHolder);
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
    this.streamAlreadyConsumed = streamConsumed;
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
    return !isDone() && !(Channels.isChannelActive(channel) && !getUri().getScheme().equalsIgnoreCase("https"))
            && inAuth == 0 && inProxyAuth == 0;
  }

  public long getStart() {
    return start;
  }

  public Object getPartitionKey() {
    return connectionPoolPartitioning.getPartitionKey(targetRequest.getUri(), targetRequest.getVirtualHost(),
            proxyServer);
  }

  public void acquirePartitionLockLazily() throws IOException {
    if (connectionSemaphore == null || partitionKeyLock != null) {
      return;
    }

    Object partitionKey = getPartitionKey();
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
            ",\n\tstatusReceived=" + statusReceived + //
            ",\n\ttouch=" + touch + //
            '}';
  }
}
