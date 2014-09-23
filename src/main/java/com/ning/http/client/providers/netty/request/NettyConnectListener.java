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
package com.ning.http.client.providers.netty.request;

import static com.ning.http.util.AsyncHttpProviderUtils.getBaseUrl;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandlerExtensions;
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

    private void onFutureSuccess(final Channel channel) throws ConnectException {
        Channels.setAttribute(channel, future);
        final SslHandler sslHandler = ChannelManager.getSslHandler(channel.getPipeline());

        final HostnameVerifier hostnameVerifier = config.getHostnameVerifier();
        if (hostnameVerifier != null && sslHandler != null) {
            final String host = future.getUri().getHost();
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
                            future.abort(new ConnectException("HostnameVerifier exception"));
                        }
                    }
                }
            });
        } else {
            writeRequest(channel, poolKey);
        }
    }

    private void onFutureFailure(Channel channel, Throwable cause) {
        abortChannelPreemption(poolKey);

        boolean canRetry = future.canRetry();
        LOGGER.debug("Trying to recover from failing to connect channel {} with a retry value of {} ", channel, canRetry);
        if (canRetry
                && cause != null
                && (cause instanceof ClosedChannelException || future.getState() != NettyResponseFuture.STATE.NEW || StackTraceInspector.abortOnDisconnectException(cause))) {

            if (!requestSender.retry(future))
                return;
        }

        LOGGER.debug("Failed to recover from connect exception: {} with channel {}", cause, channel);

        boolean printCause = cause != null && cause.getMessage() != null;
        String printedCause = printCause ? cause.getMessage() : getBaseUrl(future.getUri());
        ConnectException e = new ConnectException(printedCause);
        if (cause != null) {
            e.initCause(cause);
        }
        future.abort(e);
    }

    public final void operationComplete(ChannelFuture f) throws Exception {
        Channel channel = f.getChannel();
        if (f.isSuccess())
            onFutureSuccess(channel);
        else
            onFutureFailure(channel, f.getCause());
    }
}
