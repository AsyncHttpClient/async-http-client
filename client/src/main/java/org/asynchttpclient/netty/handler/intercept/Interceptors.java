/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.proxy.ProxyServer;

import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.CONTINUE_100;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.OK_200;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.PROXY_AUTHENTICATION_REQUIRED_407;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.UNAUTHORIZED_401;

public class Interceptors {

    private final AsyncHttpClientConfig config;
    private final Unauthorized401Interceptor unauthorized401Interceptor;
    private final ProxyUnauthorized407Interceptor proxyUnauthorized407Interceptor;
    private final Continue100Interceptor continue100Interceptor;
    private final Redirect30xInterceptor redirect30xInterceptor;
    private final ConnectSuccessInterceptor connectSuccessInterceptor;
    private final ResponseFiltersInterceptor responseFiltersInterceptor;
    private final boolean hasResponseFilters;
    private final ClientCookieDecoder cookieDecoder;

    public Interceptors(AsyncHttpClientConfig config,
                        ChannelManager channelManager,
                        NettyRequestSender requestSender) {
        this.config = config;
        unauthorized401Interceptor = new Unauthorized401Interceptor(channelManager, requestSender);
        proxyUnauthorized407Interceptor = new ProxyUnauthorized407Interceptor(channelManager, requestSender);
        continue100Interceptor = new Continue100Interceptor(requestSender);
        redirect30xInterceptor = new Redirect30xInterceptor(channelManager, config, requestSender);
        connectSuccessInterceptor = new ConnectSuccessInterceptor(channelManager, requestSender);
        responseFiltersInterceptor = new ResponseFiltersInterceptor(config, requestSender);
        hasResponseFilters = !config.getResponseFilters().isEmpty();
        cookieDecoder = config.isUseLaxCookieEncoder() ? ClientCookieDecoder.LAX : ClientCookieDecoder.STRICT;
    }

    public boolean exitAfterIntercept(Channel channel, NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponse response,
                                      HttpResponseStatus status, HttpHeaders responseHeaders) throws Exception {

        HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
        ProxyServer proxyServer = future.getProxyServer();
        int statusCode = response.status().code();
        Request request = future.getCurrentRequest();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        // This MUST BE called before Redirect30xInterceptor because latter assumes cookie store is already updated
        CookieStore cookieStore = config.getCookieStore();
        if (cookieStore != null) {
            for (String cookieStr : responseHeaders.getAll(SET_COOKIE)) {
                Cookie c = cookieDecoder.decode(cookieStr);
                if (c != null) {
                    // Set-Cookie header could be invalid/malformed
                    cookieStore.add(future.getCurrentRequest().getUri(), c);
                }
            }
        }

        if (hasResponseFilters && responseFiltersInterceptor.exitAfterProcessingFilters(channel, future, handler, status, responseHeaders)) {
            return true;
        }

        if (statusCode == UNAUTHORIZED_401) {
            return unauthorized401Interceptor.exitAfterHandling401(channel, future, response, request, realm, httpRequest);
        }

        if (statusCode == PROXY_AUTHENTICATION_REQUIRED_407) {
            return proxyUnauthorized407Interceptor.exitAfterHandling407(channel, future, response, request, proxyServer, httpRequest);
        }

        if (statusCode == CONTINUE_100) {
            return continue100Interceptor.exitAfterHandling100(channel, future);
        }

        if (Redirect30xInterceptor.REDIRECT_STATUSES.contains(statusCode)) {
            return redirect30xInterceptor.exitAfterHandlingRedirect(channel, future, response, request, statusCode, realm);
        }

        if (httpRequest.method() == HttpMethod.CONNECT && statusCode == OK_200) {
            return connectSuccessInterceptor.exitAfterHandlingConnect(channel, future, request, proxyServer);
        }
        return false;
    }
}
