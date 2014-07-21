/*
 * Copyright 2010 Ning, Inc.
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
 *
 */
package org.asynchttpclient.providers.netty.request;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.StackTraceInspector;
import org.asynchttpclient.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

/**
 * Non Blocking connect.
 */
final class NettyConnectListener<T> implements ChannelFutureListener {

    private final static Logger LOGGER = LoggerFactory.getLogger(NettyConnectListener.class);

    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;
    private final NettyResponseFuture<T> future;
    private final Channels channels;
    private final boolean channelPreempted;
    private final String poolKey;

    public NettyConnectListener(AsyncHttpClientConfig config,//
            NettyRequestSender requestSender,//
            NettyResponseFuture<T> future,//
            Channels channels,//
            boolean channelPreempted,//
            String poolKey) {
        this.requestSender = requestSender;
        this.config = config;
        this.future = future;
        this.channels = channels;
        this.channelPreempted = channelPreempted;
        this.poolKey = poolKey;
    }

    private void abortChannelPreemption(String poolKey) {
        if (channelPreempted)
            channels.abortChannelPreemption(poolKey);
    }

    private void writeRequest(Channel channel) {

        LOGGER.debug("\nNon cached request \n{}\n\nusing Channel \n{}\n", future.getNettyRequest(), channel);

        if (future.isDone()) {
            abortChannelPreemption(poolKey);
            return;
        }

        channels.registerOpenChannel(channel);
        future.attachChannel(channel, false);
        requestSender.writeRequest(future, channel);
    }

    public void onFutureSuccess(final Channel channel) throws ConnectException {
        Channels.setDefaultAttribute(channel, future);
        final HostnameVerifier hostnameVerifier = config.getHostnameVerifier();
        final SslHandler sslHandler = Channels.getSslHandler(channel);
        if (hostnameVerifier != null && sslHandler != null) {
            final String host = future.getURI().getHost();
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

            LOGGER.debug("Retrying {} ", future.getNettyRequest());
            if (requestSender.retry(future, channel)) {
                return;
            }
        }

        LOGGER.debug("Failed to recover from exception: {} with channel {}", cause, channel);

        boolean printCause = cause != null && cause.getMessage() != null;
        String url = future.getURI().toUrl();
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
