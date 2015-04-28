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
package org.asynchttpclient.providers.netty4.handler;

import static io.netty.handler.codec.http.HttpHeaders.Names.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static io.netty.handler.codec.http.HttpHeaders.Names.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static io.netty.handler.codec.http.HttpResponseStatus.SEE_OTHER;
import static io.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;
import static org.asynchttpclient.providers.netty.commons.util.HttpUtils.HTTP;
import static org.asynchttpclient.providers.netty.commons.util.HttpUtils.WEBSOCKET;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.followRedirect;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.MaxRedirectException;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.providers.netty4.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty4.channel.ChannelManager;
import org.asynchttpclient.providers.netty4.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty4.request.NettyRequestSender;
import org.asynchttpclient.uri.Uri;
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
        REDIRECT_STATUSES.add(MOVED_PERMANENTLY.code());
        REDIRECT_STATUSES.add(FOUND.code());
        REDIRECT_STATUSES.add(SEE_OTHER.code());
        REDIRECT_STATUSES.add(TEMPORARY_REDIRECT.code());
    }

    public Protocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig,
            NettyRequestSender requestSender) {
        this.channelManager = channelManager;
        this.config = config;
        this.requestSender = requestSender;
        this.nettyConfig = nettyConfig;

        hasResponseFilters = !config.getResponseFilters().isEmpty();
        hasIOExceptionFilters = !config.getIOExceptionFilters().isEmpty();
        maxRedirectException = new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
    }

    public abstract void handle(Channel channel, NettyResponseFuture<?> future, Object message) throws Exception;

    public abstract void onError(NettyResponseFuture<?> future, Throwable error);

    public abstract void onClose(NettyResponseFuture<?> future);

    private FluentCaseInsensitiveStringsMap propagatedHeaders(Request request, Realm realm, boolean switchToGet) {
        
        FluentCaseInsensitiveStringsMap originalHeaders = request.getHeaders();
        originalHeaders.remove(HOST);
        originalHeaders.remove(CONTENT_LENGTH);
        if (switchToGet)
            originalHeaders.remove(CONTENT_TYPE);
        if (realm != null && realm.getScheme() == AuthScheme.NTLM) {
            originalHeaders.remove(AUTHORIZATION);
            originalHeaders.remove(PROXY_AUTHORIZATION);
        }
        return originalHeaders;
    }

    protected boolean exitAfterHandlingRedirect(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode,//
            Realm realm) throws Exception {

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
                    boolean switchToGet = !request.getMethod().equals("GET") && (statusCode == 303 || (statusCode == 302 && !config.isStrict302Handling()));
                    if (switchToGet)
                        requestBuilder.setMethod("GET");

                    // in case of a redirect from HTTP to HTTPS, future attributes might change
                    final boolean initialConnectionKeepAlive = future.isKeepAlive();
                    final String initialPartition = future.getPartitionId();

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

                    requestBuilder.setHeaders(propagatedHeaders(future.getRequest(), realm, switchToGet));

                    final Request nextRequest = requestBuilder.setUrl(newUrl).build();

                    logger.debug("Sending redirect to {}", request.getUri());

                    if (future.isKeepAlive() && !HttpHeaders.isTransferEncodingChunked(response)) {
                        
                        boolean redirectToSameHost = request.getUri().getScheme().equals(nextRequest.getUri().getScheme())
                                && request.getUri().getHost().equals(nextRequest.getUri().getHost())
                                && request.getUri().getPort() == nextRequest.getUri().getPort();

                        if (redirectToSameHost) {
                            future.setReuseChannel(true);
                            requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);

                        } else {
                            channelManager.drainChannelAndOffer(channel, future, initialConnectionKeepAlive, initialPartition);
                            requestSender.sendNextRequest(nextRequest, future);
                        }

                    } else {
                        // redirect + chunking = WAT
                        channelManager.closeChannel(channel);
                        requestSender.sendNextRequest(nextRequest, future);
                    }
                    
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
