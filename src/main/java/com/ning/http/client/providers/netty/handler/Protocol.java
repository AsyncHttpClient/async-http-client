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
package com.ning.http.client.providers.netty.handler;

import static com.ning.http.client.providers.netty.util.HttpUtils.HTTP;
import static com.ning.http.client.providers.netty.util.HttpUtils.WEBSOCKET;
import static com.ning.http.util.AsyncHttpProviderUtils.followRedirect;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.SEE_OTHER;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.cookie.CookieDecoder;
import com.ning.http.client.date.TimeConverter;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.providers.netty.Callback;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.client.uri.Uri;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public abstract class Protocol {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final ChannelManager channelManager;
    protected final AsyncHttpClientConfig config;
    protected final NettyAsyncHttpProviderConfig nettyConfig;
    protected final NettyRequestSender requestSender;

    private final boolean hasResponseFilters;
    protected final boolean hasIOExceptionFilters;
    private final TimeConverter timeConverter;

    public static final Set<Integer> REDIRECT_STATUSES = new HashSet<Integer>();
    static {
        REDIRECT_STATUSES.add(MOVED_PERMANENTLY.getCode());
        REDIRECT_STATUSES.add(FOUND.getCode());
        REDIRECT_STATUSES.add(SEE_OTHER.getCode());
        REDIRECT_STATUSES.add(TEMPORARY_REDIRECT.getCode());
    }

    public Protocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig,
            NettyRequestSender requestSender) {
        this.channelManager = channelManager;
        this.config = config;
        this.nettyConfig = nettyConfig;
        this.requestSender = requestSender;

        hasResponseFilters = !config.getResponseFilters().isEmpty();
        hasIOExceptionFilters = !config.getIOExceptionFilters().isEmpty();
        timeConverter = config.getTimeConverter();
    }

    public abstract void handle(Channel channel, NettyResponseFuture<?> future, Object message) throws Exception;

    public abstract void onError(NettyResponseFuture<?> future, Throwable e);

    public abstract void onClose(NettyResponseFuture<?> future);

    protected boolean exitAfterHandlingRedirect(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode) throws Exception {

        if (followRedirect(config, request) && REDIRECT_STATUSES.contains(statusCode)) {
            if (future.incrementAndGetCurrentRedirectCount() >= config.getMaxRedirects()) {
                throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());

            } else {
                // We must allow 401 handling again.
                future.getAndSetAuth(false);

                HttpHeaders responseHeaders = response.headers();
                String location = responseHeaders.get(HttpHeaders.Names.LOCATION);
                Uri uri = Uri.create(future.getUri(), location);

                if (!uri.equals(future.getUri())) {
                    final RequestBuilder requestBuilder = new RequestBuilder(future.getRequest());

                    if (!config.isRemoveQueryParamOnRedirect())
                        requestBuilder.addQueryParams(future.getRequest().getQueryParams());

                    // if we are to strictly handle 302, we should keep the original method (which browsers don't)
                    // 303 must force GET
                    if ((statusCode == FOUND.getCode() && !config.isStrict302Handling()) || statusCode == SEE_OTHER.getCode())
                        requestBuilder.setMethod("GET");

                    // in case of a redirect from HTTP to HTTPS, future attributes might change
                    final boolean initialConnectionKeepAlive = future.isKeepAlive();
                    final String initialPoolKey = channelManager.getPartitionId(future);

                    future.setUri(uri);
                    String newUrl = uri.toUrl();
                    if (request.getUri().getScheme().startsWith(WEBSOCKET)) {
                        newUrl = newUrl.replaceFirst(HTTP, WEBSOCKET);
                    }

                    logger.debug("Redirecting to {}", newUrl);

                    for (String cookieStr : responseHeaders.getAll(HttpHeaders.Names.SET_COOKIE)) {
                        Cookie c = CookieDecoder.decode(cookieStr, timeConverter);
                        if (c != null)
                            requestBuilder.addOrReplaceCookie(c);
                    }

                    Callback callback = channelManager.newDrainCallback(future, channel, initialConnectionKeepAlive, initialPoolKey);

                    if (HttpHeaders.isTransferEncodingChunked(response)) {
                        // We must make sure there is no bytes left before
                        // executing the next request.
                        // FIXME investigate this
                        Channels.setAttribute(channel, callback);
                    } else {
                        // FIXME don't understand: this offers the connection to the pool, or even closes it, while the
                        // request has not been sent, right?
                        callback.call();
                    }

                    Request redirectRequest = requestBuilder.setUrl(newUrl).build();
                    // FIXME why not reuse the channel is same host?
                    requestSender.sendNextRequest(redirectRequest, future);
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected boolean exitAfterProcessingFilters(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            AsyncHandler<?> handler, //
            HttpResponseStatus status,//
            HttpResponseHeaders responseHeaders) throws IOException {

        if (hasResponseFilters) {
            FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(future.getRequest())
                    .responseStatus(status).responseHeaders(responseHeaders).build();

            for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                try {
                    fc = asyncFilter.filter(fc);
                    // FIXME Is it worth protecting against this?
                    if (fc == null) {
                        throw new NullPointerException("FilterContext is null");
                    }
                } catch (FilterException efe) {
                    requestSender.abort(channel, future, efe);
                }
            }

            // The handler may have been wrapped.
            future.setAsyncHandler(fc.getAsyncHandler());

            // The request has changed
            if (fc.replayRequest()) {
                requestSender.replayRequest(future, fc, channel);
                return true;
            }
        }
        return false;
    }
}
