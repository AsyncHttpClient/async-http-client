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
package org.asynchttpclient.netty.request;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AsciiString;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.LoadBalance;
import org.asynchttpclient.exception.FilterException;
import org.asynchttpclient.exception.PoolAlreadyClosedException;
import org.asynchttpclient.exception.RemotelyClosedException;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.handler.TransferCompletionHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.SimpleFutureListener;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.channel.ConnectionSemaphore;
import org.asynchttpclient.netty.channel.Http2ConnectionState;
import org.asynchttpclient.netty.channel.DefaultConnectionSemaphoreFactory;
import org.asynchttpclient.netty.channel.FailedIpCooldownHolder;
import org.asynchttpclient.netty.channel.NettyChannelConnector;
import org.asynchttpclient.netty.channel.NettyConnectListener;
import org.asynchttpclient.netty.channel.RoundRobinAddressSelector;
import org.asynchttpclient.netty.channel.RoundRobinPartitionKey;
import org.asynchttpclient.netty.handler.Http2ContentDecompressor;
import org.asynchttpclient.netty.request.body.NettyBody;
import org.asynchttpclient.netty.request.body.NettyDirectBody;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.resolver.RequestHostnameResolver;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.asynchttpclient.util.AuthenticatorUtils.perConnectionAuthorizationHeader;
import static org.asynchttpclient.util.AuthenticatorUtils.perConnectionProxyAuthorizationHeader;
import static org.asynchttpclient.util.HttpConstants.Methods.CONNECT;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.asynchttpclient.util.HttpUtils.hostHeader;
import static org.asynchttpclient.util.MiscUtils.getCause;
import static org.asynchttpclient.util.ProxyUtils.getProxyServer;

