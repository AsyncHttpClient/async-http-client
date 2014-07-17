/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.request;

import static org.asynchttpclient.providers.netty.util.HttpUtil.WEBSOCKET;
import static org.asynchttpclient.providers.netty.util.HttpUtil.isSecure;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandlerExtensions;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.timeout.ReadTimeoutTimerTask;
import org.asynchttpclient.providers.netty.request.timeout.RequestTimeoutTimerTask;
import org.asynchttpclient.providers.netty.request.timeout.TimeoutsHolder;
import org.asynchttpclient.uri.UriComponents;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.ProxyUtils;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

    private final AtomicBoolean closed;
    private final AsyncHttpClientConfig config;
    private final Channels channels;
    private final NettyRequestFactory requestFactory;

    public NettyRequestSender(AtomicBoolean closed,//
            AsyncHttpClientConfig config,//
            NettyAsyncHttpProviderConfig nettyConfig,//
            Channels channels) {
        this.closed = closed;
        this.config = config;
        this.channels = channels;
        requestFactory = new NettyRequestFactory(config, nettyConfig);
    }

    public boolean retry(NettyResponseFuture<?> future, Channel channel) {

        if (closed.get())
            return false;

        channels.removeAll(channel);

        if (future == null) {
            Object attachment = Channels.getDefaultAttribute(channel);
            if (attachment instanceof NettyResponseFuture)
                future = (NettyResponseFuture<?>) attachment;
        }

        if (future != null && future.canBeReplayed()) {
            future.setState(NettyResponseFuture.STATE.RECONNECTED);
            future.getAndSetStatusReceived(false);

            LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest());
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

    public boolean applyIoExceptionFiltersAndReplayRequest(NettyResponseFuture<?> future, IOException e, Channel channel)
            throws IOException {

        boolean replayed = false;

        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest())
                .ioException(e).build();
        for (IOExceptionFilter asyncFilter : config.getIOExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            } catch (FilterException efe) {
                channels.abort(future, efe);
            }
        }

        if (fc.replayRequest()) {
            replayRequest(future, fc, channel);
            replayed = true;
        }
        return replayed;
    }

    public <T> void sendNextRequest(final Request request, final NettyResponseFuture<T> f) throws IOException {
        sendRequest(request, f.getAsyncHandler(), f, true);
    }

    // FIXME is this useful? Can't we do that when building the request?
    private final boolean validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        return request.getMethod().equals(HttpMethod.GET.name()) && asyncHandler instanceof WebSocketUpgradeHandler;
    }

    private Channel getCachedChannel(NettyResponseFuture<?> future, UriComponents uri, ConnectionPoolKeyStrategy poolKeyGen, ProxyServer proxyServer) {

        if (future != null && future.reuseChannel() && isChannelValid(future.channel()))
            return future.channel();
        else
            return channels.pollAndVerifyCachedChannel(uri, proxyServer, poolKeyGen);
    }

    private <T> ListenableFuture<T> sendRequestWithCachedChannel(Request request, UriComponents uri, ProxyServer proxy,
            NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler, Channel channel) throws IOException {

        future.setState(NettyResponseFuture.STATE.POOLED);
        future.attachChannel(channel, false);

        LOGGER.debug("\nUsing cached Channel {}\n for request \n{}\n", channel, future.getNettyRequest().getHttpRequest());
        Channels.setDefaultAttribute(channel, future);

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

    private InetSocketAddress remoteAddress(Request request, UriComponents uri, ProxyServer proxy, boolean useProxy) {
        if (request.getInetAddress() != null)
            return new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getDefaultPort(uri));

        else if (!useProxy || ProxyUtils.avoidProxy(proxy, uri.getHost()))
            return new InetSocketAddress(uri.getHost(), AsyncHttpProviderUtils.getDefaultPort(uri));

        else
            return new InetSocketAddress(proxy.getHost(), proxy.getPort());
    }

    private ChannelFuture connect(Request request, UriComponents uri, ProxyServer proxy, boolean useProxy, Bootstrap bootstrap) {
        InetSocketAddress remoteAddress = remoteAddress(request, uri, proxy, useProxy);

        if (request.getLocalAddress() != null)
            return bootstrap.connect(remoteAddress, new InetSocketAddress(request.getLocalAddress(), 0));
        else
            return bootstrap.connect(remoteAddress);
    }

    private <T> ListenableFuture<T> sendRequestWithNewChannel(//
            Request request,//
            UriComponents uri,//
            ProxyServer proxy,//
            boolean useProxy,//
            NettyResponseFuture<T> future,//
            AsyncHandler<T> asyncHandler,//
            boolean reclaimCache) throws IOException {

        boolean useSSl = isSecure(uri) && !useProxy;

        // Do not throw an exception when we need an extra connection for a redirect
        // FIXME why? This violate the max connection per host handling, right?
        Bootstrap bootstrap = channels.getBootstrap(request.getURI(), useSSl, useProxy);


        boolean channelPreempted = false;
        String poolKey = null;
        
        // Do not throw an exception when we need an extra connection for a redirect.
        if (!reclaimCache) {

            // only compute when maxConnectionPerHost is enabled
            // FIXME clean up
            if (config.getMaxConnectionsPerHost() > 0)
                poolKey = channels.getPoolKey(future);

            channelPreempted = channels.preemptChannel(asyncHandler, poolKey);
        }

        try {
            ChannelFuture channelFuture = connect(request, uri, proxy, useProxy, bootstrap);
            channelFuture.addListener(new NettyConnectListener<T>(config, this, future, channels, channelPreempted, poolKey));

        } catch (Throwable t) {
            if (channelPreempted)
                channels.abortChannelPreemption(poolKey);

            channels.abort(future, t.getCause() == null ? t : t.getCause());
        }

        return future;
    }

    private <T> NettyResponseFuture<T> newNettyResponseFuture(UriComponents uri, Request request, AsyncHandler<T> asyncHandler,
            NettyRequest nettyRequest, ProxyServer proxyServer) {

        NettyResponseFuture<T> f = new NettyResponseFuture<T>(//
                uri,//
                request,//
                asyncHandler,//
                nettyRequest,//
                config.getMaxRequestRetry(),//
                request.getConnectionPoolKeyStrategy(),//
                proxyServer);

        String expectHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.EXPECT);
        if (expectHeader != null && expectHeader.equalsIgnoreCase(HttpHeaders.Values.CONTINUE)) {
            f.setDontWriteBodyBecauseExpectContinue(true);
        }
        return f;
    }

    private <T> NettyResponseFuture<T> newNettyRequestAndResponseFuture(final Request request, final AsyncHandler<T> asyncHandler,
            NettyResponseFuture<T> originalFuture, UriComponents uri, ProxyServer proxy, boolean forceConnect) throws IOException {

        NettyRequest nettyRequest = requestFactory.newNettyRequest(request, uri, forceConnect, proxy);

        if (originalFuture == null) {
            return newNettyResponseFuture(uri, request, asyncHandler, nettyRequest, proxy);
        } else {
            originalFuture.setNettyRequest(nettyRequest);
            originalFuture.setRequest(request);
            return originalFuture;
        }
    }

    private boolean isChannelValid(Channel channel) {
        return channel != null && channel.isOpen() && channel.isActive();
    }

    private <T> ListenableFuture<T> sendRequestThroughSslProxy(//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean reclaimCache,//
            UriComponents uri,//
            ProxyServer proxyServer) throws IOException {

        // Using CONNECT depends on wither we can fetch a valid channel or not

        // Loop until we get a valid channel from the pool and it's still valid once the request is built
        NettyResponseFuture<T> newFuture = null;
        for (int i = 0; i < 3; i++) {
            Channel channel = getCachedChannel(future, uri, request.getConnectionPoolKeyStrategy(), proxyServer);
            if (isChannelValid(channel)) {
                if (newFuture == null)
                    newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, uri, proxyServer, false);

                if (isChannelValid(channel))
                    // if the channel is still active, we can use it, otherwise try gain
                    return sendRequestWithCachedChannel(request, uri, proxyServer, newFuture, asyncHandler, channel);
            } else
                // pool is empty
                break;
        }

        newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, uri, proxyServer, true);
        return sendRequestWithNewChannel(request, uri, proxyServer, true, newFuture, asyncHandler, reclaimCache);
    }

    private <T> ListenableFuture<T> sendRequestWithCertainForceConnect(//
            Request request,//
            AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean reclaimCache,//
            UriComponents uri,//
            ProxyServer proxyServer,//
            boolean useProxy,//
            boolean forceConnect) throws IOException {
        // We know for sure if we have to force to connect or not, so we can build the HttpRequest right away
        // This reduces the probability of having a pooled channel closed by the server by the time we build the request
        NettyResponseFuture<T> newFuture = newNettyRequestAndResponseFuture(request, asyncHandler, future, uri, proxyServer, forceConnect);

        Channel channel = getCachedChannel(future, uri, request.getConnectionPoolKeyStrategy(), proxyServer);

        if (isChannelValid(channel))
            return sendRequestWithCachedChannel(request, uri, proxyServer, newFuture, asyncHandler, channel);
        else
            return sendRequestWithNewChannel(request, uri, proxyServer, useProxy, newFuture, asyncHandler, reclaimCache);
    }

    public <T> ListenableFuture<T> sendRequest(final Request request,//
            final AsyncHandler<T> asyncHandler,//
            NettyResponseFuture<T> future,//
            boolean reclaimCache) throws IOException {

        if (closed.get()) {
            throw new IOException("Closed");
        }

        // FIXME really useful? Why not do this check when building the request?
        if (request.getURI().getScheme().startsWith(WEBSOCKET) && !validateWebSocketRequest(request, asyncHandler)) {
            throw new IOException("WebSocket method must be a GET");
        }

        UriComponents uri = request.getURI();
        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
        boolean resultOfAConnect = future != null && future.getNettyRequest() != null
                && future.getNettyRequest().getHttpRequest().getMethod() == HttpMethod.CONNECT;
        boolean useProxy = proxyServer != null && !resultOfAConnect;

        if (useProxy && isSecure(uri)) {
            // SSL proxy, have to handle CONNECT
            if (future != null && future.isConnectAllowed())
                // CONNECT forced
                return sendRequestWithCertainForceConnect(request, asyncHandler, future, reclaimCache, uri, proxyServer, true, true);
            else
                return sendRequestThroughSslProxy(request, asyncHandler, future, reclaimCache, uri, proxyServer);
        } else
            return sendRequestWithCertainForceConnect(request, asyncHandler, future, reclaimCache, uri, proxyServer, useProxy, false);
    }

    private void configureTransferAdapter(AsyncHandler<?> handler, HttpRequest httpRequest) {
        FluentCaseInsensitiveStringsMap h = new FluentCaseInsensitiveStringsMap();
        for (Map.Entry<String, String> entries : httpRequest.headers()) {
            h.add(entries.getKey(), entries.getValue());
        }

        TransferCompletionHandler.class.cast(handler).headers(h);
    }

    private void scheduleTimeouts(NettyResponseFuture<?> nettyResponseFuture) {

        try {
            nettyResponseFuture.touch();
            int requestTimeoutInMs = AsyncHttpProviderUtils.requestTimeout(config, nettyResponseFuture.getRequest());
            TimeoutsHolder timeoutsHolder = new TimeoutsHolder();
            if (requestTimeoutInMs != -1) {
                Timeout requestTimeout = channels.newTimeoutInMs(new RequestTimeoutTimerTask(nettyResponseFuture, channels, timeoutsHolder,
                        closed, requestTimeoutInMs), requestTimeoutInMs);
                timeoutsHolder.requestTimeout = requestTimeout;
            }

            int readTimeout = config.getReadTimeout();
            if (readTimeout != -1 && readTimeout < requestTimeoutInMs) {
                // no need for a idleConnectionTimeout that's less than the requestTimeoutInMs
                Timeout idleConnectionTimeout = channels.newTimeoutInMs(new ReadTimeoutTimerTask(nettyResponseFuture, channels,
                        timeoutsHolder, closed, requestTimeoutInMs, readTimeout), readTimeout);
                timeoutsHolder.readTimeout = idleConnectionTimeout;
            }
            nettyResponseFuture.setTimeoutsHolder(timeoutsHolder);
        } catch (RejectedExecutionException ex) {
            channels.abort(nettyResponseFuture, ex);
        }
    }

    public final <T> void writeRequest(NettyResponseFuture<T> future, Channel channel) {
        try {
            // if the channel is dead because it was pooled and the remote server decided to close it,
            // we just let it go and the channelInactive do its work
            if (!channel.isOpen() || !channel.isActive()) {
                return;
            }

            NettyRequest nettyRequest = future.getNettyRequest();
            HttpRequest httpRequest = nettyRequest.getHttpRequest();
            AsyncHandler<T> handler = future.getAsyncHandler();

            if (handler instanceof TransferCompletionHandler) {
                configureTransferAdapter(handler, httpRequest);
            }

            if (!future.isHeadersAlreadyWrittenOnContinue()) {
                try {
                    if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
                        AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRequestSent();
                    }
                    channel.writeAndFlush(httpRequest, channel.newProgressivePromise()).addListener(
                            new ProgressListener(config, future.getAsyncHandler(), future, true, 0L));
                } catch (Throwable cause) {
                    // FIXME why not notify?
                    LOGGER.debug(cause.getMessage(), cause);
                    try {
                        channel.close();
                    } catch (RuntimeException ex) {
                        LOGGER.debug(ex.getMessage(), ex);
                    }
                    return;
                }
            }

            if (!future.isDontWriteBodyBecauseExpectContinue() && !httpRequest.getMethod().equals(HttpMethod.CONNECT)
                    && nettyRequest.getBody() != null)
                nettyRequest.getBody().write(channel, future, config);

        } catch (Throwable ioe) {
            try {
                channel.close();
            } catch (RuntimeException ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }

        scheduleTimeouts(future);
    }

    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) throws IOException {
        Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
        }
        channels.drainChannel(channel, future);
        sendNextRequest(newRequest, future);
    }
}
