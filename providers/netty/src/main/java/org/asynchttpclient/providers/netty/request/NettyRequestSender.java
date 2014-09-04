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
package org.asynchttpclient.providers.netty.request;

import static org.asynchttpclient.providers.netty.util.HttpUtils.WEBSOCKET;
import static org.asynchttpclient.providers.netty.util.HttpUtils.isSecure;
import static org.asynchttpclient.providers.netty.util.HttpUtils.useProxyConnect;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getDefaultPort;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.requestTimeout;
import static org.asynchttpclient.util.ProxyUtils.avoidProxy;
import static org.asynchttpclient.util.ProxyUtils.getProxyServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandlerExtensions;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolPartitioning;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.timeout.ReadTimeoutTimerTask;
import org.asynchttpclient.providers.netty.request.timeout.RequestTimeoutTimerTask;
import org.asynchttpclient.providers.netty.request.timeout.TimeoutsHolder;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;
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
            NettyAsyncHttpProviderConfig nettyConfig,//
            ChannelManager channelManager,//
            Timer nettyTimer,//
            AtomicBoolean closed) {
        this.config = config;
        this.channelManager = channelManager;
        this.nettyTimer = nettyTimer;
        this.closed = closed;
        requestFactory = new NettyRequestFactory(config, nettyConfig);
    }

    public <T> ListenableFuture<T> sendRequest(final Request request,//
            final AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean reclaimCache) throws IOException {

        if (closed.get())
            throw new IOException("Closed");

        Uri uri = request.getUri();

        // FIXME really useful? Why not do this check when building the request?
        if (uri.getScheme().startsWith(WEBSOCKET) && !validateWebSocketRequest(request, asyncHandler))
            throw new IOException("WebSocket method must be a GET");

        ProxyServer proxyServer = getProxyServer(config, request);
        boolean resultOfAConnect = future != null && future.getNettyRequest() != null && future.getNettyRequest().getHttpRequest().getMethod() == HttpMethod.CONNECT;
        boolean useProxy = proxyServer != null && !resultOfAConnect;

        if (useProxy && useProxyConnect(uri))
            // SSL proxy, have to handle CONNECT
            if (future != null && future.isConnectAllowed())
                // CONNECT forced
                return sendRequestWithCertainForceConnect(request, asyncHandler, future, reclaimCache, uri, proxyServer, true, true);
            else
                return sendRequestThroughSslProxy(request, asyncHandler, future, reclaimCache, uri, proxyServer);
        else
            return sendRequestWithCertainForceConnect(request, asyncHandler, future, reclaimCache, uri, proxyServer, useProxy, false);
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
            Uri uri,//
            ProxyServer proxyServer,//
            boolean useProxy,//
            boolean forceConnect) throws IOException {

        NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, uri, proxyServer, forceConnect);

        Channel channel = getCachedChannel(future, uri, request.getConnectionPoolPartitioning(), proxyServer, asyncHandler);

        if (Channels.isChannelValid(channel))
            return sendRequestWithCachedChannel(request, uri, proxyServer, newFuture, asyncHandler, channel);
        else
            return sendRequestWithNewChannel(request, uri, proxyServer, useProxy, newFuture, asyncHandler, reclaimCache);
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
            Uri uri,//
            ProxyServer proxyServer) throws IOException {

        NettyResponseFuture<T> newFuture = null;
        for (int i = 0; i < 3; i++) {
            Channel channel = getCachedChannel(future, uri, request.getConnectionPoolPartitioning(), proxyServer, asyncHandler);
            if (Channels.isChannelValid(channel))
                if (newFuture == null)
                    newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, uri, proxyServer, false);

            if (Channels.isChannelValid(channel))
                // if the channel is still active, we can use it, otherwise try gain
                return sendRequestWithCachedChannel(request, uri, proxyServer, newFuture, asyncHandler, channel);
            else
                // pool is empty
                break;
        }

        newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, uri, proxyServer, true);
        return sendRequestWithNewChannel(request, uri, proxyServer, true, newFuture, asyncHandler, reclaimCache);
    }

    private <T> NettyResponseFuture<T> newNettyRequestAndResponseFuture(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> originalFuture,
            Uri uri, ProxyServer proxy, boolean forceConnect) throws IOException {

        NettyRequest nettyRequest = requestFactory.newNettyRequest(request, uri, forceConnect, proxy);

        if (originalFuture == null) {
            return newNettyResponseFuture(uri, request, asyncHandler, nettyRequest, proxy);
        } else {
            originalFuture.setNettyRequest(nettyRequest);
            originalFuture.setRequest(request);
            return originalFuture;
        }
    }

    private Channel getCachedChannel(NettyResponseFuture<?> future, Uri uri, ConnectionPoolPartitioning poolKeyGen, ProxyServer proxyServer, AsyncHandler<?> asyncHandler) {

        if (future != null && future.reuseChannel() && Channels.isChannelValid(future.channel()))
            return future.channel();
        else
            return pollAndVerifyCachedChannel(uri, proxyServer, poolKeyGen, asyncHandler);
    }

    private <T> ListenableFuture<T> sendRequestWithCachedChannel(Request request, Uri uri, ProxyServer proxy, NettyResponseFuture<T> future,
            AsyncHandler<T> asyncHandler, Channel channel) throws IOException {

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionPooled();

        future.setState(NettyResponseFuture.STATE.POOLED);
        future.attachChannel(channel, false);

        LOGGER.debug("\nUsing cached Channel {}\n for request \n{}\n", channel, future.getNettyRequest().getHttpRequest());
        Channels.setAttribute(channel, future);

        try {
            writeRequest(future, channel);
        } catch (Exception ex) {
            LOGGER.debug("writeRequest failure", ex);
            if (ex.getMessage() != null && ex.getMessage().contains("SSLEngine")) {
                LOGGER.debug("SSLEngine failure", ex);
                future = null;
            } else {
                try {
                    asyncHandler.onThrowable(ex);
                } catch (Throwable t) {
                    LOGGER.warn("doConnect.writeRequest()", t);
                }
                IOException ioe = new IOException(ex.getMessage());
                ioe.initCause(ex);
                throw ioe;
            }
        }
        return future;
    }

    private <T> ListenableFuture<T> sendRequestWithNewChannel(//
            Request request,//
            Uri uri,//
            ProxyServer proxy,//
            boolean useProxy,//
            NettyResponseFuture<T> future,//
            AsyncHandler<T> asyncHandler,//
            boolean reclaimCache) throws IOException {

        boolean useSSl = isSecure(uri) && !useProxy;

        // Do not throw an exception when we need an extra connection for a
        // redirect
        // FIXME why? This violate the max connection per host handling, right?
        Bootstrap bootstrap = channelManager.getBootstrap(request.getUri(), useProxy, useSSl);

        boolean channelPreempted = false;
        String poolKey = null;

        // Do not throw an exception when we need an extra connection for a
        // redirect.
        if (!reclaimCache) {

            // only compute when maxConnectionPerHost is enabled
            // FIXME clean up
            if (config.getMaxConnectionsPerHost() > 0)
                poolKey = channelManager.getPartitionId(future);

            channelPreempted = preemptChannel(asyncHandler, poolKey);
        }

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onOpenConnection();

        try {
            ChannelFuture channelFuture = connect(request, uri, proxy, useProxy, bootstrap);
            channelFuture.addListener(new NettyConnectListener<T>(config, future, this, channelManager, channelPreempted, poolKey));

        } catch (Throwable t) {
            if (channelPreempted)
                channelManager.abortChannelPreemption(poolKey);

            abort(null, future, t.getCause() == null ? t : t.getCause());
        }

        return future;
    }

    private <T> NettyResponseFuture<T> newNettyResponseFuture(Uri uri, Request request, AsyncHandler<T> asyncHandler, NettyRequest nettyRequest, ProxyServer proxyServer) {

        NettyResponseFuture<T> future = new NettyResponseFuture<T>(//
                uri,//
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
        try {
            // if the channel is dead because it was pooled and the remote
            // server decided to close it,
            // we just let it go and the channelInactive do its work
            if (!Channels.isChannelValid(channel))
                return;

            NettyRequest nettyRequest = future.getNettyRequest();
            HttpRequest httpRequest = nettyRequest.getHttpRequest();
            AsyncHandler<T> handler = future.getAsyncHandler();

            if (handler instanceof TransferCompletionHandler)
                configureTransferAdapter(handler, httpRequest);

            if (!future.isHeadersAlreadyWrittenOnContinue()) {
                try {
                    if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
                        AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onSendRequest();

                    channel.writeAndFlush(httpRequest, channel.newProgressivePromise()).addListener(new ProgressListener(config, future.getAsyncHandler(), future, true, 0L));
                } catch (Throwable cause) {
                    // FIXME why not notify?
                    LOGGER.debug(cause.getMessage(), cause);
                    Channels.silentlyCloseChannel(channel);
                    return;
                }
            }

            if (!future.isDontWriteBodyBecauseExpectContinue() && !httpRequest.getMethod().equals(HttpMethod.CONNECT) && nettyRequest.getBody() != null)
                nettyRequest.getBody().write(channel, future, config);

        } catch (Throwable ioe) {
            Channels.silentlyCloseChannel(channel);
        }

        scheduleTimeouts(future);
    }

    private InetSocketAddress remoteAddress(Request request, Uri uri, ProxyServer proxy, boolean useProxy) {
        if (request.getInetAddress() != null)
            return new InetSocketAddress(request.getInetAddress(), getDefaultPort(uri));

        else if (!useProxy || avoidProxy(proxy, uri.getHost()))
            return new InetSocketAddress(uri.getHost(), getDefaultPort(uri));

        else
            return new InetSocketAddress(proxy.getHost(), proxy.getPort());
    }

    private ChannelFuture connect(Request request, Uri uri, ProxyServer proxy, boolean useProxy, Bootstrap bootstrap) {
        InetSocketAddress remoteAddress = remoteAddress(request, uri, proxy, useProxy);

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

        TransferCompletionHandler.class.cast(handler).headers(h);
    }

    private void scheduleTimeouts(NettyResponseFuture<?> nettyResponseFuture) {

        nettyResponseFuture.touch();
        int requestTimeoutInMs = requestTimeout(config, nettyResponseFuture.getRequest());
        TimeoutsHolder timeoutsHolder = new TimeoutsHolder();
        if (requestTimeoutInMs != -1) {
            Timeout requestTimeout = newTimeout(new RequestTimeoutTimerTask(nettyResponseFuture, this, timeoutsHolder, requestTimeoutInMs), requestTimeoutInMs);
            timeoutsHolder.requestTimeout = requestTimeout;
        }

        int readTimeout = config.getReadTimeout();
        if (readTimeout != -1 && readTimeout < requestTimeoutInMs) {
            // no need for a idleConnectionTimeout that's less than the
            // requestTimeoutInMs
            Timeout idleConnectionTimeout = newTimeout(new ReadTimeoutTimerTask(nettyResponseFuture, this, timeoutsHolder, requestTimeoutInMs, readTimeout), readTimeout);
            timeoutsHolder.readTimeout = idleConnectionTimeout;
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
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
            future.abort(t);
        }
    }

    public boolean retry(NettyResponseFuture<?> future) {

        if (isClosed())
            return false;

        // FIXME what is this for???
        //channelManager.removeAll(channel);

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

            } catch (IOException iox) {
                future.setState(NettyResponseFuture.STATE.CLOSED);
                future.abort(iox);
                LOGGER.error("Remotely Closed, unable to recover", iox);
                return false;
            }
        } else {
            LOGGER.debug("Unable to recover future {}\n", future);
            return false;
        }
    }

    public boolean applyIoExceptionFiltersAndReplayRequest(NettyResponseFuture<?> future, IOException e, Channel channel) throws IOException {

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

        if (fc.replayRequest()  && future.canBeReplayed()) {
            replayRequest(future, fc, channel);
            replayed = true;
        }
        return replayed;
    }

    public <T> void sendNextRequest(final Request request, final NettyResponseFuture<T> future) throws IOException {
        sendRequest(request, future.getAsyncHandler(), future, true);
    }

    // FIXME is this useful? Can't we do that when building the request?
    private boolean validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        return request.getMethod().equals(HttpMethod.GET.name()) && asyncHandler instanceof WebSocketUpgradeHandler;
    }

    private Channel pollAndVerifyCachedChannel(Uri uri, ProxyServer proxy, ConnectionPoolPartitioning connectionPoolPartitioning, AsyncHandler<?> asyncHandler) {

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onPoolConnection();

        final Channel channel = channelManager.poll(uri, proxy, connectionPoolPartitioning);

        if (channel != null) {
            LOGGER.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                channelManager.verifyChannelPipeline(channel.pipeline(), uri.getScheme());
            } catch (Exception ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }
        return channel;
    }

    private boolean preemptChannel(AsyncHandler<?> asyncHandler, String poolKey) throws IOException {

        boolean channelPreempted = false;
        if (channelManager.preemptChannel(poolKey)) {
            channelPreempted = true;
        } else {
            IOException ex = new IOException(String.format("Too many connections %s", config.getMaxConnections()));
            try {
                asyncHandler.onThrowable(ex);
            } catch (Exception e) {
                LOGGER.warn("asyncHandler.onThrowable crashed", e);
            }
            throw ex;
        }
        return channelPreempted;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) throws IOException {

        Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();

        channelManager.drainChannel(channel, future);
        sendNextRequest(newRequest, future);
    }

    public boolean isClosed() {
        return closed.get();
    }
}
