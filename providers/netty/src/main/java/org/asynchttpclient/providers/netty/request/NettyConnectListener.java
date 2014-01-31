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
import io.netty.handler.ssl.SslHandler;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.StackTraceInspector;
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

    public NettyConnectListener(AsyncHttpClientConfig config, NettyRequestSender requestSender, NettyResponseFuture<T> future) {
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

        requestSender.writeRequest(future, channel);
    }

    public void onFutureFailure(Channel channel, Throwable cause) {

        boolean canRetry = future.canRetry();
        LOGGER.debug("Trying to recover a dead cached channel {} with a retry value of {} ", channel, canRetry);
        if (canRetry && cause != null
                && (StackTraceInspector.abortOnDisconnectException(cause) || cause instanceof ClosedChannelException || future.getState() != NettyResponseFuture.STATE.NEW)) {

            LOGGER.debug("Retrying {} ", future.getNettyRequest());
            if (requestSender.retry(future, channel)) {
                return;
            }
        }

        LOGGER.debug("Failed to recover from exception: {} with channel {}", cause, channel);

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
}
