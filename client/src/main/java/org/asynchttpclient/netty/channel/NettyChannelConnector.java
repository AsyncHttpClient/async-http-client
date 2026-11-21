/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.netty.SimpleChannelFutureListener;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public class NettyChannelConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyChannelConnector.class);

    private static final AtomicIntegerFieldUpdater<NettyChannelConnector> I_UPDATER = AtomicIntegerFieldUpdater
            .newUpdater(NettyChannelConnector.class, "i");

    private static final AttemptCount NO_COUNT = new AttemptCount(null, null);

    private final AsyncHandler<?> asyncHandler;
    private final InetSocketAddress localAddress;
    // A private copy in LoadBalance.LEAST_CONNECTIONS mode, where the untried tail is reordered before each attempt.
    private final List<InetSocketAddress> remoteAddresses;
    private final AsyncHttpClientState clientState;
    // Notified with each remote address whose TCP connect attempt fails, or null when no caller cares.
    // Used to put a failed IP in a short cooldown so new connections route around it; see FailedIpCooldownHolder.
    private final Consumer<InetSocketAddress> connectFailureListener;
    // Set only in LoadBalance.LEAST_CONNECTIONS mode.
    private final @Nullable LeastConnectionsAddressSelector leastConnections;
    // Keys the failed-IP cooldown. Required when leastConnections is set.
    private final @Nullable String host;
    private volatile int i;

    public NettyChannelConnector(InetAddress localAddress, List<InetSocketAddress> remoteAddresses, AsyncHandler<?> asyncHandler, AsyncHttpClientState clientState) {
        this(localAddress, remoteAddresses, asyncHandler, clientState, null);
    }

    public NettyChannelConnector(InetAddress localAddress, List<InetSocketAddress> remoteAddresses, AsyncHandler<?> asyncHandler, AsyncHttpClientState clientState,
                                 Consumer<InetSocketAddress> connectFailureListener) {
        this(localAddress, remoteAddresses, asyncHandler, clientState, connectFailureListener, null, null);
    }

    // A non-null leastConnections picks and counts the address of every attempt, and requires host.
    public NettyChannelConnector(InetAddress localAddress, List<InetSocketAddress> remoteAddresses, AsyncHandler<?> asyncHandler, AsyncHttpClientState clientState,
                                 Consumer<InetSocketAddress> connectFailureListener, @Nullable LeastConnectionsAddressSelector leastConnections,
                                 @Nullable String host) {
        this.localAddress = localAddress != null ? new InetSocketAddress(localAddress, 0) : null;
        this.remoteAddresses = leastConnections != null ? new ArrayList<>(remoteAddresses) : remoteAddresses;
        this.asyncHandler = asyncHandler;
        this.clientState = clientState;
        this.connectFailureListener = connectFailureListener;
        this.leastConnections = leastConnections;
        this.host = leastConnections != null ? requireNonNull(host, "host") : host;
    }

    private boolean pickNextRemoteAddress() {
        I_UPDATER.incrementAndGet(this);
        return i < remoteAddresses.size();
    }

    public void connect(final Bootstrap bootstrap, final NettyConnectListener<?> connectListener) {
        final AttemptCount releaseCount = countAttempt();
        boolean started = false;
        try {
            started = attempt(bootstrap, connectListener, releaseCount);
        } finally {
            // Covers an Error too, which the catch blocks in attempt() let through.
            if (!started) {
                releaseCount.run();
            }
        }
    }

    // Returns true once the connect has started; the channel then owns the release. Every other exit releases the
    // count before reporting the failure, so a retry triggered by that report does not see this attempt.
    private boolean attempt(final Bootstrap bootstrap, final NettyConnectListener<?> connectListener, final AttemptCount releaseCount) {
        final InetSocketAddress remoteAddress = remoteAddresses.get(i);

        try {
            asyncHandler.onTcpConnectAttempt(remoteAddress);
        } catch (Exception e) {
            LOGGER.error("onTcpConnectAttempt crashed", e);
            releaseCount.run();
            connectListener.onFailure(null, e);
            return false;
        }

        try {
            connect0(bootstrap, connectListener, remoteAddress, releaseCount);
            return true;
        } catch (RejectedExecutionException e) {
            releaseCount.run();
            if (clientState.isClosed()) {
                LOGGER.info("Connect crash but engine is shutting down");
            } else {
                connectListener.onFailure(null, e);
            }
            return false;
        }
    }

    // Picks the address for this attempt, moves it to index i and counts it.
    private AttemptCount countAttempt() {
        LeastConnectionsAddressSelector selector = leastConnections;
        if (selector == null) {
            return NO_COUNT;
        }
        return new AttemptCount(selector, selector.selectAndAcquire(host, remoteAddresses, i));
    }

    private void connect0(Bootstrap bootstrap, final NettyConnectListener<?> connectListener, InetSocketAddress remoteAddress,
                          AttemptCount releaseCount) {
        ChannelFuture whenConnected = bootstrap.connect(remoteAddress, localAddress);
        if (releaseCount != NO_COUNT) {
            whenConnected.channel().closeFuture().addListener(releaseCount);
        }
        whenConnected
                .addListener(new SimpleChannelFutureListener() {
                    @Override
                    public void onSuccess(Channel channel) {
                        try {
                            asyncHandler.onTcpConnectSuccess(remoteAddress, channel);
                        } catch (Exception e) {
                            LOGGER.error("onTcpConnectSuccess crashed", e);
                            connectListener.onFailure(channel, e);
                            return;
                        }
                        connectListener.onSuccess(channel, remoteAddress);
                    }

                    @Override
                    public void onFailure(Channel channel, Throwable t) {
                        releaseCount.run();
                        if (connectFailureListener != null) {
                            // Record the failed IP before failing over so the cooldown can route around it briefly.
                            connectFailureListener.accept(remoteAddress);
                        }
                        try {
                            asyncHandler.onTcpConnectFailure(remoteAddress, t);
                        } catch (Exception e) {
                            LOGGER.error("onTcpConnectFailure crashed", e);
                            connectListener.onFailure(channel, e);
                            return;
                        }
                        boolean retry = pickNextRemoteAddress();
                        if (retry) {
                            connect(bootstrap, connectListener);
                        } else {
                            connectListener.onFailure(channel, annotateConnectException(t, remoteAddress));
                        }
                    }
                });
    }

    private Throwable annotateConnectException(Throwable t, InetSocketAddress remoteAddress) {
        if (t instanceof ConnectException) {
            return t;  // Already has proper type; preserve for retry predicates
        }
        String address = remoteAddress.toString();
        String message = t.getMessage();
        ConnectException annotated = new ConnectException((message != null ? message : t.getClass().getSimpleName()) + ": " + address);
        annotated.initCause(t);
        return annotated;
    }

    /**
     * Releases one attempt's count, at most once. A failed connect usually reports through both the failure listener
     * and the channel's close future. A channel that never registered never closes, so only the failure listener
     * reports it.
     */
    private static final class AttemptCount extends AtomicBoolean implements Runnable, ChannelFutureListener {

        private final @Nullable LeastConnectionsAddressSelector selector;
        private final @Nullable InetSocketAddress address;

        AttemptCount(@Nullable LeastConnectionsAddressSelector selector, @Nullable InetSocketAddress address) {
            this.selector = selector;
            this.address = address;
        }

        @Override
        public void run() {
            if (selector != null && compareAndSet(false, true)) {
                selector.release(address);
            }
        }

        @Override
        public void operationComplete(ChannelFuture closed) {
            run();
        }
    }
}
