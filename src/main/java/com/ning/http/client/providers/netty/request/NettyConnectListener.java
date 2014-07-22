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
package com.ning.http.client.providers.netty.request;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.future.StackTraceInspector;
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
    private final HttpRequest nettyRequest;
    private final NettyRequestSender nettyRequestSender;
    private final ChannelManager channelManager;
    private final boolean channelPreempted;
    private final String poolKey;

    public NettyConnectListener(AsyncHttpClientConfig config,//
            NettyResponseFuture<T> future,//
            NettyRequestSender nettyRequestSender,//
            ChannelManager channelManager,//
            boolean channelPreempted,//
            String poolKey) {
        this.config = config;
        this.future = future;
        this.nettyRequest = future.getNettyRequest();
        this.nettyRequestSender = nettyRequestSender;
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

        LOGGER.debug("\nNon cached request \n{}\n\nusing Channel \n{}\n", future.getNettyRequest(), channel);

        if (future.isDone()) {
            abortChannelPreemption(poolKey);
            return;
        }

        channelManager.registerOpenChannel(channel);
        future.attachChannel(channel, false);
        nettyRequestSender.writeRequest(channel, config, future);
    }

    public final void operationComplete(ChannelFuture f) throws Exception {
        Channel channel = f.getChannel();
        if (f.isSuccess()) {
            Channels.setAttachment(channel, future);
            final SslHandler sslHandler = (SslHandler) channel.getPipeline().get(ChannelManager.SSL_HANDLER);

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

                LOGGER.debug("Retrying {} ", nettyRequest);
                if (!nettyRequestSender.retry(channel, future))
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
