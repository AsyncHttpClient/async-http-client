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
package com.ning.http.client.providers.netty.request.body;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.future.StackTraceInspector;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.util.Base64;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

/**
 * Non Blocking connect.
 */
public final class NettyConnectListener<T> implements ChannelFutureListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(NettyConnectListener.class);
    private final AsyncHttpClientConfig config;
    private final NettyResponseFuture<T> future;
    private final NettyRequestSender requestSender;
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

    public NettyResponseFuture<T> future() {
        return future;
    }

    private void abortChannelPreemption(String poolKey) {
        if (channelPreempted)
            channelManager.abortChannelPreemption(poolKey);
    }
    
    private void writeRequest(Channel channel, String poolKey) {

        LOGGER.debug("\nRequest \n{}\n\nusing non cached Channel \n{}\n", future.getNettyRequest().getHttpRequest(), channel);

        if (future.isDone()) {
            abortChannelPreemption(poolKey);
            return;
        }

        channelManager.registerOpenChannel(channel);
        future.attachChannel(channel, false);
        requestSender.writeRequest(future, channel);
    }

    public final void operationComplete(ChannelFuture f) throws Exception {
        Channel channel = f.getChannel();
        if (f.isSuccess()) {
            Channels.setAttribute(channel, future);
            final SslHandler sslHandler = ChannelManager.getSslHandler(channel.getPipeline());

            final HostnameVerifier hostnameVerifier = config.getHostnameVerifier();
            if (hostnameVerifier != null && sslHandler != null) {
                final String host = future.getURI().getHost();
                sslHandler.handshake().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture handshakeFuture) throws Exception {
                        if (handshakeFuture.isSuccess()) {
                            Channel channel = (Channel) handshakeFuture.getChannel();
                            SSLEngine engine = sslHandler.getEngine();
                            SSLSession session = engine.getSession();

                            if (LOGGER.isDebugEnabled())
                                LOGGER.debug("onFutureSuccess: session = {}, id = {}, isValid = {}, host = {}", session.toString(),
                                        Base64.encode(session.getId()), session.isValid(), host);
                            if (hostnameVerifier.verify(host, session)) {
                                writeRequest(channel, poolKey);
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
                writeRequest(f.getChannel(), poolKey);
            }

        } else {
            abortChannelPreemption(poolKey);
            Throwable cause = f.getCause();

            boolean canRetry = future.canRetry();
            LOGGER.debug("Trying to recover a dead cached channel {} with a retry value of {} ", f.getChannel(), canRetry);
            if (canRetry
                    && cause != null
                    && (cause instanceof ClosedChannelException || future.getState() != NettyResponseFuture.STATE.NEW || StackTraceInspector.abortOnDisconnectException(cause))) {

                if (!requestSender.retry(future, channel))
                    return;
            }

            LOGGER.debug("Failed to recover from exception: {} with channel {}", cause, f.getChannel());

            boolean printCause = f.getCause() != null && cause.getMessage() != null;
            ConnectException e = new ConnectException(printCause ? cause.getMessage() + " to " + future.getURI().toString() : future
                    .getURI().toString());
            if (cause != null) {
                e.initCause(cause);
            }
            future.abort(e);
        }
    }
}
