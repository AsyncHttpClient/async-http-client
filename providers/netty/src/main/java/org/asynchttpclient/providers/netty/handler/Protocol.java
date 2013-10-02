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

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.asynchttpclient.providers.netty.util.HttpUtil.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;

import java.net.URI;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Cookie;
import org.asynchttpclient.MaxRedirectException;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.org.jboss.netty.handler.codec.http.CookieDecoder;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Protocol {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    protected final Channels channels;
    protected final AsyncHttpClientConfig config;
    protected final NettyRequestSender requestSender;
    protected final NettyAsyncHttpProviderConfig nettyConfig;

    public Protocol(Channels channels, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, NettyRequestSender requestSender) {
        this.channels = channels;
        this.config = config;
        this.nettyConfig = nettyConfig;
        this.requestSender = requestSender;
    }

    public abstract void handle(ChannelHandlerContext ctx, NettyResponseFuture<?> future, Object message) throws Exception;

    public abstract void onError(ChannelHandlerContext ctx, Throwable error);

    public abstract void onClose(ChannelHandlerContext ctx);

    protected boolean redirect(Request request, NettyResponseFuture<?> future, HttpResponse response, final ChannelHandlerContext ctx) throws Exception {

        io.netty.handler.codec.http.HttpResponseStatus status = response.getStatus();
        boolean redirectEnabled = request.isRedirectOverrideSet() ? request.isRedirectEnabled() : config.isRedirectEnabled();
        boolean isRedirectStatus = status.equals(MOVED_PERMANENTLY) || status.equals(FOUND) || status.equals(SEE_OTHER) || status.equals(TEMPORARY_REDIRECT);
        if (redirectEnabled && isRedirectStatus) {

            if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {
                // We must allow 401 handling again.
                future.getAndSetAuth(false);

                String location = response.headers().get(HttpHeaders.Names.LOCATION);
                URI uri = AsyncHttpProviderUtils.getRedirectUri(future.getURI(), location);

                if (!uri.toString().equals(future.getURI().toString())) {
                    final RequestBuilder nBuilder = new RequestBuilder(future.getRequest());
                    if (config.isRemoveQueryParamOnRedirect()) {
                        nBuilder.setQueryParameters(null);
                    }

                    // FIXME why not do that for 301 and 307 too?
                    if ((status.equals(FOUND) || status.equals(SEE_OTHER)) && !(status.equals(FOUND) && config.isStrict302Handling())) {
                        nBuilder.setMethod(HttpMethod.GET.name());
                    }

                    // in case of a redirect from HTTP to HTTPS, future attributes might change
                    final boolean initialConnectionKeepAlive = future.isKeepAlive();
                    final String initialPoolKey = channels.getPoolKey(future);

                    future.setURI(uri);
                    String newUrl = uri.toString();
                    if (request.getUrl().startsWith(WEBSOCKET)) {
                        newUrl = newUrl.replace(HTTP, WEBSOCKET);
                    }

                    logger.debug("Redirecting to {}", newUrl);

                    for (String cookieStr : future.getHttpResponse().headers().getAll(HttpHeaders.Names.SET_COOKIE)) {
                        for (Cookie c : CookieDecoder.decode(cookieStr)) {
                            nBuilder.addOrReplaceCookie(c);
                        }
                    }

                    for (String cookieStr : future.getHttpResponse().headers().getAll(HttpHeaders.Names.SET_COOKIE2)) {
                        for (Cookie c : CookieDecoder.decode(cookieStr)) {
                            nBuilder.addOrReplaceCookie(c);
                        }
                    }

                    Callback callback = new Callback(future) {
                        public void call() throws Exception {
                            if (!(initialConnectionKeepAlive && ctx.channel().isActive() && channels.offerToPool(initialPoolKey, ctx.channel()))) {
                                channels.finishChannel(ctx);
                            }
                        }
                    };

                    if (HttpHeaders.isTransferEncodingChunked(response)) {
                        // We must make sure there is no bytes left before
                        // executing the next request.
                        // FIXME investigate this
                        Channels.setDefaultAttribute(ctx, callback);
                    } else {
                        // FIXME don't understand: this offers the connection to the pool, or even closes it, while the request has not been sent, right?
                        callback.call();
                    }

                    Request target = nBuilder.setUrl(newUrl).build();
                    future.setRequest(target);
                    // FIXME why not reuse the channel is same host?
                    requestSender.sendNextRequest(target, future);
                    return true;
                }
            } else {
                throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
            }
        }
        return false;
    }
}
