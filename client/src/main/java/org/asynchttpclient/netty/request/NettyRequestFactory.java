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
package org.asynchttpclient.netty.request;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.netty.request.body.*;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.generator.ReactiveStreamsBodyGenerator;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.StringUtils;

import java.nio.charset.Charset;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.asynchttpclient.util.AuthenticatorUtils.perRequestAuthorizationHeader;
import static org.asynchttpclient.util.AuthenticatorUtils.perRequestProxyAuthorizationHeader;
import static org.asynchttpclient.util.HttpUtils.*;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.ws.WebSocketUtils.getWebSocketKey;

public final class NettyRequestFactory {

  private static final Integer ZERO_CONTENT_LENGTH = 0;

  private final AsyncHttpClientConfig config;
  private final ClientCookieEncoder cookieEncoder;

  NettyRequestFactory(AsyncHttpClientConfig config) {
    this.config = config;
    cookieEncoder = config.isUseLaxCookieEncoder() ? ClientCookieEncoder.LAX : ClientCookieEncoder.STRICT;
  }

  private NettyBody body(Request request) {
    NettyBody nettyBody = null;
    Charset bodyCharset = request.getCharset();

    if (request.getByteData() != null) {
      nettyBody = new NettyByteArrayBody(request.getByteData());

    } else if (request.getCompositeByteData() != null) {
      nettyBody = new NettyCompositeByteArrayBody(request.getCompositeByteData());

    } else if (request.getStringData() != null) {
      nettyBody = new NettyByteBufferBody(StringUtils.charSequence2ByteBuffer(request.getStringData(), bodyCharset));

    } else if (request.getByteBufferData() != null) {
      nettyBody = new NettyByteBufferBody(request.getByteBufferData());

    } else if (request.getStreamData() != null) {
      nettyBody = new NettyInputStreamBody(request.getStreamData());

    } else if (isNonEmpty(request.getFormParams())) {
      CharSequence contentTypeOverride = request.getHeaders().contains(CONTENT_TYPE) ? null : HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED;
      nettyBody = new NettyByteBufferBody(urlEncodeFormParams(request.getFormParams(), bodyCharset), contentTypeOverride);

    } else if (isNonEmpty(request.getBodyParts())) {
      nettyBody = new NettyMultipartBody(request.getBodyParts(), request.getHeaders(), config);

    } else if (request.getFile() != null) {
      nettyBody = new NettyFileBody(request.getFile(), config);

    } else if (request.getBodyGenerator() instanceof FileBodyGenerator) {
      FileBodyGenerator fileBodyGenerator = (FileBodyGenerator) request.getBodyGenerator();
      nettyBody = new NettyFileBody(fileBodyGenerator.getFile(), fileBodyGenerator.getRegionSeek(), fileBodyGenerator.getRegionLength(), config);

    } else if (request.getBodyGenerator() instanceof InputStreamBodyGenerator) {
      InputStreamBodyGenerator inStreamGenerator = InputStreamBodyGenerator.class.cast(request.getBodyGenerator());
      nettyBody = new NettyInputStreamBody(inStreamGenerator.getInputStream(), inStreamGenerator.getContentLength());

    } else if (request.getBodyGenerator() instanceof ReactiveStreamsBodyGenerator) {
      ReactiveStreamsBodyGenerator reactiveStreamsBodyGenerator = (ReactiveStreamsBodyGenerator) request.getBodyGenerator();
      nettyBody = new NettyReactiveStreamsBody(reactiveStreamsBodyGenerator.getPublisher(), reactiveStreamsBodyGenerator.getContentLength());

    } else if (request.getBodyGenerator() != null) {
      nettyBody = new NettyBodyBody(request.getBodyGenerator().createBody(), config);
    }

    return nettyBody;
  }

  public void addAuthorizationHeader(HttpHeaders headers, String authorizationHeader) {
    if (authorizationHeader != null)
      // don't override authorization but append
      headers.add(AUTHORIZATION, authorizationHeader);
  }

  public void setProxyAuthorizationHeader(HttpHeaders headers, String proxyAuthorizationHeader) {
    if (proxyAuthorizationHeader != null)
      headers.set(PROXY_AUTHORIZATION, proxyAuthorizationHeader);
  }

