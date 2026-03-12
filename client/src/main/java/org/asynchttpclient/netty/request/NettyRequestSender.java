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
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timer;
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
import org.asynchttpclient.netty.channel.DefaultConnectionSemaphoreFactory;
import org.asynchttpclient.netty.channel.NettyChannelConnector;
import org.asynchttpclient.netty.channel.NettyConnectListener;
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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static java.util.Set.of;
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

    public NettyRequestSender(AsyncHttpClientConfig config, ChannelManager channelManager, Timer nettyTimer, AsyncHttpClientState clientState) {
        this.config = config;
        this.channelManager = channelManager;
        connectionSemaphore = config.getConnectionSemaphoreFactory() == null
                ? new DefaultConnectionSemaphoreFactory().newConnectionSemaphore(config)
                : config.getConnectionSemaphoreFactory().newConnectionSemaphore(config);
        this.nettyTimer = nettyTimer;
        this.clientState = clientState;
        requestFactory = new NettyRequestFactory(config);
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
            return pollPooledChannel(request, proxyServer, asyncHandler);
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
        // handleUnexpectedClosedChannel
        Channels.setAttribute(channel, future);

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
        HttpHeaders headers = future.getNettyRequest().getHttpRequest().headers();
        if (proxy != null && proxy.getCustomHeaders() != null) {
            HttpHeaders customHeaders = proxy.getCustomHeaders().apply(request);
            if (customHeaders != null) {
                headers.add(customHeaders);
            }
        }
        Realm realm = future.getRealm();
        Realm proxyRealm = future.getProxyRealm();
        requestFactory.addAuthorizationHeader(headers, perConnectionAuthorizationHeader(request, proxy, realm));
        requestFactory.setProxyAuthorizationHeader(headers, perConnectionProxyAuthorizationHeader(request, proxyRealm));

        future.setInAuth(realm != null && realm.isUsePreemptiveAuth() && realm.getScheme() != AuthScheme.NTLM);
        future.setInProxyAuth(proxyRealm != null && proxyRealm.isUsePreemptiveAuth() && proxyRealm.getScheme() != AuthScheme.NTLM);

        try {
            if (!channelManager.isOpen()) {
                throw PoolAlreadyClosedException.INSTANCE;
            }

            // Do not throw an exception when we need an extra connection for a
            // redirect.
            future.acquirePartitionLockLazily();
        } catch (Throwable t) {
            abort(null, future, getCause(t));
            // exit and don't try to resolve address
            return future;
        }

        resolveAddresses(request, proxy, future, asyncHandler).addListener(new SimpleFutureListener<List<InetSocketAddress>>() {

            @Override
            protected void onSuccess(List<InetSocketAddress> addresses) {
                NettyConnectListener<T> connectListener = new NettyConnectListener<>(future, NettyRequestSender.this, channelManager, connectionSemaphore);
                NettyChannelConnector connector = new NettyChannelConnector(request.getLocalAddress(), addresses, asyncHandler, clientState);
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

            @Override
            protected void onFailure(Throwable cause) {
                abort(null, future, getCause(cause));
            }
        });

        return future;
    }

    private <T> Future<List<InetSocketAddress>> resolveAddresses(Request request, ProxyServer proxy, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler) {
        Uri uri = request.getUri();
        final Promise<List<InetSocketAddress>> promise = ImmediateEventExecutor.INSTANCE.newPromise();

        if (proxy != null && !proxy.isIgnoredForHost(uri.getHost()) && proxy.getProxyType().isHttp()) {
            int port = ProxyType.HTTPS.equals(proxy.getProxyType()) || uri.isSecured() ? proxy.getSecuredPort() : proxy.getPort();
            InetSocketAddress unresolvedRemoteAddress = InetSocketAddress.createUnresolved(proxy.getHost(), port);
            scheduleRequestTimeout(future, unresolvedRemoteAddress);
            return RequestHostnameResolver.INSTANCE.resolve(request.getNameResolver(), unresolvedRemoteAddress, asyncHandler);
        } else {
            int port = uri.getExplicitPort();

            InetSocketAddress unresolvedRemoteAddress = InetSocketAddress.createUnresolved(uri.getHost(), port);
            scheduleRequestTimeout(future, unresolvedRemoteAddress);

            if (request.getAddress() != null) {
                // bypass resolution
                InetSocketAddress inetSocketAddress = new InetSocketAddress(request.getAddress(), port);
                return promise.setSuccess(singletonList(inetSocketAddress));
            } else {
                return RequestHostnameResolver.INSTANCE.resolve(request.getNameResolver(), unresolvedRemoteAddress, asyncHandler);
            }
        }
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
     * HTTP/2 connection-specific headers that must NOT be forwarded as per RFC 7540 §8.1.2.2.
     * These are HTTP/1.1 connection-specific headers that have no meaning in HTTP/2.
     */
    private static final Set<String> HTTP2_EXCLUDED_HEADERS = of(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "host"
    );

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
        new Http2StreamChannelBootstrap(parentChannel)
                .handler(new ChannelInitializer<Http2StreamChannel>() {
                    @Override
                    protected void initChannel(Http2StreamChannel streamCh) {
                        streamCh.pipeline().addLast(channelManager.getHttp2Handler());
                    }
                })
                .open()
                .addListener((Future<Http2StreamChannel> f) -> {
                    if (f.isSuccess()) {
                        Http2StreamChannel streamChannel = f.getNow();
                        channelManager.registerOpenChannel(streamChannel);
                        Channels.setAttribute(streamChannel, future);
                        future.attachChannel(streamChannel, false);
                        try {
                            AsyncHandler<T> asyncHandler = future.getAsyncHandler();
                            try {
                                asyncHandler.onRequestSend(future.getNettyRequest());
                            } catch (Exception e) {
                                LOGGER.error("onRequestSend crashed", e);
                                abort(parentChannel, future, e);
                                return;
                            }

                            if (asyncHandler instanceof TransferCompletionHandler) {
                                configureTransferAdapter(asyncHandler, future.getNettyRequest().getHttpRequest());
                            }

                            sendHttp2Frames(future, streamChannel);
                            scheduleReadTimeout(future);
                        } catch (Exception e) {
                            LOGGER.error("Can't write HTTP/2 request", e);
                            abort(parentChannel, future, e);
                        }
                    } else {
                        abort(parentChannel, future, f.cause());
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
    private <T> void sendHttp2Frames(NettyResponseFuture<T> future, Http2StreamChannel streamChannel) {
        NettyRequest nettyRequest = future.getNettyRequest();
        HttpRequest httpRequest = nettyRequest.getHttpRequest();
        Uri uri = future.getUri();

        // Build HTTP/2 pseudo-headers + regular headers
        Http2Headers h2Headers = new DefaultHttp2Headers()
                .method(httpRequest.method().name())
                .path(uri.getNonEmptyPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : ""))
                .scheme(uri.getScheme())
                .authority(hostHeader(uri));

        // Copy HTTP/1.1 headers, skipping connection-specific ones that are forbidden in HTTP/2.
        // RFC 7540 §8.1.2 requires all header field names to be lowercase in HTTP/2.
        httpRequest.headers().forEach(entry -> {
            String name = entry.getKey().toLowerCase();
            if (!HTTP2_EXCLUDED_HEADERS.contains(name)) {
                h2Headers.add(name, entry.getValue());
            }
        });

        // Determine if we have a body to write.
        // Support both DefaultFullHttpRequest (inline content) and NettyDirectBody (byte array/buffer bodies).
        ByteBuf bodyBuf = null;
        if (httpRequest instanceof DefaultFullHttpRequest) {
            ByteBuf content = ((DefaultFullHttpRequest) httpRequest).content();
            if (content != null && content.isReadable()) {
                bodyBuf = content;
            }
        }

        NettyBody nettyBody = nettyRequest.getBody();
        if (bodyBuf == null && nettyBody != null) {
            if (nettyBody instanceof NettyDirectBody) {
                ByteBuf directBuf = ((NettyDirectBody) nettyBody).byteBuf();
                if (directBuf != null && directBuf.isReadable()) {
                    bodyBuf = directBuf;
                }
            } else {
                throw new UnsupportedOperationException(
                        "Streaming request bodies (" + nettyBody.getClass().getSimpleName()
                                + ") are not yet supported over HTTP/2. Use an in-memory body or disable HTTP/2.");
            }
        }

        boolean hasBody = bodyBuf != null;

        // Write HEADERS frame (endStream=true when there is no body)
        streamChannel.write(new DefaultHttp2HeadersFrame(h2Headers, !hasBody));

        if (hasBody) {
            // Write DATA frame with endStream=true — body is sent as a single frame
            streamChannel.write(new DefaultHttp2DataFrame(bodyBuf.retainedDuplicate(), true));
        }

        streamChannel.flush();

        // Release the original HTTP/1.1 request — in the HTTP/2 path it is not written to the channel,
        // so we must release it manually to avoid leaking its content ByteBuf.
        ReferenceCountUtil.release(httpRequest);
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
        if (channel != null) {
            if (channel.isActive()) {
                channelManager.closeChannel(channel);
            }
        }

        if (!future.isDone()) {
            future.setChannelState(ChannelState.CLOSED);
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
            future.abort(t);
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

    private Channel pollPooledChannel(Request request, ProxyServer proxy, AsyncHandler<?> asyncHandler) {
        try {
            asyncHandler.onConnectionPoolAttempt();
        } catch (Exception e) {
            LOGGER.error("onConnectionPoolAttempt crashed", e);
        }

        Uri uri = request.getUri();
        String virtualHost = request.getVirtualHost();
        final Channel channel = channelManager.poll(uri, virtualHost, proxy, request.getChannelPoolPartitioning());

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

        channelManager.drainChannelAndOffer(channel, future);
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
