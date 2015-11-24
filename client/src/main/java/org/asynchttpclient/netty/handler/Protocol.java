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
package org.asynchttpclient.netty.handler;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.HttpConstants.Methods.*;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.*;
import static org.asynchttpclient.util.HttpUtils.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;

import java.util.HashSet;
import java.util.Set;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.MiscUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Protocol {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final ChannelManager channelManager;
    protected final AsyncHttpClientConfig config;
    protected final NettyRequestSender requestSender;

    private final boolean hasResponseFilters;
    protected final boolean hasIOExceptionFilters;
    private final MaxRedirectException maxRedirectException;

    public static final Set<Integer> REDIRECT_STATUSES = new HashSet<>();
    static {
        REDIRECT_STATUSES.add(MOVED_PERMANENTLY_301);
        REDIRECT_STATUSES.add(FOUND_302);
        REDIRECT_STATUSES.add(SEE_OTHER_303);
        REDIRECT_STATUSES.add(TEMPORARY_REDIRECT_307);
    }

    public Protocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyRequestSender requestSender) {
        this.channelManager = channelManager;
        this.config = config;
        this.requestSender = requestSender;

        hasResponseFilters = !config.getResponseFilters().isEmpty();
        hasIOExceptionFilters = !config.getIoExceptionFilters().isEmpty();
        maxRedirectException = new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
    }

    public abstract void handle(Channel channel, NettyResponseFuture<?> future, Object message) throws Exception;

    public abstract void onError(NettyResponseFuture<?> future, Throwable error);

    public abstract void onClose(NettyResponseFuture<?> future);

    private HttpHeaders propagatedHeaders(Request request, Realm realm, boolean keepBody) {

        HttpHeaders headers = request.getHeaders()//
                .remove(HttpHeaders.Names.HOST)//
                .remove(HttpHeaders.Names.CONTENT_LENGTH);

        if (!keepBody) {
            headers.remove(HttpHeaders.Names.CONTENT_TYPE);
        }

        if (realm != null && realm.getScheme() == AuthScheme.NTLM) {
            headers.remove(AUTHORIZATION)//
                    .remove(PROXY_AUTHORIZATION);
        }
        return headers;
    }

    protected boolean exitAfterHandlingRedirect(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode,//
            Realm realm) throws Exception {

        if (followRedirect(config, request)) {
            if (future.incrementAndGetCurrentRedirectCount() >= config.getMaxRedirects()) {
                throw maxRedirectException;

            } else {
                // We must allow auth handling again.
                future.getInAuth().set(false);
                future.getInProxyAuth().set(false);

                String originalMethod = request.getMethod();
                boolean switchToGet = !originalMethod.equals(GET) && (statusCode == MOVED_PERMANENTLY_301 || statusCode == SEE_OTHER_303 || (statusCode == FOUND_302 && !config.isStrict302Handling()));
                boolean keepBody = statusCode == TEMPORARY_REDIRECT_307 || (statusCode == FOUND_302 && config.isStrict302Handling());

                final RequestBuilder requestBuilder = new RequestBuilder(switchToGet ? GET : originalMethod)//
                        .setCookies(request.getCookies())//
                        .setChannelPoolPartitioning(request.getChannelPoolPartitioning())//
                        .setFollowRedirect(true)//
                        .setLocalAddress(request.getLocalAddress())//
                        .setNameResolver(request.getNameResolver())//
                        .setProxyServer(request.getProxyServer())//
                        .setRealm(request.getRealm())//
                        .setRequestTimeout(request.getRequestTimeout());

                if (keepBody) {
                    requestBuilder.setCharset(request.getCharset());
                    if (MiscUtils.isNonEmpty(request.getFormParams()))
                        requestBuilder.setFormParams(request.getFormParams());
                    else if (request.getStringData() != null)
                        requestBuilder.setBody(request.getStringData());
                    else if (request.getByteData() != null)
                        requestBuilder.setBody(request.getByteData());
                    else if (request.getByteBufferData() != null)
                        requestBuilder.setBody(request.getByteBufferData());
                    else if (request.getBodyGenerator() != null)
                        requestBuilder.setBody(request.getBodyGenerator());
                }

                requestBuilder.setHeaders(propagatedHeaders(request, realm, keepBody));

                // in case of a redirect from HTTP to HTTPS, future
                // attributes might change
                final boolean initialConnectionKeepAlive = future.isKeepAlive();
                final Object initialPartitionKey = future.getPartitionKey();

                HttpHeaders responseHeaders = response.headers();
                String location = responseHeaders.get(HttpHeaders.Names.LOCATION);
                Uri newUri = Uri.create(future.getUri(), location);

                logger.debug("Redirecting to {}", newUri);

                for (String cookieStr : responseHeaders.getAll(HttpHeaders.Names.SET_COOKIE)) {
                    Cookie c = CookieDecoder.decode(cookieStr);
                    if (c != null)
                        requestBuilder.addOrReplaceCookie(c);
                }

                boolean sameBase = isSameBase(request.getUri(), newUri);

                if (sameBase) {
                    // we can only assume the virtual host is still valid if the baseUrl is the same
                    requestBuilder.setVirtualHost(request.getVirtualHost());
                }

                final Request nextRequest = requestBuilder.setUri(newUri).build();
                future.setTargetRequest(nextRequest);

                logger.debug("Sending redirect to {}", newUri);

                if (future.isKeepAlive() && !HttpHeaders.isTransferEncodingChunked(response)) {

                    if (sameBase) {
                        future.setReuseChannel(true);
                        // we can't directly send the next request because we still have to received LastContent
                        requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
                    } else {
                        channelManager.drainChannelAndOffer(channel, future, initialConnectionKeepAlive, initialPartitionKey);
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
        return false;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected boolean exitAfterProcessingFilters(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            AsyncHandler<?> handler, //
            HttpResponseStatus status,//
            HttpResponseHeaders responseHeaders) {

        if (hasResponseFilters) {
            FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(future.getCurrentRequest()).responseStatus(status).responseHeaders(responseHeaders)
                    .build();

            for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                try {
                    fc = asyncFilter.filter(fc);
                    // FIXME Is it worth protecting against this?
                    assertNotNull("fc", "filterContext");
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
