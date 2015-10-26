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

import static org.asynchttpclient.util.HttpUtils.getBaseUrl;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.ConnectException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.handler.AsyncHandlerExtensions;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.future.StackTraceInspector;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non Blocking connect.
 */
public final class NettyConnectListener<T> implements ChannelFutureListener {

    private final static Logger LOGGER = LoggerFactory.getLogger(NettyConnectListener.class);

    private final NettyRequestSender requestSender;
    private final NettyResponseFuture<T> future;
    private final ChannelManager channelManager;
    private final boolean channelPreempted;
    private final Object partitionKey;

    public NettyConnectListener(NettyResponseFuture<T> future,//
            NettyRequestSender requestSender,//
            ChannelManager channelManager,//
            boolean channelPreempted,//
            Object partitionKey) {
        this.future = future;
        this.requestSender = requestSender;
        this.channelManager = channelManager;
        this.channelPreempted = channelPreempted;
        this.partitionKey = partitionKey;
    }

    private void abortChannelPreemption() {
        if (channelPreempted)
            channelManager.abortChannelPreemption(partitionKey);
    }

    private void writeRequest(Channel channel) {

        LOGGER.debug("Using non-cached Channel {} for {} '{}'",
                channel,
                future.getNettyRequest().getHttpRequest().getMethod(),
                future.getNettyRequest().getHttpRequest().getUri());

        Channels.setAttribute(channel, future);
        
        if (future.isDone()) {
            abortChannelPreemption();
            return;
        }

        channelManager.registerOpenChannel(channel, partitionKey);
        future.attachChannel(channel, false);
        requestSender.writeRequest(future, channel);
    }

    private void onFutureSuccess(final Channel channel) throws Exception {
        
        Request request = future.getTargetRequest();
        Uri uri = request.getUri();

        // in case of proxy tunneling, we'll add the SslHandler later, after the CONNECT request
        if (future.getProxyServer() == null && uri.isSecured()) {
            SslHandler sslHandler = channelManager.addSslHandler(channel.pipeline(), uri, request.getVirtualHost());
            sslHandler.handshakeFuture().addListener(new GenericFutureListener<Future<Channel>>() {
                @Override
                public void operationComplete(Future<Channel> handshakeFuture) throws Exception {
                 
                    if (handshakeFuture.isSuccess()) {
                        final AsyncHandler<T> asyncHandler = future.getAsyncHandler();
                        if (asyncHandler instanceof AsyncHandlerExtensions)
                            AsyncHandlerExtensions.class.cast(asyncHandler).onSslHandshakeCompleted();

                        writeRequest(channel);
                    } else {
                        onFutureFailure(channel, handshakeFuture.cause());
                    }
                }
            });
        
        } else {
            writeRequest(channel);
        }
    }

    private void onFutureFailure(Channel channel, Throwable cause) {

        abortChannelPreemption();

        boolean canRetry = future.canRetry();
        LOGGER.debug("Trying to recover from failing to connect channel {} with a retry value of {} ", channel, canRetry);
        if (canRetry//
                && cause != null // FIXME when can we have a null cause?
                && (future.getChannelState() != ChannelState.NEW || StackTraceInspector.recoverOnNettyDisconnectException(cause))) {

            if (requestSender.retry(future)) {
                return;
            }
        }

        LOGGER.debug("Failed to recover from connect exception: {} with channel {}", cause, channel);

        boolean printCause = cause != null && cause.getMessage() != null;
        String printedCause = printCause ? cause.getMessage() : getBaseUrl(future.getUri());
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
