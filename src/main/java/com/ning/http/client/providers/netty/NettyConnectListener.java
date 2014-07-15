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
package com.ning.http.client.providers.netty;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.uri.UriComponents;
import com.ning.http.util.Base64;
import com.ning.http.util.ProxyUtils;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

/**
 * Non Blocking connect.
 */
final class NettyConnectListener<T> implements ChannelFutureListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(NettyConnectListener.class);
    private final AsyncHttpClientConfig config;
    private final NettyResponseFuture<T> future;
    private final HttpRequest nettyRequest;

    private NettyConnectListener(AsyncHttpClientConfig config, NettyResponseFuture<T> future) {
        this.config = config;
        this.future = future;
        this.nettyRequest = future.getNettyRequest();
    }

    public NettyResponseFuture<T> future() {
        return future;
    }

    public final void operationComplete(ChannelFuture f) throws Exception {
        Channel channel = f.getChannel();
        if (f.isSuccess()) {
            channel.getPipeline().getContext(NettyAsyncHttpProvider.class).setAttachment(future);
            final SslHandler sslHandler = (SslHandler) channel.getPipeline().get(NettyAsyncHttpProvider.SSL_HANDLER);

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
                            if (!hostnameVerifier.verify(host, session)) {
                                ConnectException exception = new ConnectException("HostnameVerifier exception");
                                future.abort(exception);
                                throw exception;
                            } else {
                                future.provider().writeRequest(channel, config, future);
                            }
                        }
                    }
                });
            } else {
                future.provider().writeRequest(f.getChannel(), config, future);
            }

        } else {
            Throwable cause = f.getCause();

            boolean canRetry = future.canRetry();
            LOGGER.debug("Trying to recover a dead cached channel {} with a retry value of {} ", f.getChannel(), canRetry);
            if (canRetry
                    && cause != null
                    && (NettyAsyncHttpProvider.abortOnDisconnectException(cause) || cause instanceof ClosedChannelException || future
                            .getState() != NettyResponseFuture.STATE.NEW)) {

                LOGGER.debug("Retrying {} ", nettyRequest);
                if (future.provider().remotelyClosed(channel, future)) {
                    return;
                }
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

    public static class Builder<T> {
        private final AsyncHttpClientConfig config;

        private final Request request;
        private final AsyncHandler<T> asyncHandler;
        private NettyResponseFuture<T> future;
        private final NettyAsyncHttpProvider provider;
        private final ChannelBuffer buffer;

        public Builder(AsyncHttpClientConfig config,//
                Request request,//
                AsyncHandler<T> asyncHandler,//
                NettyResponseFuture<T> future,//
                NettyAsyncHttpProvider provider,//
                ChannelBuffer buffer) {

            this.config = config;
            this.request = request;
            this.asyncHandler = asyncHandler;
            this.future = future;
            this.provider = provider;
            this.buffer = buffer;
        }

        public NettyConnectListener<T> build(final UriComponents uri) throws IOException {
            ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
            HttpRequest nettyRequest = NettyAsyncHttpProvider.buildRequest(config, request, uri, true, buffer, proxyServer);
            if (future == null) {
                future = NettyAsyncHttpProvider.newFuture(uri, request, asyncHandler, nettyRequest, config, provider, proxyServer);
            } else {
                future.setNettyRequest(nettyRequest);
                future.setRequest(request);
            }
            return new NettyConnectListener<T>(config, future);
        }
    }
}
