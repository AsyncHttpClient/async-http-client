/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler.intercept;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.*;
import static org.asynchttpclient.util.HttpUtils.*;
import static org.asynchttpclient.util.MiscUtils.*;

import java.util.HashSet;
import java.util.Set;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Redirect30xInterceptor {

    public static final Set<Integer> REDIRECT_STATUSES = new HashSet<>();
    static {
        REDIRECT_STATUSES.add(MOVED_PERMANENTLY_301);
        REDIRECT_STATUSES.add(FOUND_302);
        REDIRECT_STATUSES.add(SEE_OTHER_303);
        REDIRECT_STATUSES.add(TEMPORARY_REDIRECT_307);
    }
    
    private static final Logger LOGGER = LoggerFactory.getLogger(Redirect30xInterceptor.class);

    private final ChannelManager channelManager;
    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;
    private final MaxRedirectException maxRedirectException;
    
    public Redirect30xInterceptor(ChannelManager channelManager, AsyncHttpClientConfig config, NettyRequestSender requestSender) {
        this.channelManager = channelManager;
        this.config = config;
        this.requestSender = requestSender;
        maxRedirectException = trimStackTrace(new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects()));
    }
    
    public boolean exitAfterHandlingRedirect(//
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
                future.setInAuth(false);
                future.setInProxyAuth(false);

                String originalMethod = request.getMethod();
                boolean switchToGet = !originalMethod.equals(GET)
                        && (statusCode == MOVED_PERMANENTLY_301 || statusCode == SEE_OTHER_303 || (statusCode == FOUND_302 && !config.isStrict302Handling()));
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
                    if (isNonEmpty(request.getFormParams()))
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

                LOGGER.debug("Redirecting to {}", newUri);

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

                LOGGER.debug("Sending redirect to {}", newUri);

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
}
