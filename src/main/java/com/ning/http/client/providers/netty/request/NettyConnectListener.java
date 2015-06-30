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

import com.ning.http.client.AsyncHandler;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandlerExtensions;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.future.StackTraceInspector;

import java.net.ConnectException;

/**
 * Non Blocking connect.
 */
public final class NettyConnectListener<T> implements ChannelFutureListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(NettyConnectListener.class);
    private final NettyResponseFuture<T> future;
    private final NettyRequestSender requestSender;
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

    public NettyResponseFuture<T> future() {
        return future;
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

        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onConnectionOpen();

        channelManager.registerOpenChannel(channel, partitionKey);
        future.attachChannel(channel, false);
        requestSender.writeRequest(future, channel);
    }

    private void onFutureSuccess(final Channel channel) throws ConnectException {

        SslHandler sslHandler = channel.getPipeline().get(SslHandler.class);

        if (sslHandler != null) {
            sslHandler.handshake().addListener(new ChannelFutureListener() {

                @Override
                public void operationComplete(ChannelFuture handshakeFuture) throws Exception {
                    if (handshakeFuture.isSuccess()) {
                        final AsyncHandler<T> asyncHandler = future.getAsyncHandler();
                        if (asyncHandler instanceof AsyncHandlerExtensions)
                            AsyncHandlerExtensions.class.cast(asyncHandler).onSslHandshakeCompleted();

                        writeRequest(channel);
                    } else {
                        onFutureFailure(channel, handshakeFuture.getCause());
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
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Trying to recover from failing to connect channel " + channel + " with a retry value of " + canRetry, cause);
        }
        if (canRetry
                && cause != null
                && (future.getState() != NettyResponseFuture.STATE.NEW || StackTraceInspector.recoverOnDisconnectException(cause))) {

            if (requestSender.retry(future))
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
