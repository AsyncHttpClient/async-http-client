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
package org.asynchttpclient.netty.channel;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.SimpleFutureListener;
import org.asynchttpclient.netty.future.StackTraceInspector;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non Blocking connect.
 */
public final class NettyConnectListener<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyConnectListener.class);

    private final NettyRequestSender requestSender;
    private final NettyResponseFuture<T> future;
    private final ChannelManager channelManager;
    private final ConnectionSemaphore connectionSemaphore;

    public NettyConnectListener(NettyResponseFuture<T> future, NettyRequestSender requestSender, ChannelManager channelManager, ConnectionSemaphore connectionSemaphore) {
        this.future = future;
        this.requestSender = requestSender;
        this.channelManager = channelManager;
        this.connectionSemaphore = connectionSemaphore;
    }

    private boolean futureIsAlreadyCompleted(Channel channel) {
        // Use isDone() (covers cancel + abort + done) rather than isCancelled() alone:
        // abort() and done() set isDone but not isCancelled, so a future that has been
        // aborted (e.g. by a request timeout) would otherwise slip past this check.
        if (future.isDone()) {
            Channels.silentlyCloseChannel(channel);
            return true;
        }
        return false;
    }

    private void writeRequest(Channel channel) {
        if (futureIsAlreadyCompleted(channel)) {
            return;
        }

        if (LOGGER.isDebugEnabled()) {
            HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
            LOGGER.debug("Using new Channel '{}' for '{}' to '{}'", channel, httpRequest.method(), httpRequest.uri());
        }

        Channels.setAttribute(channel, future);

        channelManager.registerOpenChannel(channel);
        requestSender.writeRequest(future, channel);
    }

    public void onSuccess(Channel channel, InetSocketAddress remoteAddress) {
        // takePartitionKeyLock() is a getAndSet(null): the future can no longer release this permit, so bind
        // it to the channel here, before anything below can abandon that channel. Everything downstream --
        // a failed or timed-out TLS handshake, a crashing AsyncHandler callback, a connect retry -- closes
        // the channel but cannot reach the token, so deferring this to the individual branches leaked the
        // permit permanently on every one of those paths (issue #2189). The HTTP/2 branches release it
        // earlier and leave this listener a no-op.
        final Object partitionKeyLock = connectionSemaphore != null ? future.takePartitionKeyLock() : null;
        final AtomicReference<Object> permit = new AtomicReference<>(partitionKeyLock);
        if (partitionKeyLock != null) {
            channel.closeFuture().addListener(f -> releasePermitOnce(permit));
        }

        Channels.setActiveToken(channel);

        if (futureIsAlreadyCompleted(channel)) {
            releasePermitOnce(permit);
            return;
        }

        TimeoutsHolder timeoutsHolder = future.getTimeoutsHolder();
        if (timeoutsHolder == null) {
            // The future is being terminated concurrently: cancelTimeouts() has nulled the
            // holder but the isDone flag may not yet be visible on this thread. Per JMM,
            // observing one volatile write does not require observing later ones, so the
            // futureIsAlreadyCompleted check above can pass while the holder is already null.
            // Drop this connection rather than NPE-ing on setResolvedRemoteAddress below.
            Channels.silentlyCloseChannel(channel);
            releasePermitOnce(permit);
            return;
        }

        // Publish the channel before the (possibly long) TLS handshake. Until this runs future.channel() is
        // null, and NettyRequestSender.abort only closes a non-null channel -- so a request timeout firing
        // mid-handshake could not close the socket, stranding it until handshakeTimeout (issue #2189).
        future.attachChannel(channel, false);

        Request request = future.getTargetRequest();
        Uri uri = request.getUri();
        // don't set a null resolved address - if the remoteAddress is null we keep
        // the previously scheduled (possibly unresolved) address to avoid NPEs in
        // timeout logging and keep useful diagnostic information
        if (remoteAddress != null) {
            timeoutsHolder.setResolvedRemoteAddress(remoteAddress);
            // In ROUND_ROBIN mode the connector may have failed over from the initially selected IP to a
            // later one; re-pin the pool/HTTP-2 partition key to the IP we actually connected to so reuse
            // stays correct. No-op in DEFAULT mode.
            future.repinRoundRobinAddress(remoteAddress.getAddress());
        }
        ProxyServer proxyServer = future.getProxyServer();

        // For HTTPS proxies, establish SSL connection to the proxy server first
        if (proxyServer != null && ProxyType.HTTPS.equals(proxyServer.getProxyType())) {
            SslHandler sslHandler;
            try {
                sslHandler = channelManager.addSslHandler(channel.pipeline(), 
                    Uri.create("https://" + proxyServer.getHost() + ":" + proxyServer.getSecuredPort()), 
                    null, false);
            } catch (Exception sslError) {
                onFailure(channel, sslError);
                return;
            }

            final AsyncHandler<?> asyncHandler = future.getAsyncHandler();

            try {
                asyncHandler.onTlsHandshakeAttempt();
            } catch (Exception e) {
                LOGGER.error("onTlsHandshakeAttempt crashed", e);
                onFailure(channel, e);
                return;
            }

            sslHandler.handshakeFuture().addListener(new SimpleFutureListener<Channel>() {
                @Override
                protected void onSuccess(Channel value) {
                    try {
                        asyncHandler.onTlsHandshakeSuccess(sslHandler.engine().getSession());
                    } catch (Exception e) {
                        LOGGER.error("onTlsHandshakeSuccess crashed", e);
                        NettyConnectListener.this.onFailure(channel, e);
                        return;
                    }
                    // After SSL handshake to proxy, continue with normal proxy request
                    writeRequest(channel);
                }

                @Override
                protected void onFailure(Throwable cause) {
                    try {
                        asyncHandler.onTlsHandshakeFailure(cause);
                    } catch (Exception e) {
                        LOGGER.error("onTlsHandshakeFailure crashed", e);
                        NettyConnectListener.this.onFailure(channel, e);
                        return;
                    }
                    NettyConnectListener.this.onFailure(channel, cause);
                }
            });

        // in case of proxy tunneling, we'll add the SslHandler later, after the CONNECT request
        } else if ((proxyServer == null || proxyServer.getProxyType().isSocks()) && uri.isSecured()) {
            SslHandler sslHandler;
            try {
                sslHandler = channelManager.addSslHandler(channel.pipeline(), uri, request.getVirtualHost(), proxyServer != null);
            } catch (Exception sslError) {
                onFailure(channel, sslError);
                return;
            }

            final AsyncHandler<?> asyncHandler = future.getAsyncHandler();

            try {
                asyncHandler.onTlsHandshakeAttempt();
            } catch (Exception e) {
                LOGGER.error("onTlsHandshakeAttempt crashed", e);
                onFailure(channel, e);
                return;
            }

            sslHandler.handshakeFuture().addListener(new SimpleFutureListener<Channel>() {
                @Override
                protected void onSuccess(Channel value) {
                    try {
                        asyncHandler.onTlsHandshakeSuccess(sslHandler.engine().getSession());
                    } catch (Exception e) {
                        LOGGER.error("onTlsHandshakeSuccess crashed", e);
                        NettyConnectListener.this.onFailure(channel, e);
                        return;
                    }
                    // Detect ALPN-negotiated protocol and upgrade pipeline to HTTP/2 if "h2" was selected.
                    // WebSocket is excluded: AsyncHttpClient does not implement RFC 8441 (WebSocket over
                    // HTTP/2), so a wss:// request must stay on HTTP/1.1. The WebSocket SSL engine advertises
                    // only http/1.1 (DefaultSslEngineFactory), so a conformant server will not select h2 here;
                    // this guard is the backstop for a custom SslEngineFactory that still advertises h2.
                    // Without it, the WebSocket handshake would be written as a plain HTTP/2 request and the
                    // broken connection pooled in the H2 registry, mis-routing later wss:// requests. See #2160.
                    String alpnProtocol = sslHandler.applicationProtocol();
                    boolean http2Negotiated = ApplicationProtocolNames.HTTP_2.equals(alpnProtocol);
                    if (http2Negotiated && uri.isWebSocket()) {
                        LOGGER.warn("Server negotiated HTTP/2 for WebSocket request to {}; WebSocket over HTTP/2 "
                                + "(RFC 8441) is not supported — continuing on HTTP/1.1", uri);
                    }
                    if (http2Negotiated && !uri.isWebSocket()) {
                        channelManager.upgradePipelineToHttp2(channel.pipeline());
                        registerHttp2AndManageSemaphore(channel, permit);
                    }
                    writeRequest(channel);
                }

                @Override
                protected void onFailure(Throwable cause) {
                    try {
                        asyncHandler.onTlsHandshakeFailure(cause);
                    } catch (Exception e) {
                        LOGGER.error("onTlsHandshakeFailure crashed", e);
                        NettyConnectListener.this.onFailure(channel, e);
                        return;
                    }
                    NettyConnectListener.this.onFailure(channel, cause);
                }
            });

        } else {
            // h2c (cleartext HTTP/2 prior knowledge): upgrade to HTTP/2 without TLS. WebSocket (ws://) is
            // excluded for the same RFC 8441 reason as the TLS path above — it stays on HTTP/1.1.
            if (!uri.isSecured() && channelManager.isHttp2CleartextEnabled() && !uri.isWebSocket()) {
                channelManager.upgradePipelineToHttp2(channel.pipeline());
                registerHttp2AndManageSemaphore(channel, permit);
            }
            writeRequest(channel);
        }
    }

    /**
     * Returns the per-host permit this connection holds, at most once. The channel closeFuture, the
     * early-exit paths and the HTTP/2 immediate release all race to call this; a double release would
     * push the semaphore above {@code maxConnectionsPerHost}, and an unmatched one can prematurely
     * prune a live entry from the per-host map.
     */
    private void releasePermitOnce(AtomicReference<Object> permit) {
        Object partitionKeyLock = permit.getAndSet(null);
        if (partitionKeyLock != null && connectionSemaphore != null) {
            connectionSemaphore.releaseChannelLock(partitionKeyLock);
        }
    }

    /**
     * Registers the HTTP/2 connection in the channel manager's H2 registry, then decides how long to hold
     * the per-host connection permit.
     *
     * <p>{@link org.asynchttpclient.LoadBalance#DEFAULT} multiplexes a host onto a single connection, so the
     * permit is released immediately — holding it would needlessly block concurrent requests that all share
     * that one connection. {@link org.asynchttpclient.LoadBalance#ROUND_ROBIN} instead keeps one connection
     * per resolved IP, so the per-host permit is what bounds the number of live connections: it is held for
     * the connection's lifetime (as HTTP/1.1 does). Once {@code maxConnectionsPerHost} connections are live,
     * a further request fails to acquire a permit and multiplexes onto a sibling-IP connection via
     * {@link ChannelManager#pollHttp2SiblingConnection(Object)} rather than opening another (issue #2214).
     */
    private void registerHttp2AndManageSemaphore(Channel channel, AtomicReference<Object> permit) {
        // Register under the future's partition key so the H2 connection is found by the same key the
        // pool is polled with, including the IP-aware key used by LoadBalance.ROUND_ROBIN. Read the key
        // once: it is volatile (repinned on IP failover) and both uses below must agree.
        Object partitionKey = future.getPartitionKey();
        channelManager.registerHttp2Connection(partitionKey, channel);
        if (partitionKey instanceof RoundRobinPartitionKey) {
            // The permit must be freed as soon as the connection stops serving new requests: on close, or
            // at drain start after GOAWAY (issue #2214). The closeFuture listener installed in onSuccess
            // covers close; this adds the drain hook. Both funnel through releasePermitOnce.
            Http2ConnectionState state = channel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();
            if (state != null) {
                state.setPermitRelease(() -> releasePermitOnce(permit));
            }
        } else {
            releasePermitOnce(permit);
        }
    }

    public void onFailure(Channel channel, Throwable cause) {

        // beware, channel can be null
        Channels.silentlyCloseChannel(channel);

        boolean canRetry = future.incrementRetryAndCheck();
        LOGGER.debug("Trying to recover from failing to connect channel {} with a retry value of {} ", channel, canRetry);
        if (canRetry//
                && cause != null // FIXME when can we have a null cause?
                && (future.getChannelState() != ChannelState.NEW || StackTraceInspector.recoverOnNettyDisconnectException(cause))) {

            if (requestSender.retry(future)) {
                return;
            }
        }

        LOGGER.debug("Failed to recover from connect exception: {} with channel {}", cause, channel);

        String message = cause.getMessage() != null ? cause.getMessage() : future.getUri().getBaseUrl();
        ConnectException e = new ConnectException(message);
        e.initCause(cause);
        future.abort(e);
    }
}
