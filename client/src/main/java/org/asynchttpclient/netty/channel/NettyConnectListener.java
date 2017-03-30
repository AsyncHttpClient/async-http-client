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
package org.asynchttpclient.netty.channel;

import static org.asynchttpclient.handler.AsyncHandlerExtensionsUtils.toAsyncHandlerExtensions;
import static org.asynchttpclient.util.HttpUtils.getBaseUrl;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SslHandler;

import java.net.ConnectException;
import java.net.InetSocketAddress;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.asynchttpclient.Request;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.SimpleFutureListener;
import org.asynchttpclient.netty.future.StackTraceInspector;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non Blocking connect.
 */
public final class NettyConnectListener<T> {

    private final static Logger LOGGER = LoggerFactory.getLogger(NettyConnectListener.class);

    private final NettyRequestSender requestSender;
    private final NettyResponseFuture<T> future;
    private final ChannelManager channelManager;
    private final ConnectionSemaphore connectionSemaphore;
    private final Object partitionKey;

    public NettyConnectListener(NettyResponseFuture<T> future,//
            NettyRequestSender requestSender,//
            ChannelManager channelManager,//
            ConnectionSemaphore connectionSemaphore,//
            Object partitionKey) {
        this.future = future;
        this.requestSender = requestSender;
        this.channelManager = channelManager;
        this.connectionSemaphore = connectionSemaphore;
        this.partitionKey = partitionKey;
    }

    private boolean futureIsAlreadyCancelled(Channel channel) {
        // FIXME should we only check isCancelled?
        if (future.isDone()) {
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

        channelManager.registerOpenChannel(channel, partitionKey);
        future.attachChannel(channel, false);
        requestSender.writeRequest(future, channel);
    }

    public void onSuccess(Channel channel, InetSocketAddress remoteAddress) {

        {
            // transfer lock from future to channel
            Object partitionKeyLock = future.takePartitionKeyLock();

            if (partitionKeyLock != null) {
                channel.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(Future<? super Void> future) throws Exception {
                        connectionSemaphore.releaseChannelLock(partitionKeyLock);
                    }
                });
            }
        }

        Channels.setInactiveToken(channel);

        TimeoutsHolder timeoutsHolder = future.getTimeoutsHolder();

        if (futureIsAlreadyCancelled(channel)) {
            return;
        }

        Request request = future.getTargetRequest();
        Uri uri = request.getUri();

        timeoutsHolder.initRemoteAddress(remoteAddress);

        // in case of proxy tunneling, we'll add the SslHandler later, after the CONNECT request
        if (future.getProxyServer() == null && uri.isSecured()) {
            SslHandler sslHandler = null;
            try {
                sslHandler = channelManager.addSslHandler(channel.pipeline(), uri, request.getVirtualHost());
            } catch (Exception sslError) {
                onFailure(channel, sslError);
                return;
            }

            final AsyncHandlerExtensions asyncHandlerExtensions = toAsyncHandlerExtensions(future.getAsyncHandler());

            if (asyncHandlerExtensions != null) {
                try {
                    asyncHandlerExtensions.onTlsHandshakeAttempt();
                } catch (Exception e) {
                    LOGGER.error("onTlsHandshakeAttempt crashed", e);
                    onFailure(channel, e);
                    return;
                }
            }

            sslHandler.handshakeFuture().addListener(new SimpleFutureListener<Channel>() {
                @Override
                protected void onSuccess(Channel value) throws Exception {
                    if (asyncHandlerExtensions != null) {
                        try {
                            asyncHandlerExtensions.onTlsHandshakeSuccess();
                        } catch (Exception e) {
                            LOGGER.error("onTlsHandshakeSuccess crashed", e);
                            NettyConnectListener.this.onFailure(channel, e);
                            return;
                        }
                    }
                    writeRequest(channel);
                }

                @Override
                protected void onFailure(Throwable cause) throws Exception {
                    if (asyncHandlerExtensions != null) {
                        try {
                            asyncHandlerExtensions.onTlsHandshakeFailure(cause);
                        } catch (Exception e) {
                            LOGGER.error("onTlsHandshakeFailure crashed", e);
                            NettyConnectListener.this.onFailure(channel, e);
                            return;
                        }
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

        boolean printCause = cause.getMessage() != null;
        String printedCause = printCause ? cause.getMessage() : getBaseUrl(future.getUri());
        ConnectException e = new ConnectException(printedCause);
        e.initCause(cause);
        future.abort(e);
    }
}
