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

    private boolean futureIsAlreadyCancelled(Channel channel) {
        // If Future is cancelled then we will close the channel silently
        if (future.isCancelled()) {
            Channels.silentlyCloseChannel(channel);
            return true;
        }
        return false;
    }

    private void writeRequest(Channel channel) {
        if (futureIsAlreadyCancelled(channel)) {
            return;
        }

        if (LOGGER.isDebugEnabled()) {
            HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
            LOGGER.debug("Using new Channel '{}' for '{}' to '{}'", channel, httpRequest.method(), httpRequest.uri());
        }

        Channels.setAttribute(channel, future);

        channelManager.registerOpenChannel(channel);
        future.attachChannel(channel, false);
        requestSender.writeRequest(future, channel);
    }

    public void onSuccess(Channel channel, InetSocketAddress remoteAddress) {
        if (connectionSemaphore != null) {
            // transfer lock from future to channel
            Object partitionKeyLock = future.takePartitionKeyLock();

            if (partitionKeyLock != null) {
                channel.closeFuture().addListener(future -> connectionSemaphore.releaseChannelLock(partitionKeyLock));
            }
        }

        Channels.setActiveToken(channel);
        TimeoutsHolder timeoutsHolder = future.getTimeoutsHolder();

        if (futureIsAlreadyCancelled(channel)) {
            return;
        }

        Request request = future.getTargetRequest();
        Uri uri = request.getUri();
        // don't set a null resolved address - if the remoteAddress is null we keep
        // the previously scheduled (possibly unresolved) address to avoid NPEs in
        // timeout logging and keep useful diagnostic information
        if (remoteAddress != null) {
            timeoutsHolder.setResolvedRemoteAddress(remoteAddress);
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
            writeRequest(channel);
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
