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
package org.asynchttpclient.providers.netty3.handler;

import static org.asynchttpclient.providers.netty.commons.util.HttpUtils.HTTP;
import static org.asynchttpclient.providers.netty.commons.util.HttpUtils.WEBSOCKET;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.followRedirect;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ACCEPT;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_CHARSET;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_ENCODING;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_LANGUAGE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.REFERER;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.USER_AGENT;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.SEE_OTHER;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.MaxRedirectException;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.providers.netty3.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty3.channel.ChannelManager;
import org.asynchttpclient.providers.netty3.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty3.request.NettyRequestSender;
import org.asynchttpclient.uri.Uri;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Protocol {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final ChannelManager channelManager;
    protected final AsyncHttpClientConfig config;
    protected final NettyAsyncHttpProviderConfig nettyConfig;
    protected final NettyRequestSender requestSender;

    private final boolean hasResponseFilters;
    protected final boolean hasIOExceptionFilters;
    private final MaxRedirectException maxRedirectException;

    public static final Set<Integer> REDIRECT_STATUSES = new HashSet<>();
    static {
        REDIRECT_STATUSES.add(MOVED_PERMANENTLY.getCode());
        REDIRECT_STATUSES.add(FOUND.getCode());
        REDIRECT_STATUSES.add(SEE_OTHER.getCode());
        REDIRECT_STATUSES.add(TEMPORARY_REDIRECT.getCode());
    }

    public static final Set<String> PROPAGATED_ON_REDIRECT_HEADERS = new HashSet<>();
    static {
        PROPAGATED_ON_REDIRECT_HEADERS.add(ACCEPT.toLowerCase(Locale.US));
        PROPAGATED_ON_REDIRECT_HEADERS.add(ACCEPT_CHARSET.toLowerCase(Locale.US));
        PROPAGATED_ON_REDIRECT_HEADERS.add(ACCEPT_ENCODING.toLowerCase(Locale.US));
        PROPAGATED_ON_REDIRECT_HEADERS.add(ACCEPT_LANGUAGE.toLowerCase(Locale.US));
        PROPAGATED_ON_REDIRECT_HEADERS.add(REFERER.toLowerCase(Locale.US));
        PROPAGATED_ON_REDIRECT_HEADERS.add(USER_AGENT.toLowerCase(Locale.US));
    }

    public Protocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig,
            NettyRequestSender requestSender) {
        this.channelManager = channelManager;
        this.config = config;
        this.nettyConfig = nettyConfig;
        this.requestSender = requestSender;

        hasResponseFilters = !config.getResponseFilters().isEmpty();
        hasIOExceptionFilters = !config.getIOExceptionFilters().isEmpty();
        maxRedirectException = new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
        maxRedirectException.setStackTrace(new StackTraceElement[0]);
    }

    public abstract void handle(Channel channel, NettyResponseFuture<?> future, Object message) throws Exception;

    public abstract void onError(NettyResponseFuture<?> future, Throwable e);

    public abstract void onClose(NettyResponseFuture<?> future);

    private FluentCaseInsensitiveStringsMap propagatedHeaders(Request request) {
        FluentCaseInsensitiveStringsMap redirectHeaders = new FluentCaseInsensitiveStringsMap();
        for (Map.Entry<String, List<String>> headerEntry : request.getHeaders()) {
            String headerName = headerEntry.getKey();
            List<String> headerValues = headerEntry.getValue();
            if (PROPAGATED_ON_REDIRECT_HEADERS.contains(headerName.toLowerCase(Locale.US)))
                redirectHeaders.add(headerName, headerValues);
        }
        return redirectHeaders;
    }

    protected boolean exitAfterHandlingRedirect(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode) throws Exception {

        if (followRedirect(config, request) && REDIRECT_STATUSES.contains(statusCode)) {
            if (future.incrementAndGetCurrentRedirectCount() >= config.getMaxRedirects()) {
                throw maxRedirectException;

            } else {
                // We must allow 401 handling again.
                future.getAndSetAuth(false);

                HttpHeaders responseHeaders = response.headers();
                String location = responseHeaders.get(HttpHeaders.Names.LOCATION);
                Uri uri = Uri.create(future.getUri(), location);

                if (!uri.equals(future.getUri())) {
                    final RequestBuilder requestBuilder = new RequestBuilder(future.getRequest());

                    // if we are to strictly handle 302, we should keep the original method (which browsers don't)
                    // 303 must force GET
                    if ((statusCode == FOUND.getCode() && !config.isStrict302Handling()) || statusCode == SEE_OTHER.getCode())
                        requestBuilder.setMethod("GET");

                    // in case of a redirect from HTTP to HTTPS, future attributes might change
                    final boolean initialConnectionKeepAlive = future.isKeepAlive();
                    final String initialPoolKey = future.getPartitionId();

                    future.setUri(uri);
                    String newUrl = uri.toUrl();
                    if (request.getUri().getScheme().startsWith(WEBSOCKET)) {
                        newUrl = newUrl.replaceFirst(HTTP, WEBSOCKET);
                    }

                    logger.debug("Redirecting to {}", newUrl);

                    for (String cookieStr : responseHeaders.getAll(HttpHeaders.Names.SET_COOKIE)) {
                        Cookie c = CookieDecoder.decode(cookieStr);
                        if (c != null)
                            requestBuilder.addOrReplaceCookie(c);
                    }

                    requestBuilder.setHeaders(propagatedHeaders(future.getRequest()));

                    final Request nextRequest = requestBuilder.setUrl(newUrl).build();

                    logger.debug("Sending redirect to {}", request.getUri());

                    if (future.isKeepAlive() && !HttpHeaders.isTransferEncodingChunked(response) && !response.isChunked()) {
                        
                        boolean redirectToSameHost = request.getUri().getScheme().equals(nextRequest.getUri().getScheme())
                                && request.getUri().getHost().equals(nextRequest.getUri().getHost())
                                && request.getUri().getPort() == nextRequest.getUri().getPort();

                        if (redirectToSameHost) {
                            future.setReuseChannel(true);
                        } else {
                            channelManager.drainChannelAndOffer(channel, future, initialConnectionKeepAlive, initialPoolKey);
                        }

                    } else {
                        // redirect + chunking = WAT
                        channelManager.closeChannel(channel);
                    }

                    requestSender.sendNextRequest(nextRequest, future);
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
