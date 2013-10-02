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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SslHandler;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.NettyResponseFutures;
import org.asynchttpclient.util.ProxyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non Blocking connect.
 */
// FIXME Netty 3: NettyConnectListener don't need to be passed the request as
// the future has it too
final class NettyConnectListener<T> implements ChannelFutureListener {
    private final static Logger logger = LoggerFactory.getLogger(NettyConnectListener.class);
    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;
    private final NettyResponseFuture<T> future;

    private NettyConnectListener(AsyncHttpClientConfig config, NettyRequestSender requestSender, NettyResponseFuture<T> future) {
        this.requestSender = requestSender;
        this.config = config;
        this.future = future;
    }

    public NettyResponseFuture<T> future() {
        return future;
    }

    public void onFutureSuccess(final Channel channel) throws ConnectException {
        Channels.setDefaultAttribute(channel, future);
        SslHandler sslHandler = Channels.getSslHandler(channel);

        if (sslHandler != null && !config.getHostnameVerifier().verify(future.getURI().getHost(), sslHandler.engine().getSession())) {
            ConnectException exception = new ConnectException("HostnameVerifier exception");
            future.abort(exception);
            throw exception;
        }

        requestSender.writeRequest(channel, config, future);
    }

    public void onFutureFailure(Channel channel, Throwable cause) {

        logger.debug("Trying to recover a dead cached channel {} with a retry value of {} ", channel, future.canRetry());
        if (future.canRetry() && cause != null
                && (NettyResponseFutures.abortOnDisconnectException(cause) || cause instanceof ClosedChannelException || future.getState() != NettyResponseFuture.STATE.NEW)) {

            logger.debug("Retrying {} ", future.getNettyRequest());
            // FIXME Netty 3 use the wrong statement
            if (requestSender.retry(channel, future)) {
                return;
            }
        }

        logger.debug("Failed to recover from exception: {} with channel {}", cause, channel);

        boolean printCause = cause != null && cause.getMessage() != null;
        ConnectException e = new ConnectException(printCause ? cause.getMessage() + " to " + future.getURI().toString() : future.getURI().toString());
        if (cause != null) {
            e.initCause(cause);
        }
        future.abort(e);
    }

    public final void operationComplete(ChannelFuture f) throws Exception {
        if (f.isSuccess()) {
            onFutureSuccess(f.channel());
        } else {
            onFutureFailure(f.channel(), f.cause());
        }
    }

    public static class Builder<T> {
        private final AsyncHttpClientConfig config;

        private final NettyRequestSender requestSender;
        private final Request request;
        private final AsyncHandler<T> asyncHandler;
        private NettyResponseFuture<T> future;

        // FIXME Netty3 useless constructor
        public Builder(AsyncHttpClientConfig config, NettyRequestSender requestSender, Request request, AsyncHandler<T> asyncHandler, NettyResponseFuture<T> future) {

            this.config = config;
            this.requestSender = requestSender;
            this.request = request;
            this.asyncHandler = asyncHandler;
            this.future = future;
        }

        public NettyConnectListener<T> build(final URI uri) throws IOException {
            ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
            HttpRequest nettyRequest = NettyRequests.newNettyRequest(config, request, uri, true, proxyServer);
            if (future == null) {
                future = NettyResponseFutures.newNettyResponseFuture(uri, request, asyncHandler, nettyRequest, config, proxyServer);
            } else {
                future.setNettyRequest(nettyRequest);
                future.setRequest(request);
            }
            return new NettyConnectListener<T>(config, requestSender, future);
        }
    }
}
