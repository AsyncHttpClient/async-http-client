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

import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.proxy.ProxyServer;

public class Interceptors {

    private final AsyncHttpClientConfig config;
    private final Unauthorized401Interceptor unauthorized401Interceptor;
    private final ProxyUnauthorized407Interceptor proxyUnauthorized407Interceptor;
    private final Continue100Interceptor continue100Interceptor;
    private final Redirect30xInterceptor redirect30xInterceptor;
    private final ConnectSuccessInterceptor connectSuccessInterceptor;
    private final ResponseFiltersInterceptor responseFiltersInterceptor;
    private final boolean hasResponseFilters;

    public Interceptors(//
            AsyncHttpClientConfig config,//
            ChannelManager channelManager,//
            NettyRequestSender requestSender) {
        this.config = config;
        unauthorized401Interceptor = new Unauthorized401Interceptor(channelManager, requestSender);
        proxyUnauthorized407Interceptor = new ProxyUnauthorized407Interceptor(channelManager, requestSender);
        continue100Interceptor = new Continue100Interceptor(requestSender);
        redirect30xInterceptor = new Redirect30xInterceptor(channelManager, config, requestSender);
        connectSuccessInterceptor = new ConnectSuccessInterceptor(channelManager, requestSender);
        responseFiltersInterceptor = new ResponseFiltersInterceptor(config, requestSender);
        hasResponseFilters = !config.getResponseFilters().isEmpty();
    }

    public boolean exitAfterIntercept(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            AsyncHandler<?> handler,//
            HttpResponse response,//
            HttpResponseStatus status,//
            HttpResponseHeaders responseHeaders) throws Exception {

        HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
        ProxyServer proxyServer = future.getProxyServer();
        int statusCode = response.getStatus().code();
        Request request = future.getCurrentRequest();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        if (hasResponseFilters && responseFiltersInterceptor.exitAfterProcessingFilters(channel, future, handler, status, responseHeaders)) {
            return true;
        }

        if (statusCode == UNAUTHORIZED_401) {
            return unauthorized401Interceptor.exitAfterHandling401(channel, future, response, request, statusCode, realm, proxyServer, httpRequest);

        } else if (statusCode == PROXY_AUTHENTICATION_REQUIRED_407) {
            return proxyUnauthorized407Interceptor.exitAfterHandling407(channel, future, response, request, statusCode, proxyServer, httpRequest);

        } else if (statusCode == CONTINUE_100) {
            return continue100Interceptor.exitAfterHandling100(channel, future, statusCode);

        } else if (Redirect30xInterceptor.REDIRECT_STATUSES.contains(statusCode)) {
            return redirect30xInterceptor.exitAfterHandlingRedirect(channel, future, response, request, statusCode, realm);

        } else if (httpRequest.getMethod() == HttpMethod.CONNECT && statusCode == OK_200) {
            return connectSuccessInterceptor.exitAfterHandlingConnect(channel, future, request, proxyServer, statusCode, httpRequest);

        }
        return false;
    }
}