public final class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

    private final AsyncHttpClientConfig config;
    private final ChannelManager channelManager;
    private final ConnectionSemaphore connectionSemaphore;
    private final Timer nettyTimer;
    private final AsyncHttpClientState clientState;
    private final NettyRequestFactory requestFactory;
    private final RoundRobinAddressSelector rrSelector = new RoundRobinAddressSelector();
    // Deprioritizes a recently-failed IP when ordering a direct connection's resolved addresses, in any
    // LoadBalance mode. Null when the failed-IP cooldown is disabled; call sites gate on ipCooldown != null.
    private final FailedIpCooldownHolder ipCooldown;

    public NettyRequestSender(AsyncHttpClientConfig config, ChannelManager channelManager, Timer nettyTimer, AsyncHttpClientState clientState) {
        this.config = config;
        this.channelManager = channelManager;
        connectionSemaphore = config.getConnectionSemaphoreFactory() == null
                ? new DefaultConnectionSemaphoreFactory().newConnectionSemaphore(config)
                : config.getConnectionSemaphoreFactory().newConnectionSemaphore(config);
        this.nettyTimer = nettyTimer;
        this.clientState = clientState;
        requestFactory = new NettyRequestFactory(config);
        // Guard the period against a custom AsyncHttpClientConfig that enables the cooldown but returns a
        // null period: leave the cooldown off rather than NPE while constructing the client.
        Duration cooldownPeriod = config.getFailedIpCooldownPeriod();
        ipCooldown = config.isFailedIpCooldownEnabled() && cooldownPeriod != null
                ? new FailedIpCooldownHolder(cooldownPeriod.toNanos(), System::nanoTime)
                : null;
    }

    // needConnect returns true if the request is secure/websocket and a HTTP proxy is set
    private boolean needConnect(final Request request, final ProxyServer proxyServer) {
        return proxyServer != null
                && proxyServer.getProxyType().isHttp()
                && (request.getUri().isSecured() || request.getUri().isWebSocket());
    }

    public <T> ListenableFuture<T> sendRequest(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> future) {
        if (isClosed()) {
            throw new IllegalStateException("Closed");
        }

        validateWebSocketRequest(request, asyncHandler);
        ProxyServer proxyServer = getProxyServer(config, request);

        // Round-robin across the host's resolved IPs: resolve first, pick the next IP, then proceed.
        // Re-evaluated when the target base changes (e.g. a cross-host redirect, or a same-host
        // scheme/port change such as an HTTP-to-HTTPS upgrade — the cached addresses and partition key
        // carry the old port/scheme); same-base retries keep their pick. See LoadBalance.ROUND_ROBIN.
        if (config.getLoadBalance() == LoadBalance.ROUND_ROBIN) {
            boolean overrideMatchesBase = future != null && future.getRoundRobinBaseUri() != null
                    && request.getUri().isSameBase(future.getRoundRobinBaseUri());
            if (isDirectConnection(request, proxyServer) && !overrideMatchesBase) {
                return sendRequestRoundRobin(request, asyncHandler, future, proxyServer);
            }
            if (!overrideMatchesBase && future != null && future.getRoundRobinBaseUri() != null) {
                // a reused future carries round-robin state for a different base (cross-host redirect, or
                // a same-host scheme/port change, to a target that isn't eligible) — drop it so we don't
                // connect to the previous base's IPs/port
                future.clearRoundRobinOverrides();
            }
        }

        // WebSockets use connect tunneling to work with proxies
        if (needConnect(request, proxyServer) && !isConnectAlreadyDone(request, future)) {
            // Proxy with HTTPS or WebSocket: CONNECT for sure
            if (future != null && future.isConnectAllowed()) {
                // Perform CONNECT
                return sendRequestWithCertainForceConnect(request, asyncHandler, future, proxyServer, true);
            } else {
                // CONNECT will depend on if we can pool or connection or if we have to open a new one
                return sendRequestThroughProxy(request, asyncHandler, future, proxyServer);
            }
        } else {
            // no CONNECT for sure
            return sendRequestWithCertainForceConnect(request, asyncHandler, future, proxyServer, false);
        }
    }

    // Whether the request opens a direct connection to the target host, i.e. the connector targets the
    // host's resolved IPs (keyed in DNS/cooldown state by uri.getHost()). Excluded: an explicit address
    // (bypasses resolution), and any proxied host — HTTP or SOCKS — since the socket is established to the
    // proxy rather than to the resolved target IPs. A bypassed proxy (isIgnoredForHost) still connects
    // directly. Gates both round-robin rotation and the failed-IP cooldown so both stay keyed on the
    // host whose IPs are actually being connected to.
    private boolean isDirectConnection(Request request, ProxyServer proxyServer) {
        if (request.getAddress() != null || needConnect(request, proxyServer)) {
            return false;
        }
        Uri uri = request.getUri();
        return proxyServer == null || proxyServer.isIgnoredForHost(uri.getHost());
    }

    /**
     * Round-robin dispatch: resolve the host first, pick the next IP (rotating the address list so the
     * connector targets it while keeping the others for failover), pin connection reuse to that IP via
     * an IP-aware partition key, then run the normal reuse-or-connect logic.
     */
    private <T> ListenableFuture<T> sendRequestRoundRobin(Request request, AsyncHandler<T> asyncHandler, NettyResponseFuture<T> future,
                                                          ProxyServer proxyServer) {
        NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, false);
        Uri uri = request.getUri();
        String host = uri.getHost();

        // Round-robin resolves up front — before the pool check and before the per-host semaphore — so
        // every eligible request resolves first, even one that immediately reuses a pooled connection and
        // even a single-IP host. With a caching resolver this is cheap. Pass scheduleTimeout=false: the
        // reuse-or-connect path reached from dispatchResolved schedules the request timeout exactly once
        // (sendRequestWithOpenChannel for a pooled hit, or the round-robin branch of sendRequestWithNewChannel
        // for a new connection), rather than scheduling it here and then cancelling it on a pooled hit.
        resolveAddresses(request, proxyServer, newFuture, asyncHandler, false).addListener(new SimpleFutureListener<List<InetSocketAddress>>() {

            @Override
            protected void onSuccess(List<InetSocketAddress> addresses) {
                List<InetSocketAddress> ordered = addresses;
                if (addresses.size() > 1) {
                    ordered = rrSelector.rotate(host, addresses);
                    // Apply the failed-IP cooldown on top of the rotation, before pinning the IP-aware
                    // partition key below, so the pool pin and the chosen IP avoid a recently-dead address.
                    if (ipCooldown != null) {
                        ordered = ipCooldown.reorder(host, ordered);
                    }
                    InetAddress chosen = ordered.get(0).getAddress();
                    // Derive the base key from the live request, not newFuture.basePartitionKey(): a filter
                    // replay can reuse this future without updating its targetRequest (only redirects keep it
                    // in sync), so the memoized base key may lag a host-rewriting replay and would mis-key the
                    // override to the previous host.
                    Object baseKey = request.getChannelPoolPartitioning().getPartitionKey(uri, request.getVirtualHost(), proxyServer);
                    newFuture.setPartitionKeyOverride(new RoundRobinPartitionKey(baseKey, chosen));
                } else {
                    // single-IP host (e.g. a redirect from a multi-IP host onto this reused future): clear
                    // any stale IP-aware key so we don't poll/pool/lock under the previous host's IP
                    newFuture.setPartitionKeyOverride(null);
                }
                // Always feed the resolved addresses back so the new-channel path doesn't resolve twice;
                // recording the base URI lets sendRequest skip re-rotation on same-base retries while
                // re-resolving when the scheme/host/port changes (e.g. an HTTP-to-HTTPS redirect).
                newFuture.setRoundRobinAddresses(ordered);
                newFuture.setRoundRobinBaseUri(uri);
                dispatchResolved(request, proxyServer, newFuture, asyncHandler);
            }

            @Override
            protected void onFailure(Throwable cause) {
                abort(null, newFuture, getCause(cause));
            }
        });

        return newFuture;
    }

    // Reuse-or-connect once the round-robin IP has been chosen and recorded on the future.
    private <T> void dispatchResolved(Request request, ProxyServer proxyServer, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler) {
        Channel channel = getOpenChannel(future, request, proxyServer, asyncHandler);
        if (Channels.isChannelActive(channel)) {
            sendRequestWithOpenChannel(future, asyncHandler, channel);
        } else {
            sendRequestWithNewChannel(request, proxyServer, future, asyncHandler);
        }
    }

    private static boolean isConnectAlreadyDone(Request request, NettyResponseFuture<?> future) {
        return future != null
                // If the channel can't be reused or closed, a CONNECT is still required
                && future.isReuseChannel() && Channels.isChannelActive(future.channel())
                && future.getNettyRequest() != null
                && future.getNettyRequest().getHttpRequest().method() == HttpMethod.CONNECT
                && !request.getMethod().equals(CONNECT);
    }

    /**
     * We know for sure if we have to force to connect or not, so we can build the
     * HttpRequest right away This reduces the probability of having a pooled
     * channel closed by the server by the time we build the request
     */
    private <T> ListenableFuture<T> sendRequestWithCertainForceConnect(Request request, AsyncHandler<T> asyncHandler, NettyResponseFuture<T> future,
                                                                       ProxyServer proxyServer, boolean performConnectRequest) {
        Channel channel = getOpenChannel(future, request, proxyServer, asyncHandler);
        if (Channels.isChannelActive(channel)) {
            NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future,
                    proxyServer, performConnectRequest);
            return sendRequestWithOpenChannel(newFuture, asyncHandler, channel);
        } else {
            // A new channel is not expected when performConnectRequest is false. We need to
            // revisit the condition of sending
            // the CONNECT request to the new channel.
            NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future,
                    proxyServer, needConnect(request, proxyServer));
            return sendRequestWithNewChannel(request, proxyServer, newFuture, asyncHandler);
        }
    }

    /**
     * Using CONNECT depends on whether we can fetch a valid channel or not Loop
     * until we get a valid channel from the pool, and it's still valid once the
     * request is built @
     */
    private <T> ListenableFuture<T> sendRequestThroughProxy(Request request,
                                                            AsyncHandler<T> asyncHandler,
                                                            NettyResponseFuture<T> future,
                                                            ProxyServer proxyServer) {

        NettyResponseFuture<T> newFuture = null;
        for (int i = 0; i < 3; i++) {
            Channel channel = getOpenChannel(future, request, proxyServer, asyncHandler);
            if (channel == null) {
                // pool is empty
                break;
            }

            if (newFuture == null) {
                newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, false);
            }

            if (Channels.isChannelActive(channel)) {
                // if the channel is still active, we can use it,
                // otherwise, channel was closed by the time we computed the request, try again
                return sendRequestWithOpenChannel(newFuture, asyncHandler, channel);
            }
        }

        // couldn't poll an active channel
        newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, true);
        return sendRequestWithNewChannel(request, proxyServer, newFuture, asyncHandler);
    }

    private <T> NettyResponseFuture<T> newNettyRequestAndResponseFuture(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> originalFuture,
                                                                        ProxyServer proxy, boolean performConnectRequest) {
        Realm realm;
        if (originalFuture != null) {
            realm = originalFuture.getRealm();
        } else {
            realm = request.getRealm();
            if (realm == null) {
                realm = config.getRealm();
            }
        }

        Realm proxyRealm = null;
        if (originalFuture != null) {
            proxyRealm = originalFuture.getProxyRealm();
        } else if (proxy != null) {
            proxyRealm = proxy.getRealm();
        }

        NettyRequest nettyRequest = requestFactory.newNettyRequest(request, performConnectRequest, proxy, realm, proxyRealm);
        if (originalFuture == null) {
            NettyResponseFuture<T> future = newNettyResponseFuture(request, asyncHandler, nettyRequest, proxy);
            future.setRealm(realm);
            future.setProxyRealm(proxyRealm);
            return future;
        } else {
            originalFuture.setNettyRequest(nettyRequest);
            originalFuture.setCurrentRequest(request);
            return originalFuture;
        }
    }

    private Channel getOpenChannel(NettyResponseFuture<?> future, Request request, ProxyServer proxyServer, AsyncHandler<?> asyncHandler) {
        if (future != null && future.isReuseChannel() && Channels.isChannelActive(future.channel())) {
            return future.channel();
        } else {
            return pollPooledChannel(future, request, proxyServer, asyncHandler);
        }
    }

    private <T> ListenableFuture<T> sendRequestWithOpenChannel(NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler, Channel channel) {
        try {
            asyncHandler.onConnectionPooled(channel);
        } catch (Exception e) {
            LOGGER.error("onConnectionPooled crashed", e);
            abort(channel, future, e);
            return future;
        }

        SocketAddress channelRemoteAddress = channel.remoteAddress();
        if (channelRemoteAddress != null) {
            // otherwise, bad luck, the channel was closed, see bellow
            scheduleRequestTimeout(future, (InetSocketAddress) channelRemoteAddress);
        }

        future.setChannelState(ChannelState.POOLED);
        future.attachChannel(channel, false);

        if (LOGGER.isDebugEnabled()) {
            HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
            LOGGER.debug("Using open Channel {} for {} '{}'", channel, httpRequest.method(), httpRequest.uri());
        }

        // channelInactive might be called between isChannelValid and writeRequest
        // so if we don't store the Future now, channelInactive won't perform
        // handleUnexpectedClosedChannel.
        // For HTTP/2, skip this: the parent connection multiplexes many concurrent requests, so a
        // single per-request Future on the parent channel is meaningless — each request's Future is
        // stored on its own stream child channel in openHttp2Stream(). The parent also has no
        // AsyncHttpClientHandler after the H2 upgrade, so nothing reads this attribute anyway.
        if (!ChannelManager.isHttp2(channel)) {
            Channels.setAttribute(channel, future);
        }

        if (Channels.isChannelActive(channel)) {
            writeRequest(future, channel);
        } else {
            // bad luck, the channel was closed in-between
            // there's a very good chance onClose was already notified but the
            // future wasn't already registered
            handleUnexpectedClosedChannel(channel, future);
        }

        return future;
    }

    private <T> ListenableFuture<T> sendRequestWithNewChannel(Request request, ProxyServer proxy, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler) {
        // some headers are only set when performing the first request
        HttpRequest nettyRequest = future.getNettyRequest().getHttpRequest();
        HttpHeaders headers = nettyRequest.headers();
        if (proxy != null && proxy.getCustomHeaders() != null) {
            HttpHeaders customHeaders = proxy.getCustomHeaders().apply(request);
            if (customHeaders != null) {
                headers.add(customHeaders);
            }
        }
        Realm realm = future.getRealm();
        Realm proxyRealm = future.getProxyRealm();
        // On the tunnel path this is the CONNECT request, sent to the proxy in the clear before the TLS
        // tunnel exists. Preemptive NTLM/Kerberos/SPNEGO realms attach their header here rather than in
        // the factory, so skip it on CONNECT to keep the origin credentials off the plaintext hop. They
        // travel on the tunneled request once the tunnel is up.
        if (nettyRequest.method() != HttpMethod.CONNECT) {
            requestFactory.addAuthorizationHeader(headers, perConnectionAuthorizationHeader(request, proxy, realm));
        }
        requestFactory.setProxyAuthorizationHeader(headers, perConnectionProxyAuthorizationHeader(request, proxyRealm));

        future.setInAuth(realm != null && realm.isUsePreemptiveAuth()
                && realm.getScheme() != AuthScheme.NTLM
                && realm.getScheme() != AuthScheme.SCRAM_SHA_256);
        future.setInProxyAuth(proxyRealm != null && proxyRealm.isUsePreemptiveAuth()
                && proxyRealm.getScheme() != AuthScheme.NTLM
                && proxyRealm.getScheme() != AuthScheme.SCRAM_SHA_256);

        try {
            if (!channelManager.isOpen()) {
                throw PoolAlreadyClosedException.INSTANCE;
            }

            // Do not throw an exception when we need an extra connection for a
            // redirect.
            try {
                // On the event loop (a redirect / 401 / 407 / retry replay re-enters sendRequest here),
                // acquire the connection permit WITHOUT blocking: parking the loop for
                // acquireFreeChannelTimeout would stall every other connection it serves (and the permit
                // may be released only by a task queued on this same loop). Off the loop — the initial
                // execute() on the caller thread — keep the configured blocking wait.
                future.acquirePartitionLockLazily(isOnEventLoop());
            } catch (IOException semaphoreException) {
                // The per-host permit is exhausted, but a sibling request may be establishing an HTTP/2
                // connection to this origin we can multiplex onto (stream reuse needs no permit). Reuse one
                // if it exists; otherwise wait for one WITHOUT blocking the caller thread. Returns the
                // pending future when handled; null means we should fail with the permit exception.
                if (config.isHttp2Enabled()) {
                    ListenableFuture<T> handled = reuseOrDeferHttp2Connection(request, proxy, future, asyncHandler, semaphoreException);
                    if (handled != null) {
                        return handled;
                    }
                }
                throw semaphoreException;
            }
        } catch (Throwable t) {
            abort(null, future, getCause(t));
            // exit and don't try to resolve address
            return future;
        }

        // In round-robin mode the addresses were already resolved (and rotated) before polling the pool,
        // so reuse them directly instead of resolving a second time. The up-front resolve deliberately did
        // NOT schedule the request timeout (see sendRequestRoundRobin), so schedule it here — once — for this
        // new-connection path, before connecting.
        List<InetSocketAddress> roundRobinAddresses = future.getRoundRobinAddresses();
        if (roundRobinAddresses != null) {
            // A misbehaving custom NameResolver can complete resolution successfully with an empty list; fail
            // fast with a clear cause instead of throwing IndexOutOfBoundsException on get(0) below, which the
            // resolve listener would swallow and leave the request hanging with no timeout scheduled.
            if (roundRobinAddresses.isEmpty()) {
                abort(null, future, new UnknownHostException("No addresses resolved for " + request.getUri().getHost()));
                return future;
            }
            scheduleRequestTimeout(future, roundRobinAddresses.get(0));
            connectWithAddresses(request, proxy, future, asyncHandler, roundRobinAddresses);
            return future;
        }

        resolveAddresses(request, proxy, future, asyncHandler, true).addListener(new SimpleFutureListener<List<InetSocketAddress>>() {

            @Override
            protected void onSuccess(List<InetSocketAddress> addresses) {
                List<InetSocketAddress> ordered = addresses;
                // Apply the failed-IP cooldown to direct connections regardless of LoadBalance mode, so a
                // recently-failed IP is deprioritized on the next new connection. Skipped for the
                // round-robin reuse branch above (those addresses are already cooldown-ordered) and for
                // proxied/explicit-address requests (the resolved addresses aren't the target host's IPs).
                if (ipCooldown != null && addresses.size() > 1 && isDirectConnection(request, proxy)) {
                    ordered = ipCooldown.reorder(request.getUri().getHost(), addresses);
                }
                connectWithAddresses(request, proxy, future, asyncHandler, ordered);
            }

            @Override
            protected void onFailure(Throwable cause) {
                abort(null, future, getCause(cause));
            }
        });

        return future;
    }

    private <T> void connectWithAddresses(Request request, ProxyServer proxy, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler,
                                          List<InetSocketAddress> addresses) {
        NettyConnectListener<T> connectListener = new NettyConnectListener<>(future, NettyRequestSender.this, channelManager, connectionSemaphore);
        // Feed TCP connect failures back so the cooldown deprioritizes a dead IP for a short window instead
        // of the next new connection re-targeting it (and burning another connectTimeout). Applied to direct
        // connections in any LoadBalance mode; the host key matches the one reorder() ordered under.
        Consumer<InetSocketAddress> connectFailureListener = null;
        if (ipCooldown != null && isDirectConnection(request, proxy)) {
            String host = request.getUri().getHost();
            connectFailureListener = address -> ipCooldown.markFailed(host, address);
        }
        NettyChannelConnector connector = new NettyChannelConnector(request.getLocalAddress(), addresses, asyncHandler, clientState, connectFailureListener);
        if (!future.isDone()) {
            // Do not throw an exception when we need an extra connection for a redirect
            // FIXME why? This violate the max connection per host handling, right?
            channelManager.getBootstrap(request.getUri(), request.getNameResolver(), proxy).addListener((Future<Bootstrap> whenBootstrap) -> {
                if (whenBootstrap.isSuccess()) {
                    connector.connect(whenBootstrap.get(), connectListener);
                } else {
                    abort(null, future, whenBootstrap.cause());
                }
            });
        }
    }

    /**
     * Resolves the request's remote addresses. When {@code scheduleTimeout} is {@code true} the request
     * timeout is scheduled here, before resolution — the behaviour the DEFAULT-mode new-channel path relies
     * on so the timeout also bounds DNS. ROUND_ROBIN resolves up front for every request (it needs the IP to
     * key the pool) and passes {@code false}: scheduling here and then again on the reuse-or-connect path
     * would allocate a {@code TimeoutsHolder} and a timer entry only to cancel them on a pooled hit. Instead
     * the chosen path schedules exactly once — {@link #sendRequestWithOpenChannel} for a reuse, or the
     * round-robin branch of {@link #sendRequestWithNewChannel} for a new connection.
     */
    private <T> Future<List<InetSocketAddress>> resolveAddresses(Request request, ProxyServer proxy, NettyResponseFuture<T> future,
                                                                 AsyncHandler<T> asyncHandler, boolean scheduleTimeout) {
        Uri uri = request.getUri();

        if (proxy != null && !proxy.isIgnoredForHost(uri.getHost()) && proxy.getProxyType().isHttp()) {
            int port = ProxyType.HTTPS.equals(proxy.getProxyType()) || uri.isSecured() ? proxy.getSecuredPort() : proxy.getPort();
            InetSocketAddress unresolvedRemoteAddress = InetSocketAddress.createUnresolved(proxy.getHost(), port);
            if (scheduleTimeout) {
                scheduleRequestTimeout(future, unresolvedRemoteAddress);
            }
            return resolveHostname(request, unresolvedRemoteAddress, asyncHandler);
        } else {
            int port = uri.getExplicitPort();

            InetSocketAddress unresolvedRemoteAddress = InetSocketAddress.createUnresolved(uri.getHost(), port);
            if (scheduleTimeout) {
                scheduleRequestTimeout(future, unresolvedRemoteAddress);
            }

            if (request.getAddress() != null) {
                // bypass resolution
                InetSocketAddress inetSocketAddress = new InetSocketAddress(request.getAddress(), port);
                Promise<List<InetSocketAddress>> promise = ImmediateEventExecutor.INSTANCE.newPromise();
                return promise.setSuccess(singletonList(inetSocketAddress));
            }
            return resolveHostname(request, unresolvedRemoteAddress, asyncHandler);
        }
    }

    private Future<List<InetSocketAddress>> resolveHostname(Request request, InetSocketAddress unresolvedRemoteAddress, AsyncHandler<?> asyncHandler) {
        AddressResolverGroup<InetSocketAddress> group = channelManager.getAddressResolverGroup();
        if (group != null) {
            AddressResolver<InetSocketAddress> resolver = group.getResolver(channelManager.getEventLoopGroup().next());
            return RequestHostnameResolver.INSTANCE.resolve(resolver, unresolvedRemoteAddress, asyncHandler);
        }
        return RequestHostnameResolver.INSTANCE.resolve(request.getNameResolver(), unresolvedRemoteAddress, asyncHandler);
    }

    private <T> NettyResponseFuture<T> newNettyResponseFuture(Request request, AsyncHandler<T> asyncHandler, NettyRequest nettyRequest, ProxyServer proxyServer) {
        NettyResponseFuture<T> future = new NettyResponseFuture<>(
                request,
                asyncHandler,
                nettyRequest,
                config.getMaxRequestRetry(),
                request.getChannelPoolPartitioning(),
                connectionSemaphore,
                proxyServer);

        String expectHeader = request.getHeaders().get(EXPECT);
        if (HttpHeaderValues.CONTINUE.contentEqualsIgnoreCase(expectHeader)) {
            future.setDontWriteBodyBecauseExpectContinue(true);
        }
        return future;
    }

    /**
     * Whether {@code name} is a connection-specific header forbidden in HTTP/2 (RFC 7540 §8.1.2.2).
     * Matched case-insensitively against the {@link HttpHeaderNames} {@link AsciiString} constants, so the
     * per-request header copy needs no {@link String}/{@code toLowerCase} allocation to run this check.
     */
    private static boolean isHttp2ExcludedHeader(CharSequence name) {
        return HttpHeaderNames.CONNECTION.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.HOST.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.UPGRADE.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.KEEP_ALIVE.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.PROXY_CONNECTION.contentEqualsIgnoreCase(name);
    }

    /**
     * Lower-cases an HTTP/1.1 header name for HTTP/2, allocating nothing when it is already lowercase.
     * Netty's validating {@link DefaultHttp2Headers} throws {@link io.netty.handler.codec.http2.Http2Exception}
     * on a name with any uppercase ASCII letter (it does not normalise), so mixed-case user names must be
     * lowercased before they are added. {@link AsciiString#toLowerCase()} and {@link String#toLowerCase(Locale)}
     * both return the same instance when nothing changes, so already-lowercase names — AHC's own
     * {@link HttpHeaderNames} constants — allocate nothing.
     */
    private static CharSequence toLowerCaseHeaderName(CharSequence name) {
        if (name instanceof AsciiString) {
            return ((AsciiString) name).toLowerCase();
        }
        return name.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Removes any userinfo subcomponent from an authority before it becomes the HTTP/2 {@code :authority}
     * pseudo-header. RFC 9113 §8.3.1: ":authority MUST NOT include the deprecated userinfo subcomponent". Drops
     * everything up to and including the last {@code '@'} (userinfo itself cannot contain a raw '@', RFC 3986
     * §3.2.1), leaving {@code host[:port]} — an IPv6 literal such as {@code [::1]:443} contains no '@' and is
     * untouched. Allocation-free when there is no userinfo (the common case).
     */
    private static CharSequence stripUserInfo(CharSequence authority) {
        if (authority == null) {
            return null;
        }
        for (int i = authority.length() - 1; i >= 0; i--) {
            if (authority.charAt(i) == '@') {
                return authority.subSequence(i + 1, authority.length());
            }
        }
        return authority;
    }

    public <T> void writeRequest(NettyResponseFuture<T> future, Channel channel) {
        // if the channel is dead because it was pooled and the remote server decided to close it,
        // we just let it go and the channelInactive do its work
        if (!Channels.isChannelActive(channel)) {
            return;
        }

        // Route to HTTP/2 path if the parent channel has the HTTP/2 multiplex handler installed
        if (ChannelManager.isHttp2(channel)) {
            writeHttp2Request(future, channel);
            return;
        }

        NettyRequest nettyRequest = future.getNettyRequest();
        HttpRequest httpRequest = nettyRequest.getHttpRequest();
        AsyncHandler<T> asyncHandler = future.getAsyncHandler();

        try {
            if (asyncHandler instanceof TransferCompletionHandler) {
                configureTransferAdapter(asyncHandler, httpRequest);
            }

            boolean writeBody = !future.isDontWriteBodyBecauseExpectContinue() && httpRequest.method() != HttpMethod.CONNECT && nettyRequest.getBody() != null;
            if (!future.isHeadersAlreadyWrittenOnContinue()) {
                try {
                    asyncHandler.onRequestSend(nettyRequest);
                } catch (Exception e) {
                    LOGGER.error("onRequestSend crashed", e);
                    abort(channel, future, e);
                    return;
                }

                // Atomically claim httpRequest for the channel write — from here Netty's HTTP/1.1 encoder owns
                // and releases it, so a later abort must not release it again (NettyRequest.release() then
                // no-ops). If the claim FAILS, a concurrent abort/cancel/timeout has already released the
                // request body on another thread; writing the freed buffer would be a use-after-free, so bail
                // (the future is already terminal). Before this point an abort releases the request body itself.
                if (!nettyRequest.markHandedToChannel()) {
                    return;
                }

                // if the request has a body, we want to track progress
                if (writeBody) {
                    // FIXME does this really work??? the promise is for the request without body!!!
                    ChannelProgressivePromise promise = channel.newProgressivePromise();
                    ChannelFuture f = channel.write(httpRequest, promise);
                    f.addListener(new WriteProgressListener(future, true, 0L));
                } else {
                    // we can just track write completion
                    ChannelPromise promise = channel.newPromise();
                    ChannelFuture f = channel.writeAndFlush(httpRequest, promise);
                    f.addListener(new WriteCompleteListener(future));
                }
            }

            if (writeBody) {
                nettyRequest.getBody().write(channel, future);
            }

            // don't bother scheduling read timeout if channel became invalid
            if (Channels.isChannelActive(channel)) {
                scheduleReadTimeout(future);
            }

        } catch (Exception e) {
            LOGGER.error("Can't write request", e);
            abort(channel, future, e);
        }
    }

    /**
     * Opens a new HTTP/2 stream child channel on the given parent connection channel and writes the request
     * as HTTP/2 frames ({@link DefaultHttp2HeadersFrame} + optional {@link DefaultHttp2DataFrame}).
     * The stream child channel has the {@link org.asynchttpclient.netty.handler.Http2Handler} installed
     * and the {@link NettyResponseFuture} attached to it, mirroring the HTTP/1.1 channel model.
     */
    private <T> void writeHttp2Request(NettyResponseFuture<T> future, Channel parentChannel) {
        Http2ConnectionState state = parentChannel.attr(Http2ConnectionState.HTTP2_STATE_KEY).get();

        if (state != null && !state.tryAcquireStream()) {
            if (state.isDraining()) {
                // Connection is draining from GOAWAY — fail the future so it retries on a new connection.
                // Don't close the parent channel since it may still have active streams. sendHttp2Frames
                // never runs for this future, so release its request body here to avoid leaking it.
                releaseHttp2Request(future);
                future.abort(new java.io.IOException("HTTP/2 connection is draining (GOAWAY received)"));
                return;
            }
            // Queue for later when a stream slot opens up. offerPendingOpener returns false if the
            // connection started draining/closing (e.g. a GOAWAY processed on the event loop) between our
            // tryAcquireStream() above and this enqueue — such an opener would never run, so fail the
            // request now instead of leaving it to be completed only by the request timeout (Issue #2160).
            if (!state.offerPendingOpener(future, () -> openHttp2Stream(future, parentChannel, state))) {
                releaseHttp2Request(future);
                // Fail ONLY this future (future.abort, not abort(parentChannel, ...)): the parent may be
                // draining-but-still-active with healthy sibling streams, and abort(channel, ...) would close
                // it — the multiplexed-connection blast radius. The parent closes itself once its streams drain.
                future.abort(new java.io.IOException("HTTP/2 connection draining or closed while request was queued"));
                return;
            }
            // The parent connection may have closed concurrently with the enqueue above; if so its
            // close listener already drained the queue and this future would be orphaned (it has no
            // stream channel, so no channelInactive is ever delivered for it). Detect that race and
            // fail it here so it never survives only to the request timeout (Issue #2160).
            if (!parentChannel.isActive() && !future.isDone()) {
                releaseHttp2Request(future);
                abort(parentChannel, future,
                        new java.io.IOException("HTTP/2 connection closed while request was queued"));
            }
            return;
        }
        openHttp2Stream(future, parentChannel, state);
    }

    private <T> void openHttp2Stream(NettyResponseFuture<T> future, Channel parentChannel, Http2ConnectionState state) {
        new Http2StreamChannelBootstrap(parentChannel)
                .handler(new ChannelInitializer<Http2StreamChannel>() {
                    @Override
                    protected void initChannel(Http2StreamChannel streamCh) {
                        if (config.isEnableAutomaticDecompression()) {
                            streamCh.pipeline().addLast("http2-decompressor",
                                    new Http2ContentDecompressor(config.isKeepEncodingHeader(),
                                            config.getHttp2MaxDecompressedResponseSize()));
                        }
                        streamCh.pipeline().addLast(channelManager.getHttp2Handler());
                    }
                })
                .open()
                .addListener((Future<Http2StreamChannel> f) -> {
                    if (f.isSuccess()) {
                        Http2StreamChannel streamChannel = f.getNow();

                        // Pin the request THIS stream is opened for so the closeFuture listener below frees
                        // exactly that buffer — even if the future is later replayed/redirected onto a
                        // freshly-built NettyRequest (re-reading future.getNettyRequest() at close time could
                        // otherwise free the new request's body before it is written). release() is idempotent.
                        final NettyRequest openedRequest = future.getNettyRequest();

                        // Release the acquired stream slot exactly once, when this stream channel closes —
                        // no matter HOW it closes: normal completion, abort, a non-IOException such as a
                        // DecompressionException that completes the future before finishUpdate runs, or a
                        // parent-connection drop. Binding the slot to the channel lifecycle (as
                        // NettyConnectListener does for the HTTP/1.1 connection semaphore) is the one place
                        // guaranteed to run exactly once per opened stream, so activeStreams cannot leak and
                        // wedge the connection. (The open-FAILURE branch below has no channel and releases
                        // inline.) The listener also closes a draining connection once its last stream ends.
                        streamChannel.closeFuture().addListener(closed -> {
                            // Safety net: free the request body when the stream ends, however it ends —
                            // covers an Expect/100-continue request whose server answered the final response
                            // without a 100, so the deferred body was never sent (hence never released).
                            // Idempotent with the prompt releases on the normal/abort paths.
                            if (openedRequest != null) {
                                openedRequest.release();
                            }
                            if (state != null) {
                                state.releaseStream();
                                // Close the parent once it has no active streams AND it is either draining
                                // (GOAWAY) or a redundant duplicate (#10 thundering-herd loser) — neither
                                // will serve further requests, so it must not linger open.
                                if ((state.isDraining() || state.isRedundant()) && state.getActiveStreams() <= 0) {
                                    channelManager.closeChannel(parentChannel);
                                }
                            }
                        });

                        channelManager.registerOpenChannel(streamChannel);
                        Channels.setAttribute(streamChannel, future);
                        Channels.setActiveToken(streamChannel);
                        future.attachChannel(streamChannel, false);
                        try {
                            AsyncHandler<T> asyncHandler = future.getAsyncHandler();
                            try {
                                asyncHandler.onRequestSend(future.getNettyRequest());
                            } catch (Exception e) {
                                LOGGER.error("onRequestSend crashed", e);
                                // sendHttp2Frames never ran, so it never released the request body — do it
                                // here. The slot is released by the closeFuture listener above once the
                                // abort closes the stream channel.
                                releaseHttp2Request(future);
                                abort(streamChannel, future, e);
                                return;
                            }

                            if (asyncHandler instanceof TransferCompletionHandler) {
                                configureTransferAdapter(asyncHandler, future.getNettyRequest().getHttpRequest());
                            }

                            sendHttp2Frames(future, streamChannel);
                            scheduleReadTimeout(future);
                        } catch (Exception e) {
                            LOGGER.error("Can't write HTTP/2 request", e);
                            // If the throw happened before sendHttp2Frames released the request body,
                            // release it now (idempotent). The slot is released via the closeFuture
                            // listener above once the abort closes the stream channel.
                            releaseHttp2Request(future);
                            abort(streamChannel, future, e);
                        }
                    } else {
                        // Stream channel was never opened — no closeFuture will fire, so release the
                        // acquired slot and the unsent request body inline.
                        if (state != null) {
                            state.releaseStream();
                        }
                        releaseHttp2Request(future);
                        // Fail ONLY this future (future.abort, not abort(parentChannel, ...)): opening one stream
                        // can fail for a stream-local reason (e.g. Netty rejecting it as the outbound max-streams
                        // bookkeeping races AHC's own cap) while the parent connection is healthy with sibling
                        // streams. abort(channel, ...) would closeChannel(parentChannel) and take them all down —
                        // the multiplexed-connection blast radius. Keep it stream-scoped, like the draining/queued
                        // paths above and Http2Handler.streamFailed(close=false).
                        future.abort(f.cause());
                    }
                });
    }

    /**
     * Builds and writes HTTP/2 frames for the given request on the stream child channel.
     * <p>
     * Manually assembles {@link DefaultHttp2Headers} with HTTP/2 pseudo-headers (:method, :path,
     * :scheme, :authority) plus all regular request headers, then writes them as a
     * {@link DefaultHttp2HeadersFrame}. If the request has a body, writes it as a
     * {@link DefaultHttp2DataFrame} with {@code endStream=true}.
     * <p>
     * Currently supports in-memory bodies ({@link DefaultFullHttpRequest} content and
     * {@link org.asynchttpclient.netty.request.body.NettyDirectBody}). Streaming bodies
     * (file uploads, input streams) are not yet supported over HTTP/2.
     */
    private <T> void sendHttp2Frames(NettyResponseFuture<T> future, Http2StreamChannel streamChannel) throws IOException {
        NettyRequest nettyRequest = future.getNettyRequest();
        HttpRequest httpRequest = nettyRequest.getHttpRequest();
        Uri uri = future.getUri();
        boolean releaseRequest = true;

        try {
            // Build HTTP/2 pseudo-headers + regular headers. :path reuses Uri.toRelativeUrl() (pooled
            // StringBuilder) instead of re-concatenating path + "?" + query on every request.
            // :authority must carry the effective Host — RFC 9113 §8.3.1 makes :authority authoritative and
            // it replaces the Host header (which isHttp2ExcludedHeader strips below). Reuse the Host the
            // request factory already computed (which honours setVirtualHost(...) and any explicit Host,
            // NettyRequestFactory) instead of re-deriving from the URI, so a virtual host is not dropped on
            // HTTP/2 the way it would be with a bare hostHeader(uri).
            // RFC 9113 §8.3.1: :authority MUST NOT include the deprecated userinfo subcomponent, so strip any
            // "user@" / "user:pass@" prefix from the effective Host before it becomes :authority.
            CharSequence hostValue = httpRequest.headers().get(HttpHeaderNames.HOST);
            CharSequence authority = stripUserInfo(hostValue != null ? hostValue : hostHeader(uri));
            Http2Headers h2Headers = new DefaultHttp2Headers()
                    .method(httpRequest.method().name())
                    .path(uri.toRelativeUrl())
                    .scheme(uri.getScheme())
                    .authority(authority);

            // Copy the HTTP/1.1 headers, dropping connection-specific names forbidden in HTTP/2 (RFC 7540
            // §8.1.2.2). iteratorCharSequence() avoids the per-name String the String-typed iterator forces;
            // see isHttp2ExcludedHeader and toLowerCaseHeaderName for the skip-check and lowercasing rules.
            Iterator<Map.Entry<CharSequence, CharSequence>> it = httpRequest.headers().iteratorCharSequence();
            while (it.hasNext()) {
                Map.Entry<CharSequence, CharSequence> entry = it.next();
                CharSequence name = entry.getKey();
                CharSequence value = entry.getValue();
                if (isHttp2ExcludedHeader(name)) {
                    continue;
                }
                // RFC 9113 §8.2.2: TE MUST NOT be sent over HTTP/2 with any value other than "trailers".
                // Forwarding a user's "TE: gzip" verbatim makes a conformant server reset the stream
                // (PROTOCOL_ERROR), so drop every TE value except the permitted "trailers".
                if (HttpHeaderNames.TE.contentEqualsIgnoreCase(name)
                        && !HttpHeaderValues.TRAILERS.contentEqualsIgnoreCase(value)) {
                    continue;
                }
                h2Headers.add(toLowerCaseHeaderName(name), value);
            }

            // Determine the body to send: an in-memory buffer (DefaultFullHttpRequest content or a
            // NettyDirectBody) or a streaming body written via NettyBody.writeHttp2. See http2BodyBuf.
            NettyBody nettyBody = nettyRequest.getBody();
            ByteBuf bodyBuf = http2BodyBuf(httpRequest, nettyBody);
            boolean hasStreamingBody = bodyBuf == null && nettyBody != null && !(nettyBody instanceof NettyDirectBody);
            boolean hasBody = bodyBuf != null || hasStreamingBody;

            if (hasBody && future.isDontWriteBodyBecauseExpectContinue()) {
                // Expect: 100-continue (RFC 9110 §10.1.1) — send only the HEADERS frame with
                // endStream=false and wait for the server's 100 (Continue) before sending the body. The
                // request (and its body buffer) must survive until the resume, so don't release it here:
                // Continue100Interceptor -> sendHttp2RequestBody() sends the body and releases. If the
                // server answers the final response WITHOUT a 100, the stream's closeFuture safety-net in
                // openHttp2Stream releases the never-sent body.
                streamChannel.write(new DefaultHttp2HeadersFrame(h2Headers, false));
                streamChannel.flush();
                releaseRequest = false;
                return;
            }

            // Write HEADERS frame (endStream=true when there is no body)
            streamChannel.write(new DefaultHttp2HeadersFrame(h2Headers, !hasBody));
            writeHttp2BodyFrames(future, streamChannel, bodyBuf, hasStreamingBody, nettyBody);
        } finally {
            // Release the original HTTP/1.1 request — in the HTTP/2 path it is not written to the channel,
            // so we must release it manually to avoid leaking its content ByteBuf. Routed through the
            // idempotent NettyRequest.release() so the early-abort paths (writeHttp2Request /
            // openHttp2Stream / failPendingOpeners) can release it too with no risk of a double-free here.
            // Skipped only when an Expect/100-continue request is parked waiting for its 100 (above).
            if (releaseRequest) {
                nettyRequest.release();
            }
        }
    }

    /**
     * Sends the body of an HTTP/2 request whose HEADERS were already written with {@code endStream=false}
     * because it carried {@code Expect: 100-continue}. Invoked by {@code Continue100Interceptor} once the
     * server's 100 (Continue) arrives: writes the body as DATA frame(s) with {@code endStream=true} and
     * releases the request.
     */
    public void sendHttp2RequestBody(NettyResponseFuture<?> future, Http2StreamChannel streamChannel) throws IOException {
        NettyRequest nettyRequest = future.getNettyRequest();
        NettyBody nettyBody = nettyRequest.getBody();
        ByteBuf bodyBuf = http2BodyBuf(nettyRequest.getHttpRequest(), nettyBody);
        boolean hasStreamingBody = bodyBuf == null && nettyBody != null && !(nettyBody instanceof NettyDirectBody);
        try {
            if (bodyBuf != null || hasStreamingBody) {
                writeHttp2BodyFrames(future, streamChannel, bodyBuf, hasStreamingBody, nettyBody);
            } else {
                // No body materialised (unexpected on this path) — just end the stream.
                streamChannel.writeAndFlush(new DefaultHttp2DataFrame(streamChannel.alloc().buffer(0), true));
            }
        } finally {
            nettyRequest.release();
        }
    }

    /**
     * Extracts the in-memory request body buffer for the HTTP/2 path: the inline content of a
     * {@link DefaultFullHttpRequest}, or a {@link NettyDirectBody}'s buffer. Returns {@code null} when
     * there is no in-memory body (a streaming body, if any, is written via {@link NettyBody#writeHttp2}).
     */
    private static ByteBuf http2BodyBuf(HttpRequest httpRequest, NettyBody nettyBody) {
        if (httpRequest instanceof DefaultFullHttpRequest) {
            ByteBuf content = ((DefaultFullHttpRequest) httpRequest).content();
            if (content != null && content.isReadable()) {
                return content;
            }
        }
        if (nettyBody instanceof NettyDirectBody) {
            ByteBuf directBuf = ((NettyDirectBody) nettyBody).byteBuf();
            if (directBuf != null && directBuf.isReadable()) {
                return directBuf;
            }
        }
        return null;
    }

    /**
     * Writes the request body as HTTP/2 DATA frame(s) with {@code endStream=true} (the HEADERS frame has
     * already been written).
     */
    private void writeHttp2BodyFrames(NettyResponseFuture<?> future, Http2StreamChannel streamChannel,
                                      ByteBuf bodyBuf, boolean hasStreamingBody, NettyBody nettyBody) throws IOException {
        if (hasStreamingBody) {
            streamChannel.flush();
            nettyBody.writeHttp2(streamChannel, future);
        } else if (bodyBuf != null) {
            // Single DATA frame; retainedDuplicate so releasing the request doesn't free the bytes
            // mid-write (Netty releases the duplicate after the write completes).
            streamChannel.write(new DefaultHttp2DataFrame(bodyBuf.retainedDuplicate(), true));
            streamChannel.flush();
        } else {
            streamChannel.flush();
        }
    }

    /**
     * Releases the request body buffer for an HTTP/2 request that is being aborted before
     * {@link #sendHttp2Frames} runs (and would otherwise release it). Idempotent — safe to call on a
     * path that may or may not have already reached {@code sendHttp2Frames}.
     */
    private static void releaseHttp2Request(NettyResponseFuture<?> future) {
        NettyRequest nettyRequest = future.getNettyRequest();
        if (nettyRequest != null) {
            nettyRequest.release();
        }
    }

    private static void configureTransferAdapter(AsyncHandler<?> handler, HttpRequest httpRequest) {
        HttpHeaders h = new DefaultHttpHeaders().set(httpRequest.headers());
        ((TransferCompletionHandler) handler).headers(h);
    }

    private void scheduleRequestTimeout(NettyResponseFuture<?> nettyResponseFuture,
                                        InetSocketAddress originalRemoteAddress) {
        nettyResponseFuture.touch();
        TimeoutsHolder timeoutsHolder = new TimeoutsHolder(nettyTimer, nettyResponseFuture, this, config,
                originalRemoteAddress);
        nettyResponseFuture.setTimeoutsHolder(timeoutsHolder);
    }

    private static void scheduleReadTimeout(NettyResponseFuture<?> nettyResponseFuture) {
        TimeoutsHolder timeoutsHolder = nettyResponseFuture.getTimeoutsHolder();
        if (timeoutsHolder != null) {
            // on very fast requests, it's entirely possible that the response has already
            // been completed
            // by the time we try to schedule the read timeout
            nettyResponseFuture.touch();
            timeoutsHolder.startReadTimeout();
        }
    }

    public void abort(Channel channel, NettyResponseFuture<?> future, Throwable t) {
        // Complete the future before closing, so the caller's cause is the one the user sees. Closing first
        // can fail an in-flight TLS handshake, and that failure races back through
        // NettyConnectListener.onFailure to abort the same future with a ConnectException instead -- which a
        // request timeout on the connect path can now hit, since the channel is published before the
        // handshake (issue #2189). The close still uses the channel passed in, which abort() does not clear.
        if (!future.isDone()) {
            future.setChannelState(ChannelState.CLOSED);
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
            future.abort(t);
        }

        if (channel != null && channel.isActive()) {
            channelManager.closeChannel(channel);
        }
    }

    public void handleUnexpectedClosedChannel(Channel channel, NettyResponseFuture<?> future) {
        if (Channels.isActiveTokenSet(channel)) {
            if (future.isDone()) {
                channelManager.closeChannel(channel);
            } else if (future.incrementRetryAndCheck() && retry(future)) {
                future.pendingException = null;
            } else {
                abort(channel, future, future.pendingException != null ? future.pendingException : RemotelyClosedException.INSTANCE);
            }
        }
    }

    public boolean retry(NettyResponseFuture<?> future) {
        if (isClosed()) {
            return false;
        }

        if (future.isReplayPossible()) {
            future.setChannelState(ChannelState.RECONNECTED);

            LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest().getHttpRequest());
            try {
                future.getAsyncHandler().onRetry();
            } catch (Exception e) {
                LOGGER.error("onRetry crashed", e);
                abort(future.channel(), future, e);
                return false;
            }

            try {
                sendNextRequest(future.getCurrentRequest(), future);
                return true;

            } catch (Exception e) {
                abort(future.channel(), future, e);
                return false;
            }
        } else {
            LOGGER.debug("Unable to recover future {}\n", future);
            return false;
        }
    }

    public boolean applyIoExceptionFiltersAndReplayRequest(NettyResponseFuture<?> future, IOException e, Channel channel) {

        boolean replayed = false;
        @SuppressWarnings({"unchecked", "rawtypes"})
        FilterContext<?> fc = new FilterContext.FilterContextBuilder(future.getAsyncHandler(), future.getCurrentRequest())
                .ioException(e).build();
        for (IOExceptionFilter asyncFilter : config.getIoExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                requireNonNull(fc, "filterContext");
            } catch (FilterException efe) {
                abort(channel, future, efe);
            }
        }

        if (fc.replayRequest() && future.incrementRetryAndCheck() && future.isReplayPossible()) {
            future.setKeepAlive(false);
            replayRequest(future, fc, channel);
            replayed = true;
        }
        return replayed;
    }

    public <T> void sendNextRequest(final Request request, final NettyResponseFuture<T> future) {
        sendRequest(request, future.getAsyncHandler(), future);
    }

    private static void validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        Uri uri = request.getUri();
        boolean isWs = uri.isWebSocket();
        if (asyncHandler instanceof WebSocketUpgradeHandler) {
            if (!isWs) {
                throw new IllegalArgumentException("WebSocketUpgradeHandler but scheme isn't ws or wss: " + uri.getScheme());
            } else if (!request.getMethod().equals(GET) && !request.getMethod().equals(CONNECT)) {
                throw new IllegalArgumentException("WebSocketUpgradeHandler but method isn't GET or CONNECT: " + request.getMethod());
            }
        } else if (isWs) {
            throw new IllegalArgumentException("No WebSocketUpgradeHandler but scheme is " + uri.getScheme());
        }
    }

    /**
     * Tries to reuse — or, failing that, wait for — an HTTP/2 connection to this origin that a sibling
     * request is establishing, after this request failed to acquire a connection permit. HTTP/2 stream reuse
     * multiplexes onto an existing connection and needs no permit, so this lets an over-cap request proceed.
     *
     * <p>The immediate attempt polls the request's own partition key and, in
     * {@link org.asynchttpclient.LoadBalance#ROUND_ROBIN} mode (registry keyed per IP —
     * {@code RoundRobinPartitionKey(base, IP)}; see {@link org.asynchttpclient.netty.channel.NettyConnectListener}),
     * falls back to ANY active, non-draining HTTP/2 connection already open to the same host on a sibling IP
     * (see {@link org.asynchttpclient.netty.channel.ChannelManager#pollHttp2SiblingConnection(Object)}). That
     * lets a request that failed the per-host {@code maxConnectionsPerHost} permit still multiplex onto an
     * existing connection to a different IP — multiplexing takes no permit — instead of stalling (issue #2214).
     * The sibling fallback is confined to this permit-failure path; the normal pooled-reuse path stays strictly
     * per IP so load keeps spreading. This only matters when {@code maxConnectionsPerHost} is configured below
     * the host's resolved-IP count (the default is unlimited).
     *
     * <p>If no connection is available yet, then <b>off the event loop</b> a one-shot
     * {@link Http2ConnectionWaiter} is registered that resumes the send when a matching connection is
     * registered ({@link ChannelManager#registerHttp2Connection}) — WITHOUT parking the caller thread — bounded
     * by a {@code connectTimeout} deadline that fails the request. The previous implementation instead
     * {@code Thread.sleep}-polled the registry here, blocking the caller thread (the synchronous part of
     * {@code execute()}) for up to the full {@code connectTimeout} and burning CPU.
     *
     * <p><b>On the event loop</b> we can neither block nor usefully defer: a redirect / 401 / 407 retry
     * re-enters here on the loop, and the connection we would wait for is being established on that SAME loop,
     * so waiting could self-deadlock. There we do the single immediate poll and give up.
     *
     * <p>In {@link org.asynchttpclient.LoadBalance#ROUND_ROBIN} mode the waiter is registered against the
     * per-host base key (see {@link ChannelManager#addHttp2ConnectionWaiter}), so a request pinned to
     * {@code IP_B} is woken by a connection registered for ANY IP of the host — including a sibling
     * {@code IP_A} that finishes connecting after this request deferred — and multiplexes onto it (#2214),
     * rather than waiting out the deadline for its own pinned IP.
     *
     * @return the (pending) future when the request was reused or deferred; {@code null} if it should be
     *         failed with {@code semaphoreException} (a WebSocket request, or on the event loop with no
     *         connection available)
     */
    private <T> ListenableFuture<T> reuseOrDeferHttp2Connection(Request request, ProxyServer proxy,
            NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler, IOException semaphoreException) {
        // WebSocket requests must never multiplex onto an HTTP/2 connection (no RFC 8441 support). See #2160.
        if (request.getUri().isWebSocket()) {
            return null;
        }
        // In round-robin mode, only multiplex onto the H2 connection for the IP this request is pinned to;
        // otherwise use the per-host base key. Derive it from the live request rather than
        // future.basePartitionKey() so a filter replay that rewrites the host still polls the correct key
        // (the future's targetRequest is only kept in sync on the redirect path).
        Object override = future.getPartitionKeyOverride();
        Object h2Key = override != null ? override
                : request.getChannelPoolPartitioning().getPartitionKey(request.getUri(), request.getVirtualHost(), proxy);
        Channel h2Channel = pollHttp2(h2Key);
        if (h2Channel != null) {
            return sendRequestWithOpenChannel(future, asyncHandler, h2Channel);
        }
        if (isOnEventLoop()) {
            return null;
        }
        new Http2ConnectionWaiter<>(request, proxy, future, asyncHandler, override, semaphoreException).arm();
        return future;
    }

    /**
     * A one-shot, off-event-loop waiter for a sibling HTTP/2 connection to the request's origin, used when a
     * request could not acquire a connection permit. Instead of blocking the caller thread polling the
     * registry, it registers itself with the {@link ChannelManager} and returns; it fires exactly once —
     * whichever of these happens first:
     * <ul>
     *   <li>a matching connection is registered → resume the send onto it via {@link #sendRequestWithOpenChannel};</li>
     *   <li>the {@code connectTimeout} deadline elapses → fail the request with the original permit exception;</li>
     *   <li>the client closes → {@link ChannelManager} invokes it with {@code null} → fail the request.</li>
     * </ul>
     * The {@link #claimed} CAS makes those sources mutually exclusive.
     */
    private final class Http2ConnectionWaiter<T> implements Consumer<Channel> {

        private final Request request;
        private final ProxyServer proxy;
        private final NettyResponseFuture<T> future;
        private final AsyncHandler<T> asyncHandler;
        private final Object override;
        private final IOException semaphoreException;
        // This request's own partition key (future.getPartitionKey() — the per-IP key in round-robin mode).
        // ChannelManager groups waiters by its per-host base key, so a connection registered for any IP of the
        // host wakes this waiter and it can multiplex onto that (possibly sibling-IP) connection (#2214).
        private final Object waitKey;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile Timeout deadline;

        Http2ConnectionWaiter(Request request, ProxyServer proxy, NettyResponseFuture<T> future,
                AsyncHandler<T> asyncHandler, Object override, IOException semaphoreException) {
            this.request = request;
            this.proxy = proxy;
            this.future = future;
            this.asyncHandler = asyncHandler;
            this.override = override;
            this.semaphoreException = semaphoreException;
            this.waitKey = future.getPartitionKey();
        }

        void arm() {
            if (!channelManager.addHttp2ConnectionWaiter(waitKey, this)) {
                // The client is closing (the waiter sweep already ran): no connection will register and the
                // nettyTimer that would fire our deadline is being stopped, so arming would hang the request.
                // Fail now with the original permit exception instead — and before touching nettyTimer, whose
                // newTimeout throws once stopped.
                accept(null);
                return;
            }
            // Assign the deadline before the recheck so it exists (and is cancellable) if a wake races in.
            deadline = nettyTimer.newTimeout(t -> fireTimeout(),
                    config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (claimed.get()) {
                // A registration woke us between addHttp2ConnectionWaiter and assigning `deadline`; cancel
                // the now-orphaned timeout (accept() could not, as `deadline` was still null then).
                deadline.cancel();
                return;
            }
            // A connection may have registered between the caller's poll (in reuseOrDeferHttp2Connection) and
            // addHttp2ConnectionWaiter above — a lost wakeup. Re-poll now that the waiter is registered, using
            // the same live-request key the caller resolved.
            Object h2Key = override != null ? override
                    : request.getChannelPoolPartitioning().getPartitionKey(request.getUri(), request.getVirtualHost(), proxy);
            Channel raced = pollHttp2(h2Key);
            if (raced != null) {
                accept(raced);
            }
        }

        private void fireTimeout() {
            if (claimed.compareAndSet(false, true)) {
                channelManager.removeHttp2ConnectionWaiter(waitKey, this);
                abort(null, future, semaphoreException);
            }
        }

        // Invoked with the registered connection to resume onto, or with null when the client is closing.
        @Override
        public void accept(Channel channel) {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            channelManager.removeHttp2ConnectionWaiter(waitKey, this);
            Timeout d = deadline;
            if (d != null) {
                d.cancel();
            }
            if (future.isDone()) {
                return;
            }
            if (channel == null) {
                abort(null, future, semaphoreException);
            } else {
                sendRequestWithOpenChannel(future, asyncHandler, channel);
            }
        }
    }

    // Polls the HTTP/2 registry for the already-resolved partition key (computed once by the caller from the
    // live request). In round-robin mode the key is the per-IP RoundRobinPartitionKey: the exact per-IP
    // connection is tried first (keeping reuse pinned to this request's IP), and only if that misses do we
    // fall back to a sibling-IP connection for the same host, so a permit-starved request can still multiplex
    // instead of failing (issue #2214). Otherwise the key is the plain per-host base key and no sibling
    // fallback applies. The fallback is confined to this permit-failure path — the happy path
    // (pollPooledChannel) deliberately does not use it, so steady-state reuse keeps spreading across IPs.
    private Channel pollHttp2(Object h2Key) {
        Channel h2Channel = channelManager.pollHttp2Connection(h2Key);
        if (h2Channel == null && h2Key instanceof RoundRobinPartitionKey) {
            h2Channel = channelManager.pollHttp2SiblingConnection(((RoundRobinPartitionKey) h2Key).getBaseKey());
        }
        return h2Channel;
    }

    private boolean isOnEventLoop() {
        for (EventExecutor executor : channelManager.getEventLoopGroup()) {
            if (executor.inEventLoop()) {
                return true;
            }
        }
        return false;
    }

    private Channel pollPooledChannel(NettyResponseFuture<?> future, Request request, ProxyServer proxy, AsyncHandler<?> asyncHandler) {
        try {
            asyncHandler.onConnectionPoolAttempt();
        } catch (Exception e) {
            LOGGER.error("onConnectionPoolAttempt crashed", e);
        }

        Uri uri = request.getUri();
        String virtualHost = request.getVirtualHost();

        // Round-robin mode: poll with the IP-aware key so reuse stays pinned to the chosen IP (both the
        // HTTP/2 registry and the HTTP/1.1 pool).
        Object override = future != null ? future.getPartitionKeyOverride() : null;
        if (override != null) {
            if (!uri.isWebSocket()) {
                Channel h2Channel = channelManager.pollHttp2Connection(override);
                if (h2Channel != null) {
                    LOGGER.debug("Using HTTP/2 multiplexed Channel '{}' for '{}' to '{}'", h2Channel, request.getMethod(), uri);
                    return h2Channel;
                }
            }
            Channel channel = channelManager.poll(override);
            if (channel != null) {
                LOGGER.debug("Using pooled Channel '{}' for '{}' to '{}'", channel, request.getMethod(), uri);
            }
            return channel;
        }

        // Check HTTP/2 connection registry first — these connections support multiplexing and are not
        // removed from the registry on poll (unlike the regular pool). WebSocket requests are excluded:
        // AsyncHttpClient does not implement RFC 8441 (WebSocket over HTTP/2), so reusing a pooled h2
        // connection would send the WS handshake as a plain HTTP/2 request and the WebSocket handler would
        // receive raw frames ("Invalid message ... AdaptiveByteBuf"). Fall through to an HTTP/1.1 connection.
        // See Issue #2160.
        // Compute the base partition key once and reuse it for both the HTTP/2 registry poll and the
        // HTTP/1.1 pool poll, instead of recomputing (and re-allocating) it inside each channelManager call.
        // Derive it from the live request rather than future.basePartitionKey(): on the filter-replay path
        // (replayRequest) the future's targetRequest is not updated to the replayed request, so its memoized
        // base key can lag a host-rewriting replay. Reading the current request's URI/virtualHost stays correct.
        Object partitionKey = request.getChannelPoolPartitioning().getPartitionKey(uri, virtualHost, proxy);
        if (!uri.isWebSocket()) {
            Channel h2Channel = channelManager.pollHttp2Connection(partitionKey);
            if (h2Channel != null) {
                LOGGER.debug("Using HTTP/2 multiplexed Channel '{}' for '{}' to '{}'", h2Channel, request.getMethod(), uri);
                return h2Channel;
            }
        }

        final Channel channel = channelManager.poll(partitionKey);

        if (channel != null) {
            LOGGER.debug("Using pooled Channel '{}' for '{}' to '{}'", channel, request.getMethod(), uri);
        }
        return channel;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) {
        Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setChannelState(ChannelState.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        try {
            future.getAsyncHandler().onRetry();
        } catch (Exception e) {
            LOGGER.error("onRetry crashed", e);
            abort(channel, future, e);
            return;
        }

        // An HTTP/2 stream child channel is single-use and never emits LastHttpContent, so the HTTP/1.1
        // drain-and-pool path (drainChannelAndOffer installs an OnLastHttpContentCallback) would wait for it
        // forever — leaking the stream slot (the closeFuture listener that releases the slot never fires)
        // and orphaning the channel until the connection wedges (Issue #2160). Instead detach the future from
        // the old stream (so the stream's imminent channelInactive does not fail the just-replayed request —
        // the same disconnect drainChannelAndOffer performs by swapping the channel attribute) and close it,
        // which fires the closeFuture slot release. The replay opens a fresh stream/connection below.
        if (channel instanceof Http2StreamChannel) {
            Channels.setDiscard(channel);
            channelManager.closeChannel(channel);
        } else {
            channelManager.drainChannelAndOffer(channel, future);
        }
        sendNextRequest(newRequest, future);
    }

    public boolean isClosed() {
        return clientState.isClosed();
    }

    public void drainChannelAndExecuteNextRequest(final Channel channel, final NettyResponseFuture<?> future, Request nextRequest) {
        Channels.setAttribute(channel, new OnLastHttpContentCallback(future) {
            @Override
            public void call() {
                sendNextRequest(nextRequest, future);
            }
        });
    }

    public void drainChannelAndExecuteNextRequest(final Channel channel, final NettyResponseFuture<?> future, Request nextRequest, Future<Channel> whenHandshaked) {
        Channels.setAttribute(channel, new OnLastHttpContentCallback(future) {
            @Override
            public void call() {
                whenHandshaked.addListener(f -> {
                            if (f.isSuccess()) {
                                if (!nextRequest.getUri().isWebSocket()) {
                                    channelManager.upgradePipelineToHttp2AfterProxyConnect(
                                            channel.pipeline(), future.getPartitionKey());
                                }
                                sendNextRequest(nextRequest, future);
                            } else {
                                future.abort(f.cause());
                            }
                        }
                );
            }
        });
    }
}
