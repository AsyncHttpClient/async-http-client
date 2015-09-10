/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request;

import static org.asynchttpclient.util.AsyncHttpProviderUtils.REMOTELY_CLOSED_EXCEPTION;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getExplicitPort;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.requestTimeout;
import static org.asynchttpclient.util.AuthenticatorUtils.perConnectionAuthorizationHeader;
import static org.asynchttpclient.util.AuthenticatorUtils.perConnectionProxyAuthorizationHeader;
import static org.asynchttpclient.util.HttpUtils.WS;
import static org.asynchttpclient.util.HttpUtils.useProxyConnect;
import static org.asynchttpclient.util.ProxyUtils.avoidProxy;
import static org.asynchttpclient.util.ProxyUtils.getProxyServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.handler.TransferCompletionHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.channel.NettyConnectListener;
import org.asynchttpclient.netty.timeout.ReadTimeoutTimerTask;
import org.asynchttpclient.netty.timeout.RequestTimeoutTimerTask;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

    private final AsyncHttpClientConfig config;
    private final ChannelManager channelManager;
    private final Timer nettyTimer;
    private final AtomicBoolean closed;
    private final NettyRequestFactory requestFactory;

    public NettyRequestSender(AsyncHttpClientConfig config,//
            ChannelManager channelManager,//
            Timer nettyTimer,//
            AtomicBoolean closed) {
        this.config = config;
        this.channelManager = channelManager;
        this.nettyTimer = nettyTimer;
        this.closed = closed;
        requestFactory = new NettyRequestFactory(config);
    }

    public <T> ListenableFuture<T> sendRequest(final Request request,//
            final AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean reclaimCache) {

        if (closed.get())
            throw new IllegalStateException("Closed");

        validateWebSocketRequest(request, asyncHandler);

        ProxyServer proxyServer = getProxyServer(config, request);
        boolean resultOfAConnect = future != null && future.getNettyRequest() != null && future.getNettyRequest().getHttpRequest().getMethod() == HttpMethod.CONNECT;
        boolean useProxy = proxyServer != null && !resultOfAConnect;

        if (useProxy && useProxyConnect(request.getUri()))
            // SSL proxy, have to handle CONNECT
            if (future != null && future.isConnectAllowed())
                // CONNECT forced
                return sendRequestWithCertainForceConnect(request, asyncHandler, future, reclaimCache, proxyServer, true, true);
            else
                return sendRequestThroughSslProxy(request, asyncHandler, future, reclaimCache, proxyServer);
        else
            return sendRequestWithCertainForceConnect(request, asyncHandler, future, reclaimCache, proxyServer, useProxy, false);
    }

    /**
     * We know for sure if we have to force to connect or not, so we can build
     * the HttpRequest right away This reduces the probability of having a
     * pooled channel closed by the server by the time we build the request
     */
    private <T> ListenableFuture<T> sendRequestWithCertainForceConnect(//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean reclaimCache,//
            ProxyServer proxyServer,//
            boolean useProxy,//
            boolean forceConnect) {
        NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, forceConnect);

        Channel channel = getCachedChannel(future, request, proxyServer, asyncHandler);

        if (Channels.isChannelValid(channel))
            return sendRequestWithCachedChannel(request, proxyServer, newFuture, asyncHandler, channel);
        else
            return sendRequestWithNewChannel(request, proxyServer, useProxy, newFuture, asyncHandler, reclaimCache);
    }

    /**
     * Using CONNECT depends on wither we can fetch a valid channel or not Loop
     * until we get a valid channel from the pool and it's still valid once the
     * request is built
     */
    @SuppressWarnings("unused")
    private <T> ListenableFuture<T> sendRequestThroughSslProxy(//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean reclaimCache,//
            ProxyServer proxyServer) {

        NettyResponseFuture<T> newFuture = null;
        for (int i = 0; i < 3; i++) {
            Channel channel = getCachedChannel(future, request, proxyServer, asyncHandler);
            if (Channels.isChannelValid(channel))
                if (newFuture == null)
                    newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, false);

            if (Channels.isChannelValid(channel))
                // if the channel is still active, we can use it, otherwise try
                // gain
                return sendRequestWithCachedChannel(request, proxyServer, newFuture, asyncHandler, channel);
            else
                // pool is empty
                break;
        }

        newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, proxyServer, true);
        return sendRequestWithNewChannel(request, proxyServer, true, newFuture, asyncHandler, reclaimCache);
    }

    private <T> NettyResponseFuture<T> newNettyRequestAndResponseFuture(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> originalFuture,
            ProxyServer proxy, boolean forceConnect) {

        NettyRequest nettyRequest = requestFactory.newNettyRequest(request, forceConnect, proxy);

        if (originalFuture == null) {
            return newNettyResponseFuture(request, asyncHandler, nettyRequest, proxy);
        } else {
            originalFuture.setNettyRequest(nettyRequest);
            originalFuture.setRequest(request);
            return originalFuture;
        }
    }

    private Channel getCachedChannel(NettyResponseFuture<?> future, Request request, ProxyServer proxyServer, AsyncHandler<?> asyncHandler) {

        if (future != null && future.reuseChannel() && Channels.isChannelValid(future.channel()))
            return future.channel();
        else
            return pollAndVerifyCachedChannel(request, proxyServer, asyncHandler);
    }

    private <T> ListenableFuture<T> sendRequestWithCachedChannel(Request request, ProxyServer proxy, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler, Channel channel) {

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionPooled(channel);

        future.setState(NettyResponseFuture.STATE.POOLED);
        future.attachChannel(channel, false);

        LOGGER.debug("Using cached Channel {} for {} '{}'", channel, future.getNettyRequest().getHttpRequest().getMethod(), future.getNettyRequest().getHttpRequest().getUri());

        if (Channels.isChannelValid(channel)) {
            Channels.setAttribute(channel, future);

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
            boolean useProxy,//
            NettyResponseFuture<T> future,//
            AsyncHandler<T> asyncHandler,//
            boolean reclaimCache) {

        // some headers are only set when performing the first request
        HttpHeaders headers = future.getNettyRequest().getHttpRequest().headers();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        boolean connect = future.getNettyRequest().getHttpRequest().getMethod() == HttpMethod.CONNECT;
        requestFactory.addAuthorizationHeader(headers, perConnectionAuthorizationHeader(request, proxy, realm));
        requestFactory.setProxyAuthorizationHeader(headers, perConnectionProxyAuthorizationHeader(request, proxy, connect));

        // Do not throw an exception when we need an extra connection for a
        // redirect
        // FIXME why? This violate the max connection per host handling, right?
        ClientBootstrap bootstrap = channelManager.getBootstrap(request.getUri().getScheme(), useProxy);

        boolean channelPreempted = false;
        Object partitionKey = future.getPartitionKey();

        try {
            // Do not throw an exception when we need an extra connection for a
            // redirect.
            if (!reclaimCache) {
                channelManager.preemptChannel(partitionKey);
                channelPreempted = true;
            }

            if (asyncHandler instanceof AsyncHandlerExtensions)
                AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionOpen();

            ChannelFuture channelFuture = connect(request, proxy, useProxy, bootstrap, asyncHandler);
            channelFuture.addListener(new NettyConnectListener<T>(future, this, channelManager, channelPreempted, partitionKey));

        } catch (Throwable t) {
            if (channelPreempted)
                channelManager.abortChannelPreemption(partitionKey);

            abort(null, future, t.getCause() == null ? t : t.getCause());
        }

        return future;
    }

    private <T> NettyResponseFuture<T> newNettyResponseFuture(Request request, AsyncHandler<T> asyncHandler, NettyRequest nettyRequest, ProxyServer proxyServer) {

        NettyResponseFuture<T> future = new NettyResponseFuture<>(//
                request,//
                asyncHandler,//
                nettyRequest,//
                config.getMaxRequestRetry(),//
                request.getConnectionPoolPartitioning(),//
                proxyServer);

        String expectHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.EXPECT);
        if (expectHeader != null && expectHeader.equalsIgnoreCase(HttpHeaders.Values.CONTINUE))
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

            if (!future.isHeadersAlreadyWrittenOnContinue()) {
                if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
                    AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRequestSend(nettyRequest);
                channel.write(httpRequest).addListener(new ProgressListener(config, future.getAsyncHandler(), future, true));
            }

            if (!future.isDontWriteBodyBecauseExpectContinue() && httpRequest.getMethod() != HttpMethod.CONNECT && nettyRequest.getBody() != null)
                nettyRequest.getBody().write(channel, future);

            // don't bother scheduling timeouts if channel became invalid
            if (Channels.isChannelValid(channel))
                scheduleTimeouts(future);

        } catch (Exception e) {
            LOGGER.error("Can't write request", e);
            abort(channel, future, e);
        }
    }

    private InetSocketAddress remoteAddress(Request request, ProxyServer proxy, boolean useProxy) throws UnknownHostException {

        InetAddress address;
        Uri uri = request.getUri();
        int port = getExplicitPort(uri);

        if (request.getInetAddress() != null) {
            address = request.getInetAddress();

        } else if (!useProxy || avoidProxy(proxy, uri.getHost())) {
            address = request.getNameResolver().resolve(uri.getHost());

        } else {
            address = request.getNameResolver().resolve(proxy.getHost());
            port = proxy.getPort();
        }

        return new InetSocketAddress(address, port);
    }

    private ChannelFuture connect(Request request, ProxyServer proxy, boolean useProxy, ClientBootstrap bootstrap, AsyncHandler<?> asyncHandler) throws UnknownHostException {
        InetSocketAddress remoteAddress = remoteAddress(request, proxy, useProxy);

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onDnsResolved(remoteAddress.getAddress());

        if (request.getLocalAddress() != null)
            return bootstrap.connect(remoteAddress, new InetSocketAddress(request.getLocalAddress(), 0));
        else
            return bootstrap.connect(remoteAddress);
    }

    private void configureTransferAdapter(AsyncHandler<?> handler, HttpRequest httpRequest) {
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        for (Map.Entry<String, String> entries : httpRequest.headers()) {
            h.add(entries.getKey(), entries.getValue());
        }

        TransferCompletionHandler transferCompletionHandler = (TransferCompletionHandler) handler;
        transferCompletionHandler.patchForNetty3();
        transferCompletionHandler.headers(h);
    }

    private void scheduleTimeouts(NettyResponseFuture<?> nettyResponseFuture) {

        nettyResponseFuture.touch();
        int requestTimeoutInMs = requestTimeout(config, nettyResponseFuture.getRequest());
        TimeoutsHolder timeoutsHolder = new TimeoutsHolder();
        if (requestTimeoutInMs != -1) {
            Timeout requestTimeout = newTimeout(new RequestTimeoutTimerTask(nettyResponseFuture, this, timeoutsHolder, requestTimeoutInMs), requestTimeoutInMs);
            timeoutsHolder.requestTimeout = requestTimeout;
        }

        int readTimeoutValue = config.getReadTimeout();
        if (readTimeoutValue != -1 && readTimeoutValue < requestTimeoutInMs) {
            // use readTimeout if it is less than the requestTimeout
            Timeout readTimeout = newTimeout(new ReadTimeoutTimerTask(nettyResponseFuture, this, timeoutsHolder, requestTimeoutInMs, readTimeoutValue), readTimeoutValue);
            timeoutsHolder.readTimeout = readTimeout;
        }
        nettyResponseFuture.setTimeoutsHolder(timeoutsHolder);
    }

    public Timeout newTimeout(TimerTask task, long delay) {
        return nettyTimer.newTimeout(task, delay, TimeUnit.MILLISECONDS);
    }

    public void abort(Channel channel, NettyResponseFuture<?> future, Throwable t) {

        if (channel != null)
            channelManager.closeChannel(channel);

        if (!future.isDone()) {
            future.setState(NettyResponseFuture.STATE.CLOSED);
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
            future.abort(t);
        }
    }

    public void handleUnexpectedClosedChannel(Channel channel, NettyResponseFuture<?> future) {
        if (future.isDone())
            channelManager.closeChannel(channel);

        else if (!retry(future))
            abort(channel, future, REMOTELY_CLOSED_EXCEPTION);
    }

    public boolean retry(NettyResponseFuture<?> future) {

        if (isClosed())
            return false;

        if (future.canBeReplayed()) {
            future.setState(NettyResponseFuture.STATE.RECONNECTED);
            future.getAndSetStatusReceived(false);

            LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest().getHttpRequest());
            if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
                AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
            }

            try {
                sendNextRequest(future.getRequest(), future);
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
        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest()).ioException(e).build();
        for (IOExceptionFilter asyncFilter : config.getIOExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            } catch (FilterException efe) {
                abort(channel, future, efe);
            }
        }

        if (fc.replayRequest() && future.canBeReplayed()) {
            replayRequest(future, fc, channel);
            replayed = true;
        }
        return replayed;
    }

    public <T> void sendNextRequest(Request request, NettyResponseFuture<T> future) {
        sendRequest(request, future.getAsyncHandler(), future, true);
    }

    private void validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        Uri uri = request.getUri();
        boolean isWs = uri.getScheme().startsWith(WS);
        if (asyncHandler instanceof WebSocketUpgradeHandler) {
            if (!isWs)
                throw new IllegalArgumentException("WebSocketUpgradeHandler but scheme isn't ws or wss: " + uri.getScheme());
            else if (!request.getMethod().equals(HttpMethod.GET.getName()))
                throw new IllegalArgumentException("WebSocketUpgradeHandler but method isn't GET: " + request.getMethod());
        } else if (isWs) {
            throw new IllegalArgumentException("No WebSocketUpgradeHandler but scheme is " + uri.getScheme());
        }
    }

    public Channel pollAndVerifyCachedChannel(Request request, ProxyServer proxy, AsyncHandler<?> asyncHandler) {

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionPool();

        Uri uri = request.getUri();
        String virtualHost = request.getVirtualHost();
        final Channel channel = channelManager.poll(uri, virtualHost, proxy, request.getConnectionPoolPartitioning());

        if (channel != null) {
            LOGGER.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                // Always make sure the channel who got cached support the
                // proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used against a proxy
                // that requires upgrading from http to
                // https.
                channelManager.verifyChannelPipeline(channel.getPipeline(), uri, virtualHost);
            } catch (Exception ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }
        return channel;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) {

        final Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();

        channelManager.drainChannelAndOffer(channel, future);
        sendNextRequest(newRequest, future);
        return;
    }

    public boolean isClosed() {
        return closed.get();
    }
}
