/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.handler;

import static com.ning.http.util.MiscUtils.isNonEmpty;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandlerExtensions;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.cookie.CookieDecoder;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.providers.netty.Callback;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.client.providers.netty.util.HttpUtil;
import com.ning.http.client.uri.UriComponents;
import com.ning.http.util.AsyncHttpProviderUtils;

import java.io.IOException;
import java.util.List;

public abstract class Protocol {

    private static final Logger LOGGER = LoggerFactory.getLogger(Protocol.class);

    protected final ChannelManager channelManager;
    protected final AsyncHttpClientConfig config;
    protected final NettyRequestSender nettyRequestSender;

    public Protocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyRequestSender nettyRequestSender) {
        this.channelManager = channelManager;
        this.config = config;
        this.nettyRequestSender = nettyRequestSender;
    }

    protected void replayRequest(final NettyResponseFuture<?> future, FilterContext fc, Channel channel) throws IOException {
        if (future.getAsyncHandler() instanceof AsyncHandlerExtensions)
            AsyncHandlerExtensions.class.cast(future.getAsyncHandler()).onRetry();

        final Request newRequest = fc.getRequest();
        future.setAsyncHandler(fc.getAsyncHandler());
        future.setState(NettyResponseFuture.STATE.NEW);
        future.touch();

        LOGGER.debug("\n\nReplaying Request {}\n for Future {}\n", newRequest, future);
        nettyRequestSender.drainChannel(channel, future);
        nettyRequestSender.nextRequest(newRequest, future);
        return;
    }

    protected boolean exitAfterHandlingRedirect(Channel channel, NettyResponseFuture<?> future, Request request, HttpResponse response,
            int statusCode) throws Exception {

        if (AsyncHttpProviderUtils.followRedirect(config, request)
                && (statusCode == 302 || statusCode == 301 || statusCode == 303 || statusCode == 307)) {

            if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {
                // allow 401 handling again
                future.getAndSetAuth(false);

                HttpHeaders responseHeaders = response.headers();

                String location = responseHeaders.get(HttpHeaders.Names.LOCATION);
                UriComponents uri = UriComponents.create(future.getURI(), location);
                if (!uri.equals(future.getURI())) {
                    final RequestBuilder nBuilder = new RequestBuilder(future.getRequest());

                    if (config.isRemoveQueryParamOnRedirect())
                        nBuilder.resetQuery();
                    else
                        nBuilder.addQueryParams(future.getRequest().getQueryParams());

                    if (!(statusCode < 302 || statusCode > 303) && !(statusCode == 302 && config.isStrict302Handling())) {
                        nBuilder.setMethod("GET");
                    }
                    final boolean initialConnectionKeepAlive = future.isKeepAlive();
                    final String initialPoolKey = channelManager.getPoolKey(future);
                    future.setURI(uri);
                    UriComponents newURI = uri;
                    String targetScheme = request.getURI().getScheme();
                    if (targetScheme.equals(HttpUtil.WEBSOCKET)) {
                        newURI = newURI.withNewScheme(HttpUtil.WEBSOCKET);
                    }
                    if (targetScheme.equals(HttpUtil.WEBSOCKET_SSL)) {
                        newURI = newURI.withNewScheme(HttpUtil.WEBSOCKET_SSL);
                    }

                    LOGGER.debug("Redirecting to {}", newURI);
                    List<String> setCookieHeaders = responseHeaders.getAll(HttpHeaders.Names.SET_COOKIE2);
                    if (!isNonEmpty(setCookieHeaders)) {
                        setCookieHeaders = responseHeaders.getAll(HttpHeaders.Names.SET_COOKIE);
                    }

                    for (String cookieStr : setCookieHeaders) {
                        nBuilder.addOrReplaceCookie(CookieDecoder.decode(cookieStr));
                    }

                    Callback ac = nettyRequestSender.newDrainCallable(future, channel, initialConnectionKeepAlive, initialPoolKey);

                    if (response.isChunked()) {
                        // We must make sure there is no bytes left before executing the next request.
                        Channels.setAttachment(channel, ac);
                    } else {
                        ac.call();
                    }
                    nettyRequestSender.nextRequest(nBuilder.setURI(newURI).build(), future);
                    return true;
                }
            } else {
                throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
            }
        }
        return false;
    }

    public abstract void handle(Channel channel, MessageEvent e, NettyResponseFuture<?> future) throws Exception;

    public abstract void onError(Channel channel, ExceptionEvent e);

    public abstract void onClose(Channel channel, ChannelStateEvent e);
}
