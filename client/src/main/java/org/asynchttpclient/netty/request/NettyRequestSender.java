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
package org.asynchttpclient.netty.request;

import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.AuthenticatorUtils.*;
import static org.asynchttpclient.util.HttpConstants.Methods.*;
import static org.asynchttpclient.util.MiscUtils.getCause;
import static org.asynchttpclient.util.ProxyUtils.getProxyServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Timer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.exception.PoolAlreadyClosedException;
import org.asynchttpclient.exception.RemotelyClosedException;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.handler.TransferCompletionHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.SimpleFutureListener;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.channel.ConnectionSemaphore;
import org.asynchttpclient.netty.channel.NettyConnectListener;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.resolver.RequestHostnameResolver;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

    private final AsyncHttpClientConfig config;
    private final ChannelManager channelManager;
    private final ConnectionSemaphore connectionSemaphore;
    private final Timer nettyTimer;
    private final AsyncHttpClientState clientState;
    private final NettyRequestFactory requestFactory;

    public NettyRequestSender(AsyncHttpClientConfig config,//
            ChannelManager channelManager,//
            ConnectionSemaphore connectionSemaphore,//
            Timer nettyTimer,//
            AsyncHttpClientState clientState) {
        this.config = config;
        this.channelManager = channelManager;
        this.connectionSemaphore = connectionSemaphore;
        this.nettyTimer = nettyTimer;
        this.clientState = clientState;
        requestFactory = new NettyRequestFactory(config);
    }

    public <T> ListenableFuture<T> sendRequest(final Request request,//
            final AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean performingNextRequest) {

        if (isClosed())
            throw new IllegalStateException("Closed");

        validateWebSocketRequest(request, asyncHandler);

        ProxyServer proxyServer = getProxyServer(config, request);

        // websockets use connect tunnelling to work with proxies
        if (proxyServer != null && (request.getUri().isSecured() || request.getUri().isWebSocket()) && !isConnectDone(request, future))
            if (future != null && future.isConnectAllowed())
                // SSL proxy or websocket: CONNECT for sure
                return sendRequestWithCertainForceConnect(request, asyncHandler, future, performingNextRequest, proxyServer, true);
            else
                // CONNECT will depend if we can pool or connection or if we have to open a new one
                return sendRequestThroughSslProxy(request, asyncHandler, future, performingNextRequest, proxyServer);
        else
            // no CONNECT for sure
            return sendRequestWithCertainForceConnect(request, asyncHandler, future, performingNextRequest, proxyServer, false);
    }

    private boolean isConnectDone(Request request, NettyResponseFuture<?> future) {
        return future != null //
                && future.getNettyRequest() != null //
                && future.getNettyRequest().getHttpRequest().method() == HttpMethod.CONNECT //
                && !request.getMethod().equals(CONNECT);
    }

    /**
     * We know for sure if we have to force to connect or not, so we can build the HttpRequest right away This reduces the probability of having a pooled channel closed by the
     * server by the time we build the request
     */
    private <T> ListenableFuture<T> sendRequestWithCertainForceConnect(//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean performingNextRequest,//
            ProxyServer proxyServer,//
            boolean forceConnect) {

        NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, forceConnect);

        Channel channel = getOpenChannel(future, request, proxyServer, asyncHandler);

        if (Channels.isChannelValid(channel))
            return sendRequestWithOpenChannel(request, proxyServer, newFuture, asyncHandler, channel);
        else
            return sendRequestWithNewChannel(request, proxyServer, newFuture, asyncHandler, performingNextRequest);
    }

    /**
     * Using CONNECT depends on wither we can fetch a valid channel or not Loop until we get a valid channel from the pool and it's still valid once the request is built @
     */
    @SuppressWarnings("unused")
    private <T> ListenableFuture<T> sendRequestThroughSslProxy(//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean performingNextRequest,//
            ProxyServer proxyServer) {

        NettyResponseFuture<T> newFuture = null;
        for (int i = 0; i < 3; i++) {
            Channel channel = getOpenChannel(future, request, proxyServer, asyncHandler);
            if (Channels.isChannelValid(channel))
                if (newFuture == null)
                    newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, false);

            if (Channels.isChannelValid(channel))
                // if the channel is still active, we can use it, otherwise try
                // gain
                return sendRequestWithOpenChannel(request, proxyServer, newFuture, asyncHandler, channel);
            else
                // pool is empty
                break;
        }

        newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, true);
        return sendRequestWithNewChannel(request, proxyServer, newFuture, asyncHandler, performingNextRequest);
    }

    private <T> NettyResponseFuture<T> newNettyRequestAndResponseFuture(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> originalFuture,
            ProxyServer proxy, boolean forceConnect) {

        Realm realm = null;
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

        NettyRequest nettyRequest = requestFactory.newNettyRequest(request, forceConnect, proxy, realm, proxyRealm);

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

        if (future != null && future.isReuseChannel() && Channels.isChannelValid(future.channel()))
            return future.channel();
        else
            return pollPooledChannel(request, proxyServer, asyncHandler);
    }

    private <T> ListenableFuture<T> sendRequestWithOpenChannel(Request request, ProxyServer proxy, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler, Channel channel) {

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionPooled(channel);

        TimeoutsHolder timeoutsHolder = scheduleRequestTimeout(future);
        timeoutsHolder.initRemoteAddress((InetSocketAddress) channel.remoteAddress());
        future.setChannelState(ChannelState.POOLED);
        future.attachChannel(channel, false);

        if (LOGGER.isDebugEnabled()) {
            HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
            LOGGER.debug("Using open Channel {} for {} '{}'", channel, httpRequest.method(), httpRequest.uri());
        }

        // channelInactive might be called between isChannelValid and writeRequest
        // so if we don't store the Future now, channelInactive won't perform handleUnexpectedClosedChannel
        Channels.setAttribute(channel, future);

        if (Channels.isChannelValid(channel)) {
            writeRequest(future, channel);
        } else {
            // bad luck, the channel was closed in-between
            // there's a very good chance onClose was already notified but the
            // future wasn't already registered
            handleUnexpectedClosedChannel(channel, future);
        }

        return future;
    }

    private <T> ListenableFuture<T> sendRequestWithNewChannel(//
            Request request,//
            ProxyServer proxy,//
            NettyResponseFuture<T> future,//
            AsyncHandler<T> asyncHandler,//
            boolean performingNextRequest) {

        // some headers are only set when performing the first request
        HttpHeaders headers = future.getNettyRequest().getHttpRequest().headers();
        Realm realm = future.getRealm();
        Realm proxyRealm = future.getProxyRealm();
        requestFactory.addAuthorizationHeader(headers, perConnectionAuthorizationHeader(request, proxy, realm));
        requestFactory.setProxyAuthorizationHeader(headers, perConnectionProxyAuthorizationHeader(request, proxyRealm));

        future.setInAuth(realm != null && realm.isUsePreemptiveAuth() && realm.getScheme() != AuthScheme.NTLM);
        future.setInProxyAuth(proxyRealm != null && proxyRealm.isUsePreemptiveAuth() && proxyRealm.getScheme() != AuthScheme.NTLM);

        // Do not throw an exception when we need an extra connection for a redirect
        // FIXME why? This violate the max connection per host handling, right?
        Bootstrap bootstrap = channelManager.getBootstrap(request.getUri(), proxy);

        Object partitionKey = future.getPartitionKey();

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

        scheduleRequestTimeout(future);

        RequestHostnameResolver.INSTANCE.resolve(request, proxy, asyncHandler)//
                .addListener(new SimpleFutureListener<List<InetSocketAddress>>() {

                    @Override
                    protected void onSuccess(List<InetSocketAddress> addresses) {
                        NettyConnectListener<T> connectListener = new NettyConnectListener<>(
                                future, NettyRequestSender.this, channelManager, connectionSemaphore, partitionKey);
                        NettyChannelConnector connector = new NettyChannelConnector(request.getLocalAddress(), addresses, asyncHandler, clientState, config);
                        if (!future.isDone()) {
                            connector.connect(bootstrap, connectListener);
                        }
                    }

                    @Override
                    protected void onFailure(Throwable cause) {
                        abort(null, future, getCause(cause));
                    }
                });

        return future;
    }

    private <T> NettyResponseFuture<T> newNettyResponseFuture(Request request, AsyncHandler<T> asyncHandler, NettyRequest nettyRequest, ProxyServer proxyServer) {

        NettyResponseFuture<T> future = new NettyResponseFuture<>(//
                request,//
                asyncHandler,//
                nettyRequest,//
                config.getMaxRequestRetry(),//
                request.getChannelPoolPartitioning(),//
                connectionSemaphore,//
                proxyServer);

        String expectHeader = request.getHeaders().get(EXPECT);
        if (HttpHeaderValues.CONTINUE.contentEqualsIgnoreCase(expectHeader))
            future.setDontWriteBodyBecauseExpectContinue(true);
        return future;
    }

    public <T> void writeRequest(NettyResponseFuture<T> future, Channel channel) {

        NettyRequest nettyRequest = future.getNettyRequest();
        HttpRequest httpRequest = nettyRequest.getHttpRequest();
        AsyncHandler<T> handler = future.getAsyncHandler();

        // if the channel is dead because it was pooled and the remote
        // server decided to close it,
        // we just let it go and the channelInactive do its work
        if (!Channels.isChannelValid(channel))
            return;

        try {
            if (handler instanceof TransferCompletionHandler)
                configureTransferAdapter(handler, httpRequest);

            boolean writeBody = !future.isDontWriteBodyBecauseExpectContinue() && httpRequest.method() != HttpMethod.CONNECT && nettyRequest.getBody() != null;

            if (!future.isHeadersAlreadyWrittenOnContinue()) {
                if (handler instanceof AsyncHandlerExtensions) {
                    AsyncHandlerExtensions.class.cast(handler).onRequestSend(nettyRequest);
                }

                // if the request has a body, we want to track progress
                if (writeBody) {
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

            if (writeBody)
                nettyRequest.getBody().write(channel, future);

            // don't bother scheduling read timeout if channel became invalid
            if (Channels.isChannelValid(channel))
                scheduleReadTimeout(future);

        } catch (Exception e) {
            LOGGER.error("Can't write request", e);
            abort(channel, future, e);
        }
    }

    private void configureTransferAdapter(AsyncHandler<?> handler, HttpRequest httpRequest) {
        HttpHeaders h = new DefaultHttpHeaders(false).set(httpRequest.headers());
        TransferCompletionHandler.class.cast(handler).headers(h);
    }

    private TimeoutsHolder scheduleRequestTimeout(NettyResponseFuture<?> nettyResponseFuture) {
        nettyResponseFuture.touch();
        TimeoutsHolder timeoutsHolder = new TimeoutsHolder(nettyTimer, nettyResponseFuture, this, config);
        nettyResponseFuture.setTimeoutsHolder(timeoutsHolder);
        return timeoutsHolder;
    }

    private void scheduleReadTimeout(NettyResponseFuture<?> nettyResponseFuture) {
        TimeoutsHolder timeoutsHolder = nettyResponseFuture.getTimeoutsHolder();
        if (timeoutsHolder != null) {
            // on very fast requests, it's entirely possible that the response has already been completed
            // by the time we try to schedule the read timeout
            nettyResponseFuture.touch();
            timeoutsHolder.startReadTimeout();
        }
    }

    public void abort(Channel channel, NettyResponseFuture<?> future, Throwable t) {

        if (channel != null)
            channelManager.closeChannel(channel);

        if (!future.isDone()) {
            future.setChannelState(ChannelState.CLOSED);
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
            future.abort(t);
        }
    }

    public void handleUnexpectedClosedChannel(Channel channel, NettyResponseFuture<?> future) {
        if (Channels.getInactiveToken(channel)) {
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

        if (isClosed())
            return false;

        if (future.isReplayPossible()) {
            future.setChannelState(ChannelState.RECONNECTED);

            LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest().getHttpRequest());
            if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
                AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
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

        @SuppressWarnings({ "unchecked", "rawtypes" })
        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getCurrentRequest()).ioException(e).build();
        for (IOExceptionFilter asyncFilter : config.getIoExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                assertNotNull(fc, "filterContext");
            } catch (FilterException efe) {
                abort(channel, future, efe);
            }
        }

        if (fc.replayRequest() && future.incrementRetryAndCheck() && future.isReplayPossible()) {
            replayRequest(future, fc, channel);
            replayed = true;
        }
        return replayed;
    }

    public <T> void sendNextRequest(final Request request, final NettyResponseFuture<T> future) {
        sendRequest(request, future.getAsyncHandler(), future, true);
    }

    private void validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        Uri uri = request.getUri();
        boolean isWs = uri.isWebSocket();
        if (asyncHandler instanceof WebSocketUpgradeHandler) {
            if (!isWs)
                throw new IllegalArgumentException("WebSocketUpgradeHandler but scheme isn't ws or wss: " + uri.getScheme());
            else if (!request.getMethod().equals(GET) && !request.getMethod().equals(CONNECT))
                throw new IllegalArgumentException("WebSocketUpgradeHandler but method isn't GET or CONNECT: " + request.getMethod());
        } else if (isWs) {
            throw new IllegalArgumentException("No WebSocketUpgradeHandler but scheme is " + uri.getScheme());
        }
    }

    private Channel pollPooledChannel(Request request, ProxyServer proxy, AsyncHandler<?> asyncHandler) {

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionPoolAttempt();

        Uri uri = request.getUri();
        String virtualHost = request.getVirtualHost();
        final Channel channel = channelManager.poll(uri, virtualHost, proxy, request.getChannelPoolPartitioning());

        if (channel != null) {
            LOGGER.debug("Using pooled Channel '{}' for '{}' to '{}'", channel, request.getMethod(), uri);
        }
        return channel;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) {

        Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setChannelState(ChannelState.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();

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
}
