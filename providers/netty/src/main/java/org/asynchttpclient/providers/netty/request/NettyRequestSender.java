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
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.NettyResponseFutures;
import org.asynchttpclient.providers.netty.request.timeout.IdleConnectionTimeoutTimerTask;
import org.asynchttpclient.providers.netty.request.timeout.RequestTimeoutTimerTask;
import org.asynchttpclient.providers.netty.request.timeout.TimeoutsHolder;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.ProxyUtils;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyRequestSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRequestSender.class);

    private final AtomicBoolean closed;
    private final AsyncHttpClientConfig config;
    private final Channels channels;

    public NettyRequestSender(AtomicBoolean closed, AsyncHttpClientConfig config, Channels channels) {
        this.closed = closed;
        this.config = config;
        this.channels = channels;
    }

    public boolean retry(Channel channel, NettyResponseFuture<?> future) {

        boolean success = false;

        if (!closed.get()) {
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
                    success = true;
                } catch (IOException iox) {
                    future.setState(NettyResponseFuture.STATE.CLOSED);
                    future.abort(iox);
                    LOGGER.error("Remotely Closed, unable to recover", iox);
                }
            } else {
                LOGGER.debug("Unable to recover future {}\n", future);
            }
        }
        return success;
    }

    public boolean applyIoExceptionFiltersAndReplayRequest(ChannelHandlerContext ctx, NettyResponseFuture<?> future, IOException e) throws IOException {

        boolean replayed = false;

        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest()).ioException(e).build();
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
            replayRequest(future, fc, ctx);
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

    private Channel getCachedChannel(NettyResponseFuture<?> future, URI uri, ConnectionPoolKeyStrategy poolKeyGen, ProxyServer proxyServer) {

        if (future != null && future.reuseChannel() && future.channel() != null) {
            return future.channel();
        } else {
            URI connectionKeyUri = proxyServer != null ? proxyServer.getURI() : uri;
            return channels.lookupInCache(connectionKeyUri, poolKeyGen);
        }
    }

    private <T> ListenableFuture<T> sendRequestWithCachedChannel(Channel channel, Request request, URI uri, ProxyServer proxy, NettyResponseFuture<T> future,
            AsyncHandler<T> asyncHandler) throws IOException {

        future.setState(NettyResponseFuture.STATE.POOLED);
        future.attachChannel(channel, false);

        LOGGER.debug("\nUsing cached Channel {}\n for request \n{}\n", channel, future.getNettyRequest().getHttpRequest());
        Channels.setDefaultAttribute(channel, future);

        try {
            writeRequest(channel, config, future);
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

    private InetSocketAddress remoteAddress(Request request, URI uri, ProxyServer proxy) {
        if (request.getInetAddress() != null)
            return new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getPort(uri));

        else if (proxy == null || ProxyUtils.avoidProxy(proxy, uri.getHost()))
            return new InetSocketAddress(AsyncHttpProviderUtils.getHost(uri), AsyncHttpProviderUtils.getPort(uri));

        else
            return new InetSocketAddress(proxy.getHost(), proxy.getPort());
    }

    private ChannelFuture connect(Request request, URI uri, ProxyServer proxy, Bootstrap bootstrap) {
        InetSocketAddress remoteAddress = remoteAddress(request, uri, proxy);

        if (request.getLocalAddress() != null) {
            return bootstrap.connect(remoteAddress, new InetSocketAddress(request.getLocalAddress(), 0));
        } else {
            return bootstrap.connect(remoteAddress);
        }
    }

    private <T> ListenableFuture<T> sendRequestWithNewChannel(Request request, URI uri, ProxyServer proxy, NettyResponseFuture<T> future, AsyncHandler<T> asyncHandler,
            boolean reclaimCache) throws IOException {

        boolean useSSl = isSecure(uri) && proxy == null;

        // Do not throw an exception when we need an extra connection for a redirect
        // FIXME why? This violate the max connection per host handling, right?
        boolean acquiredConnection = !reclaimCache && channels.acquireConnection(asyncHandler);
        Bootstrap bootstrap = channels.getBootstrap(request.getUrl(), useSSl);

        NettyConnectListener<T> connectListener = new NettyConnectListener<T>(config, this, future);

        ChannelFuture channelFuture;
        try {
            channelFuture = connect(request, uri, proxy, bootstrap);

        } catch (Throwable t) {
            if (acquiredConnection) {
                channels.releaseFreeConnections();
            }
            channels.abort(connectListener.future(), t.getCause() == null ? t : t.getCause());
            return connectListener.future();
        }

        channelFuture.addListener(connectListener);

        LOGGER.debug("\nNon cached request \n{}\n\nusing Channel \n{}\n", connectListener.future().getNettyRequest().getHttpRequest(), channelFuture.channel());

        if (!connectListener.future().isCancelled() || !connectListener.future().isDone()) {
            channels.registerChannel(channelFuture.channel());
            connectListener.future().attachChannel(channelFuture.channel(), false);
        }
        return connectListener.future();
    }

    private <T> NettyResponseFuture<T> newFuture(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> originalFuture, URI uri, ProxyServer proxy,
            boolean forceConnect) throws IOException {

        NettyRequest nettyRequest = NettyRequests.newNettyRequest(config, request, uri, forceConnect, proxy);

        if (originalFuture == null) {
            return NettyResponseFutures.newNettyResponseFuture(uri, request, asyncHandler, nettyRequest, config, proxy);
        } else {
            originalFuture.setNettyRequest(nettyRequest);
            originalFuture.setRequest(request);
            return originalFuture;
        }
    }

    public <T> ListenableFuture<T> sendRequest(final Request request, final AsyncHandler<T> asyncHandler, NettyResponseFuture<T> future, boolean reclaimCache) throws IOException {

        if (closed.get()) {
            throw new IOException("Closed");
        }

        // FIXME really useful? Why not do this check when building the request?
        if (request.getUrl().startsWith(WEBSOCKET) && !validateWebSocketRequest(request, asyncHandler)) {
            throw new IOException("WebSocket method must be a GET");
        }

        URI uri = config.isUseRawUrl() ? request.getRawURI() : request.getURI();
        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
        
        boolean sslProxy = proxyServer != null && isSecure(uri);
            
        if (!sslProxy) {
            // won't be forcing to CONNECT whatever how we get a connection, so we can build the HttpRequest right away
            
            // We first build the request, then try to get a connection from the pool.
            // This reduces the probability of having a pooled connection closed by the server by the time we build the request
            NettyResponseFuture<T> newFuture = newFuture(request, asyncHandler, future, uri, proxyServer, false);
            
            Channel channel = getCachedChannel(future, uri, request.getConnectionPoolKeyStrategy(), proxyServer);
            
            if (channel != null && channel.isOpen() && channel.isActive())
                return sendRequestWithCachedChannel(channel, request, uri, proxyServer, newFuture, asyncHandler);
            else
                return sendRequestWithNewChannel(request, uri, proxyServer, newFuture, asyncHandler, reclaimCache);
            
        } else {
            // we have to determine wither we have to open a new connection or not before being able to build the HttpRequest
            Channel channel = getCachedChannel(future, uri, request.getConnectionPoolKeyStrategy(), proxyServer);
            
            if (channel != null && channel.isOpen() && channel.isActive()) {
                NettyResponseFuture<T> newFuture = newFuture(request, asyncHandler, future, uri, proxyServer, future != null ? future.isConnectAllowed() : false);
                return sendRequestWithCachedChannel(channel, request, uri, proxyServer, newFuture, asyncHandler);

            } else {
                NettyResponseFuture<T> newFuture = newFuture(request, asyncHandler, future, uri, proxyServer, true);
                return sendRequestWithNewChannel(request, uri, proxyServer, newFuture, asyncHandler, reclaimCache);
            }
        }
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
                Timeout requestTimeout = channels.newTimeoutInMs(new RequestTimeoutTimerTask(nettyResponseFuture, channels, timeoutsHolder, closed), requestTimeoutInMs);
                timeoutsHolder.requestTimeout = requestTimeout;
            }

            int idleConnectionTimeoutInMs = config.getIdleConnectionTimeoutInMs();
            if (idleConnectionTimeoutInMs != -1 && idleConnectionTimeoutInMs < requestTimeoutInMs) {
                // no need for a idleConnectionTimeout that's less than the requestTimeoutInMs
                Timeout idleConnectionTimeout = channels.newTimeoutInMs(new IdleConnectionTimeoutTimerTask(nettyResponseFuture, channels, timeoutsHolder, closed,
                        requestTimeoutInMs, idleConnectionTimeoutInMs), idleConnectionTimeoutInMs);
                timeoutsHolder.idleConnectionTimeout = idleConnectionTimeout;
            }
        } catch (RejectedExecutionException ex) {
            channels.abort(nettyResponseFuture, ex);
        }
    }

    public final <T> void writeRequest(final Channel channel, final AsyncHttpClientConfig config, final NettyResponseFuture<T> future) {
        try {
            // If the channel is dead because it was pooled and the remote
            // server decided to close it, we just let it go and the
            // closeChannel do it's work.
            if (!channel.isOpen() || !channel.isActive()) {
                return;
            }

            NettyRequest nettyRequest = future.getNettyRequest();
            HttpRequest httpRequest = nettyRequest.getHttpRequest();
            AsyncHandler<T> handler = future.getAsyncHandler();

            if (handler instanceof TransferCompletionHandler) {
                configureTransferAdapter(handler, httpRequest);
            }

            // Leave it to true.
            // FIXME That doesn't just leave to true, the set is always done? and what's the point of not having a is/get?
            if (future.getAndSetWriteHeaders(true)) {
                try {
                    if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
                        AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRequestSent();
                    }
                    channel.writeAndFlush(httpRequest, channel.newProgressivePromise()).addListener(new ProgressListener(config, future.getAsyncHandler(), future, true, 0L));
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

            // FIXME OK, why? and what's the point of not having a is/get?
            if (future.getAndSetWriteBody(true) && !httpRequest.getMethod().equals(HttpMethod.CONNECT) && nettyRequest.getBody() != null)
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

    public void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, ChannelHandlerContext ctx) throws IOException {
        Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions) {
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();
        }
        channels.drainChannel(ctx, future);
        sendNextRequest(newRequest, future);
    }
}
