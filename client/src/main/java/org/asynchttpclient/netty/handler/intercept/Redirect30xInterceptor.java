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

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.*;
import static org.asynchttpclient.util.HttpUtils.followRedirect;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;

public class Redirect30xInterceptor {

  public static final Set<Integer> REDIRECT_STATUSES = new HashSet<>();
  private static final Logger LOGGER = LoggerFactory.getLogger(Redirect30xInterceptor.class);

  static {
    REDIRECT_STATUSES.add(MOVED_PERMANENTLY_301);
    REDIRECT_STATUSES.add(FOUND_302);
    REDIRECT_STATUSES.add(SEE_OTHER_303);
    REDIRECT_STATUSES.add(TEMPORARY_REDIRECT_307);
    REDIRECT_STATUSES.add(PERMANENT_REDIRECT_308);
  }

  private final ChannelManager channelManager;
  private final AsyncHttpClientConfig config;
  private final NettyRequestSender requestSender;
  private final MaxRedirectException maxRedirectException;

  Redirect30xInterceptor(ChannelManager channelManager, AsyncHttpClientConfig config, NettyRequestSender requestSender) {
    this.channelManager = channelManager;
    this.config = config;
    this.requestSender = requestSender;
    maxRedirectException = unknownStackTrace(new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects()), Redirect30xInterceptor.class,
            "exitAfterHandlingRedirect");
  }

  public boolean exitAfterHandlingRedirect(Channel channel,
                                           NettyResponseFuture<?> future,
                                           HttpResponse response,
                                           Request request,
                                           int statusCode,
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
        boolean keepBody = statusCode == TEMPORARY_REDIRECT_307 || statusCode == PERMANENT_REDIRECT_308 || (statusCode == FOUND_302 && config.isStrict302Handling());

        final RequestBuilder requestBuilder = new RequestBuilder(switchToGet ? GET : originalMethod)
                .setChannelPoolPartitioning(request.getChannelPoolPartitioning())
                .setFollowRedirect(true)
                .setLocalAddress(request.getLocalAddress())
                .setNameResolver(request.getNameResolver())
                .setProxyServer(request.getProxyServer())
                .setRealm(request.getRealm())
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
        String location = responseHeaders.get(LOCATION);
        Uri newUri = Uri.create(future.getUri(), location);

        LOGGER.debug("Redirecting to {}", newUri);

        CookieStore cookieStore = config.getCookieStore();
        if (cookieStore != null) {
          // Update request's cookies assuming that cookie store is already updated by Interceptors
          List<Cookie> cookies = cookieStore.get(newUri);
          if (!cookies.isEmpty())
            for (Cookie cookie : cookies)
              requestBuilder.addOrReplaceCookie(cookie);
        }

        boolean sameBase = request.getUri().isSameBase(newUri);

        if (sameBase) {
          // we can only assume the virtual host is still valid if the baseUrl is the same
          requestBuilder.setVirtualHost(request.getVirtualHost());
        }

        final Request nextRequest = requestBuilder.setUri(newUri).build();
        future.setTargetRequest(nextRequest);

        LOGGER.debug("Sending redirect to {}", newUri);

        if (future.isKeepAlive() && !HttpUtil.isTransferEncodingChunked(response)) {
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

    HttpHeaders headers = request.getHeaders()
            .remove(HOST)
            .remove(CONTENT_LENGTH);

    if (!keepBody) {
      headers.remove(CONTENT_TYPE);
    }

    if (realm != null && realm.getScheme() == AuthScheme.NTLM) {
      headers.remove(AUTHORIZATION)
              .remove(PROXY_AUTHORIZATION);
    }
    return headers;
  }
}
