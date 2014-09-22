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
package com.ning.http.client.providers.netty.request;

import static com.ning.http.client.providers.netty.util.HttpUtils.WEBSOCKET;
import static com.ning.http.client.providers.netty.util.HttpUtils.isSecure;
import static com.ning.http.client.providers.netty.util.HttpUtils.useProxyConnect;
import static com.ning.http.util.AsyncHttpProviderUtils.getDefaultPort;
import static com.ning.http.util.AsyncHttpProviderUtils.requestTimeout;
import static com.ning.http.util.ProxyUtils.avoidProxy;
import static com.ning.http.util.ProxyUtils.getProxyServer;

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

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandlerExtensions;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ConnectionPoolPartitioning;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.timeout.ReadTimeoutTimerTask;
import com.ning.http.client.providers.netty.request.timeout.RequestTimeoutTimerTask;
import com.ning.http.client.providers.netty.request.timeout.TimeoutsHolder;
import com.ning.http.client.uri.Uri;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

    private final AsyncHttpClientConfig config;
    private final ChannelManager channelManager;
    private final Timer nettyTimer;
    private final AtomicBoolean closed;
    private final NettyRequestFactory requestFactory;
    private final IOException tooManyConnections;

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
        tooManyConnections = new IOException(String.format("Too many connections %s", config.getMaxConnections()));
        tooManyConnections.setStackTrace(new StackTraceElement[] {});
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
        boolean resultOfAConnect = future != null && future.getNettyRequest() != null
                && future.getNettyRequest().getHttpRequest().getMethod() == HttpMethod.CONNECT;
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
     * We know for sure if we have to force to connect or not, so we can
     * build the HttpRequest right away
     * This reduces the probability of having a pooled channel closed by the
     * server by the time we build the request
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
     * Using CONNECT depends on wither we can fetch a valid channel or not
     * Loop until we get a valid channel from the pool and it's still valid
     * once the request is built
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

    private <T> NettyResponseFuture<T> newNettyRequestAndResponseFuture(final Request request, final AsyncHandler<T> asyncHandler,
            NettyResponseFuture<T> originalFuture, Uri uri, ProxyServer proxy, boolean forceConnect) throws IOException {

        NettyRequest nettyRequest = requestFactory.newNettyRequest(request, uri, forceConnect, proxy);

        if (originalFuture == null) {
            return newNettyResponseFuture(uri, request, asyncHandler, nettyRequest, proxy);
        } else {
            originalFuture.setNettyRequest(nettyRequest);
            originalFuture.setRequest(request);
            return originalFuture;
        }
    }

    private Channel getCachedChannel(NettyResponseFuture<?> future, Uri uri, ConnectionPoolPartitioning poolKeyGen,
            ProxyServer proxyServer, AsyncHandler<?> asyncHandler) {

        if (future != null && future.reuseChannel() && Channels.isChannelValid(future.channel()))
            return future.channel();
        else
            return pollAndVerifyCachedChannel(uri, proxyServer, poolKeyGen, asyncHandler);
    }

    private <T> ListenableFuture<T> sendRequestWithCachedChannel(Request request, Uri uri, ProxyServer proxy,
            NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler, Channel channel) throws IOException {

        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onConnectionPooled();

        future.setState(NettyResponseFuture.STATE.POOLED);
        future.attachChannel(channel, false);

        LOGGER.debug("\nUsing cached Channel {}\n for request \n{}\n", channel, future.getNettyRequest().getHttpRequest());
        Channels.setAttribute(channel, future);

        try {
            writeRequest(future, channel);
        } catch (Exception ex) {
            // write request isn't supposed to throw Exceptions
            LOGGER.debug("writeRequest failure", ex);
            if (ex.getMessage() != null && ex.getMessage().contains("SSLEngine")) {
                // FIXME what is this for? https://github.com/AsyncHttpClient/async-http-client/commit/a847c3d4523ccc09827743e15b17e6bab59c553b
                // can such an exception happen as we write async?
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
        ClientBootstrap bootstrap = channelManager.getBootstrap(request.getUri().getScheme(), useProxy, useSSl);

        boolean channelPreempted = false;
        String poolKey = null;

        try {
            // Do not throw an exception when we need an extra connection for a redirect.
            if (!reclaimCache) {
                // only compute when maxConnectionPerHost is enabled
                // FIXME clean up
                if (config.getMaxConnectionsPerHost() > 0)
                    poolKey = channelManager.getPartitionId(future);

                if (!channelManager.preemptChannel(poolKey))
                    throw tooManyConnections;

                channelPreempted = true;
            }

            if (asyncHandler instanceof AsyncHandlerExtensions)
                AsyncHandlerExtensions.class.cast(asyncHandler).onOpenConnection();

            ChannelFuture channelFuture = connect(request, uri, proxy, useProxy, bootstrap);
            channelFuture.addListener(new NettyConnectListener<T>(config, future, this, channelManager, channelPreempted, poolKey));

        } catch (Throwable t) {
            if (channelPreempted)
                channelManager.abortChannelPreemption(poolKey);

            abort(null, future, t.getCause() == null ? t : t.getCause());
        }

        return future;
    }

    private <T> NettyResponseFuture<T> newNettyResponseFuture(Uri uri, Request request, AsyncHandler<T> asyncHandler,
            NettyRequest nettyRequest, ProxyServer proxyServer) {

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
                try {
                    if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
                        AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onSendRequest(nettyRequest);
                    channel.write(httpRequest).addListener(new ProgressListener(config, future.getAsyncHandler(), future, true));
                } catch (Throwable cause) {
                    // FIXME why not notify?
                    LOGGER.debug(cause.getMessage(), cause);
                    // FIXME what about the attribute? how could this fail?
                    Channels.silentlyCloseChannel(channel);
                    return;
                }
            }

            // FIXME what happens to this second write if the first one failed? Should it be done in the ProgressListener?
            if (!future.isDontWriteBodyBecauseExpectContinue() && !httpRequest.getMethod().equals(HttpMethod.CONNECT)
                    && nettyRequest.getBody() != null)
                nettyRequest.getBody().write(channel, future, config);

            // don't bother scheduling timeouts if channel became invalid
            if (Channels.isChannelValid(channel))
                scheduleTimeouts(future);

        } catch (Throwable ioe) {
            Channels.silentlyCloseChannel(channel);
        }
    }

    private InetSocketAddress remoteAddress(Request request, Uri uri, ProxyServer proxy, boolean useProxy) {
        if (request.getInetAddress() != null)
            return new InetSocketAddress(request.getInetAddress(), getDefaultPort(uri));

        else if (!useProxy || avoidProxy(proxy, uri.getHost()))
            return new InetSocketAddress(uri.getHost(), getDefaultPort(uri));

        else
            return new InetSocketAddress(proxy.getHost(), proxy.getPort());
    }

    private ChannelFuture connect(Request request, Uri uri, ProxyServer proxy, boolean useProxy, ClientBootstrap bootstrap) {
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
            Timeout requestTimeout = newTimeout(new RequestTimeoutTimerTask(nettyResponseFuture, this, timeoutsHolder,
                    requestTimeoutInMs), requestTimeoutInMs);
            timeoutsHolder.requestTimeout = requestTimeout;
        }

        int readTimeoutValue = config.getReadTimeout();
        if (readTimeoutValue != -1 && readTimeoutValue < requestTimeoutInMs) {
            // no need for a readTimeout that's less than the requestTimeout
            Timeout readTimeout = newTimeout(new ReadTimeoutTimerTask(nettyResponseFuture, this, timeoutsHolder,
                    requestTimeoutInMs, readTimeoutValue), readTimeoutValue);
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
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
            future.abort(t);
        }
    }

    public boolean retry(NettyResponseFuture<?> future) {

        if (isClosed())
            return false;

        // FIXME this was done in AHC2, is this a bug?
        // channelManager.removeAll(channel);

        if (future.canBeReplayed()) {
            future.setState(NettyResponseFuture.STATE.RECONNECTED);

            LOGGER.debug("Trying to recover request {}\n", future.getNettyRequest().getHttpRequest());
            if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
                AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();

            try {
                sendNextRequest(future.getRequest(), future);
                return true;

            } catch (IOException iox) {
                future.setState(NettyResponseFuture.STATE.CLOSED);
                future.abort(iox);
                LOGGER.error("Remotely closed, unable to recover", iox);
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

        @SuppressWarnings({ "unchecked", "rawtypes" })
        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest())
                .ioException(e).build();
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

    public <T> void sendNextRequest(final Request request, final NettyResponseFuture<T> future) throws IOException {
        sendRequest(request, future.getAsyncHandler(), future, true);
    }

    private boolean validateWebSocketRequest(Request request, AsyncHandler<?> asyncHandler) {
        return request.getMethod().equals(HttpMethod.GET.getName()) && asyncHandler instanceof WebSocketUpgradeHandler;
    }

    public Channel pollAndVerifyCachedChannel(Uri uri, ProxyServer proxy, ConnectionPoolPartitioning connectionPoolPartitioning, AsyncHandler<?> asyncHandler) {
 
        if (asyncHandler instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(asyncHandler).onPoolConnection();

        final Channel channel = channelManager.poll(uri, proxy, connectionPoolPartitioning);

        if (channel != null) {
            LOGGER.debug("Using cached Channel {}\n for uri {}\n", channel, uri);

            try {
                // Always make sure the channel who got cached support the proper protocol. It could
                // only occurs when a HttpMethod.CONNECT is used against a proxy that requires upgrading from http to
                // https.
                channelManager.verifyChannelPipeline(channel.getPipeline(), uri.getScheme());
            } catch (Exception ex) {
                LOGGER.debug(ex.getMessage(), ex);
            }
        }
        return channel;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) throws IOException {

        final Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();

        channelManager.drainChannel(channel, future);
        sendNextRequest(newRequest, future);
        return;
    }

    public boolean isClosed() {
        return closed.get();
    }
}
