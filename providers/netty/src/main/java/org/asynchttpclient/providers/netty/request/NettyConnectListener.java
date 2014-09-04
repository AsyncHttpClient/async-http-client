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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.asynchttpclient.AsyncHandlerExtensions;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.StackTraceInspector;
import org.asynchttpclient.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non Blocking connect.
 */
final class NettyConnectListener<T> implements ChannelFutureListener {

    private final static Logger LOGGER = LoggerFactory.getLogger(NettyConnectListener.class);

    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;
    private final NettyResponseFuture<T> future;
    private final ChannelManager channelManager;
    private final boolean channelPreempted;
    private final String poolKey;

    public NettyConnectListener(AsyncHttpClientConfig config,//
            NettyResponseFuture<T> future,//
            NettyRequestSender requestSender,//
            ChannelManager channelManager,//
            boolean channelPreempted,//
            String poolKey) {
        this.config = config;
        this.future = future;
        this.requestSender = requestSender;
        this.channelManager = channelManager;
        this.channelPreempted = channelPreempted;
        this.poolKey = poolKey;
    }

    private void abortChannelPreemption(String poolKey) {
        if (channelPreempted)
            channelManager.abortChannelPreemption(poolKey);
    }

    private void writeRequest(Channel channel) {

        LOGGER.debug("Request using non cached Channel '{}':\n{}\n", channel, future.getNettyRequest().getHttpRequest());

        if (future.isDone()) {
            abortChannelPreemption(poolKey);
            return;
        }

        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onConnectionOpen();

        channelManager.registerOpenChannel(channel);
        future.attachChannel(channel, false);
        requestSender.writeRequest(future, channel);
    }

    public void onFutureSuccess(final Channel channel) throws ConnectException {
        Channels.setAttribute(channel, future);
        final HostnameVerifier hostnameVerifier = config.getHostnameVerifier();
        final SslHandler sslHandler = ChannelManager.getSslHandler(channel.pipeline());
        if (hostnameVerifier != null && sslHandler != null) {
            final String host = future.getUri().getHost();
            sslHandler.handshakeFuture().addListener(new GenericFutureListener<Future<? super Channel>>() {
                @Override
                public void operationComplete(Future<? super Channel> handshakeFuture) throws Exception {
                    if (handshakeFuture.isSuccess()) {
                        Channel channel = (Channel) handshakeFuture.getNow();
                        SSLEngine engine = sslHandler.engine();
                        SSLSession session = engine.getSession();

                        LOGGER.debug("onFutureSuccess: session = {}, id = {}, isValid = {}, host = {}", session.toString(),
                                Base64.encode(session.getId()), session.isValid(), host);
                        if (hostnameVerifier.verify(host, session)) {
                            writeRequest(channel);
                        } else {
                            abortChannelPreemption(poolKey);
                            ConnectException exception = new ConnectException("HostnameVerifier exception");
                            future.abort(exception);
                            throw exception;
                        }
                    }
                }
            });
        } else {
            writeRequest(channel);
        }
    }

    public void onFutureFailure(Channel channel, Throwable cause) {

        abortChannelPreemption(poolKey);

        boolean canRetry = future.canRetry();
        LOGGER.debug("Trying to recover a dead cached channel {} with a retry value of {} ", channel, canRetry);
        if (canRetry//
                && cause != null//
                && (cause instanceof ClosedChannelException || future.getState() != NettyResponseFuture.STATE.NEW || StackTraceInspector.abortOnDisconnectException(cause))) {

            if (requestSender.retry(future)) {
                return;
            }
        }

        LOGGER.debug("Failed to recover from exception: {} with channel {}", cause, channel);

        boolean printCause = cause != null && cause.getMessage() != null;
        String url = future.getUri().toUrl();
        String printedCause = printCause ? cause.getMessage() + " to " + url : url;
        ConnectException e = new ConnectException(printedCause);
        if (cause != null)
            e.initCause(cause);
        future.abort(e);
    }

    public final void operationComplete(ChannelFuture f) throws Exception {
        if (f.isSuccess())
            onFutureSuccess(f.channel());
        else
            onFutureFailure(f.channel(), f.cause());
    }
}