  public NettyRequest newNettyRequest(Request request, boolean performConnectRequest, ProxyServer proxyServer, Realm realm, Realm proxyRealm) {

    Uri uri = request.getUri();
    HttpMethod method = performConnectRequest ? HttpMethod.CONNECT : HttpMethod.valueOf(request.getMethod());
    boolean connect = method == HttpMethod.CONNECT;

    HttpVersion httpVersion = HttpVersion.HTTP_1_1;
    String requestUri = requestUri(uri, proxyServer, connect);

    NettyBody body = connect ? null : body(request);

    NettyRequest nettyRequest;
    if (body == null) {
      HttpRequest httpRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri, Unpooled.EMPTY_BUFFER);
      nettyRequest = new NettyRequest(httpRequest, null);

    } else if (body instanceof NettyDirectBody) {
      ByteBuf buf = NettyDirectBody.class.cast(body).byteBuf();
      HttpRequest httpRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri, buf);
      // body is passed as null as it's written directly with the request
      nettyRequest = new NettyRequest(httpRequest, null);

    } else {
      HttpRequest httpRequest = new DefaultHttpRequest(httpVersion, method, requestUri);
      nettyRequest = new NettyRequest(httpRequest, body);
    }

    HttpHeaders headers = nettyRequest.getHttpRequest().headers();

    if (connect) {
      // assign proxy-auth as configured on request
      headers.set(PROXY_AUTHORIZATION, request.getHeaders().getAll(PROXY_AUTHORIZATION));
      headers.set(USER_AGENT, request.getHeaders().getAll(USER_AGENT));

    } else {
      // assign headers as configured on request
      headers.set(request.getHeaders());

      if (isNonEmpty(request.getCookies())) {
        headers.set(COOKIE, cookieEncoder.encode(request.getCookies()));
      }

      String userDefinedAcceptEncoding = headers.get(ACCEPT_ENCODING);
      if (userDefinedAcceptEncoding != null) {
        // we don't support Brotly ATM
        headers.set(ACCEPT_ENCODING, filterOutBrotliFromAcceptEncoding(userDefinedAcceptEncoding));

      } else if (config.isCompressionEnforced()) {
        headers.set(ACCEPT_ENCODING, GZIP_DEFLATE);
      }
    }

    if (!headers.contains(CONTENT_LENGTH)) {
      if (body != null) {
        if (body.getContentLength() < 0) {
          headers.set(TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        } else {
          headers.set(CONTENT_LENGTH, body.getContentLength());
        }
      } else if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
        headers.set(CONTENT_LENGTH, ZERO_CONTENT_LENGTH);
      }
    }

    if (body != null && body.getContentTypeOverride() != null) {
      headers.set(CONTENT_TYPE, body.getContentTypeOverride());
    }

    // connection header and friends
    if (!connect && uri.isWebSocket()) {
      headers.set(UPGRADE, HttpHeaderValues.WEBSOCKET)
              .set(CONNECTION, HttpHeaderValues.UPGRADE)
              .set(SEC_WEBSOCKET_KEY, getWebSocketKey())
              .set(SEC_WEBSOCKET_VERSION, "13");

      if (!headers.contains(ORIGIN)) {
        headers.set(ORIGIN, originHeader(uri));
      }

    } else if (!headers.contains(CONNECTION)) {
      CharSequence connectionHeaderValue = connectionHeader(config.isKeepAlive(), httpVersion);
      if (connectionHeaderValue != null) {
        headers.set(CONNECTION, connectionHeaderValue);
      }
    }

    if (!headers.contains(HOST)) {
      String virtualHost = request.getVirtualHost();
      headers.set(HOST, virtualHost != null ? virtualHost : hostHeader(uri));
    }

    // don't override authorization but append. Skip it on a CONNECT: that request is sent to the
    // proxy in the clear to open the tunnel, so the origin Authorization would be exposed to the
    // proxy. It is added to the tunneled request, which is built separately once the tunnel is up.
    if (!connect) {
      addAuthorizationHeader(headers, perRequestAuthorizationHeader(request, realm));
    }
    // Only set proxy auth on a request sent to an HTTP proxy: either over plain HTTP (the origin request
    // carries an absolute URI straight to the proxy) or on a CONNECT (sent to the proxy to open the
    // tunnel). A SOCKS proxy tunnels at the transport layer, so the request reaches the ORIGIN - a
    // Proxy-Authorization header would leak the proxy credentials to the origin. Guard on the proxy type.
    // A ws:// request is tunnelled through CONNECT the same way wss:// is (see NettyRequestSender's
    // needConnect check), so the upgrade request that follows also reaches the origin, not the proxy;
    // exclude it from the plain-HTTP branch the same way wss:// already is.
    if ((connect || (!uri.isSecured() && !uri.isWebSocket())) && proxyServer != null && proxyServer.getProxyType().isHttp()) {
      setProxyAuthorizationHeader(headers, perRequestProxyAuthorizationHeader(request, proxyRealm));
    }

    // Add default accept headers
    if (!headers.contains(ACCEPT)) {
      headers.set(ACCEPT, ACCEPT_ALL_HEADER_VALUE);
    }

    // Add default user agent
    if (!headers.contains(USER_AGENT) && config.getUserAgent() != null) {
      headers.set(USER_AGENT, config.getUserAgent());
    }

    return nettyRequest;
  }

  private String requestUri(Uri uri, ProxyServer proxyServer, boolean connect) {
    if (connect) {
      // proxy tunnelling, connect need host and explicit port
      return uri.getAuthority();

    } else if (proxyServer != null && !uri.isSecured() && !uri.isWebSocket() && proxyServer.getProxyType().isHttp()) {
      // proxy over HTTP, need full url. A ws:// request is tunnelled through CONNECT, so its upgrade
      // request reaches the origin and takes the origin-form request target below
      return uri.toUrl();

    } else {
      // direct connection to target host or tunnel already connected: only path and query
      return uri.toRelativeUrl();
    }
  }

  private CharSequence connectionHeader(boolean keepAlive, HttpVersion httpVersion) {
    if (httpVersion.isKeepAliveDefault()) {
      return keepAlive ? null : HttpHeaderValues.CLOSE;
    } else {
      return keepAlive ? HttpHeaderValues.KEEP_ALIVE : null;
    }
  }
}
