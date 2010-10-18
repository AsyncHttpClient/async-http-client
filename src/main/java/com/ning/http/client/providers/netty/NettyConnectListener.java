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
import com.ning.http.client.Request;
import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;
import com.ning.http.util.AsyncHttpProviderUtils;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.ssl.SslHandler;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Non Blocking connect.
 */
final class NettyConnectListener<T> implements ChannelFutureListener {

    private final AsyncHttpClientConfig config;
    private final NettyResponseFuture<T> future;
    private final HttpRequest nettyRequest;
    private final AtomicBoolean handshakeDone = new AtomicBoolean(false);

    private NettyConnectListener(AsyncHttpClientConfig config,
                            NettyResponseFuture<T> future,
                            HttpRequest nettyRequest) {
        this.config = config;
        this.future = future;
        this.nettyRequest = nettyRequest;
    }

    public NettyResponseFuture<T> future() {
        return future;
    }

    public final void operationComplete(ChannelFuture f) throws Exception {
        try {
            if (f.isSuccess()) {
                if (!handshakeDone.getAndSet(true) && f.getChannel().getPipeline().get(NettyAsyncHttpProvider.SSL_HANDLER) != null ){
                    ((SslHandler)f.getChannel().getPipeline().get(NettyAsyncHttpProvider.SSL_HANDLER)).handshake().addListener(this);
                    return;
                }
                f.getChannel().getPipeline().getContext(NettyAsyncHttpProvider.class).

                setAttachment(future);
                future.provider().executeRequest(f.getChannel(), config, future, nettyRequest);
            } else {
                ConnectException e = new ConnectException(f.getCause() != null ? f.getCause().getMessage() : future.getURI().toString());
                e.initCause(f.getCause());
                future.abort(e);
            }
        } catch (ConnectException ex) {
            future.abort(ex);
        }
    }

    public static class Builder<T> {
        private final Logger log = LogManager.getLogger(Builder.class);
        private final AsyncHttpClientConfig config;
        private final Request request;
        private final AsyncHandler<T> asyncHandler;
        private NettyResponseFuture<T> future;
        private final NettyAsyncHttpProvider provider;

        public Builder(AsyncHttpClientConfig config, Request request, AsyncHandler<T> asyncHandler, NettyAsyncHttpProvider provider) {
            this.config = config;
            this.request = request;
            this.asyncHandler = asyncHandler;
            this.future = null;
            this.provider = provider;
        }

        public Builder(AsyncHttpClientConfig config, Request request, AsyncHandler<T> asyncHandler,
                       NettyResponseFuture<T> future, NettyAsyncHttpProvider provider) {
            this.config = config;
            this.request = request;
            this.asyncHandler = asyncHandler;
            this.future = future;
            this.provider = provider;
        }

        public NettyConnectListener<T> build() throws IOException {
            URI uri = AsyncHttpProviderUtils.createUri(request.getRawUrl().replace(" ", "%20"));
            HttpRequest nettyRequest = NettyAsyncHttpProvider.buildRequest(config, request, uri, true, null);
            if (future == null) {
                future = new NettyResponseFuture<T>(uri, request, asyncHandler,
                        nettyRequest, NettyAsyncHttpProvider.requestTimeout(config, request.getPerRequestConfig()), provider);
            }
            return new NettyConnectListener<T>(config, future, nettyRequest);
        }
    }
}
