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
package org.asynchttpclient.providers.netty.handler;

import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static io.netty.handler.codec.http.HttpResponseStatus.SEE_OTHER;
import static io.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;
import static org.asynchttpclient.providers.netty.util.HttpUtil.HTTP;
import static org.asynchttpclient.providers.netty.util.HttpUtil.WEBSOCKET;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.MaxRedirectException;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;
import org.asynchttpclient.date.TimeConverter;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.uri.UriComponents;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public abstract class Protocol {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    protected final Channels channels;
    protected final AsyncHttpClientConfig config;
    protected final NettyAsyncHttpProviderConfig nettyConfig;
    protected final NettyRequestSender requestSender;

    private final boolean hasResponseFilters;
    protected final boolean hasIOExceptionFilters;
    private final TimeConverter timeConverter;

    public static final Set<io.netty.handler.codec.http.HttpResponseStatus> REDIRECT_STATUSES = new HashSet<io.netty.handler.codec.http.HttpResponseStatus>();

    static {
        REDIRECT_STATUSES.add(MOVED_PERMANENTLY);
        REDIRECT_STATUSES.add(FOUND);
        REDIRECT_STATUSES.add(SEE_OTHER);
        REDIRECT_STATUSES.add(TEMPORARY_REDIRECT);
    }

    public Protocol(Channels channels, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig,
            NettyRequestSender requestSender) {
        this.channels = channels;
        this.config = config;
        this.requestSender = requestSender;
        this.nettyConfig = nettyConfig;

        hasResponseFilters = !config.getResponseFilters().isEmpty();
        hasIOExceptionFilters = !config.getIOExceptionFilters().isEmpty();
        timeConverter = config.getTimeConverter();
    }

    public abstract void handle(Channel channel, NettyResponseFuture<?> future, Object message) throws Exception;

    public abstract void onError(Channel channel, Throwable error);

    public abstract void onClose(Channel channel);

    protected boolean handleRedirectAndExit(Request request, NettyResponseFuture<?> future, HttpResponse response, final Channel channel)
            throws Exception {

        io.netty.handler.codec.http.HttpResponseStatus status = response.getStatus();

        if (AsyncHttpProviderUtils.followRedirect(config, request) && REDIRECT_STATUSES.contains(status)) {
            if (future.incrementAndGetCurrentRedirectCount() >= config.getMaxRedirects()) {
                throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());

            } else {
                // We must allow 401 handling again.
                future.getAndSetAuth(false);

                String location = response.headers().get(HttpHeaders.Names.LOCATION);
                UriComponents uri = UriComponents.create(future.getURI(), location);

                if (!uri.equals(future.getURI())) {
                    final RequestBuilder requestBuilder = new RequestBuilder(future.getRequest());

                    if (!config.isRemoveQueryParamOnRedirect())
                        requestBuilder.addQueryParams(future.getRequest().getQueryParams());

                    // FIXME why not do that for 301 and 307 too?
                    // FIXME I think condition is wrong
                    if ((status.equals(FOUND) || status.equals(SEE_OTHER)) && !(status.equals(FOUND) && config.isStrict302Handling())) {
                        requestBuilder.setMethod(HttpMethod.GET.name());
                    }

                    // in case of a redirect from HTTP to HTTPS, future attributes might change
                    final boolean initialConnectionKeepAlive = future.isKeepAlive();
                    final String initialPoolKey = channels.getPoolKey(future);

                    future.setURI(uri);
                    String newUrl = uri.toString();
                    if (request.getURI().getScheme().startsWith(WEBSOCKET)) {
                        newUrl = newUrl.replaceFirst(HTTP, WEBSOCKET);
                    }

                    logger.debug("Redirecting to {}", newUrl);

                    if (future.getHttpHeaders().contains(HttpHeaders.Names.SET_COOKIE2)) {
                        for (String cookieStr : future.getHttpHeaders().getAll(HttpHeaders.Names.SET_COOKIE2)) {
                            Cookie c = CookieDecoder.decode(cookieStr, timeConverter);
                            if (c != null) {
                                requestBuilder.addOrReplaceCookie(c);
                            }
                        }
                    } else if (future.getHttpHeaders().contains(HttpHeaders.Names.SET_COOKIE)) {
                        for (String cookieStr : future.getHttpHeaders().getAll(HttpHeaders.Names.SET_COOKIE)) {
                            Cookie c = CookieDecoder.decode(cookieStr, timeConverter);
                            if (c != null) {
                                requestBuilder.addOrReplaceCookie(c);
                            }
                        }
                    }

                    Callback callback = new Callback(future) {
                        public void call() throws Exception {
                            if (!(initialConnectionKeepAlive && channel.isActive() && channels.offerToPool(initialPoolKey, channel))) {
                                channels.finishChannel(channel);
                            }
                        }
                    };

                    if (HttpHeaders.isTransferEncodingChunked(response)) {
                        // We must make sure there is no bytes left before
                        // executing the next request.
                        // FIXME investigate this
                        Channels.setDefaultAttribute(channel, callback);
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

    protected boolean handleResponseFiltersReplayRequestAndExit(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponseStatus status,//
            HttpResponseHeaders responseHeaders) throws IOException {

        if (hasResponseFilters) {
            AsyncHandler<?> handler = future.getAsyncHandler();
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
                    channels.abort(future, efe);
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
