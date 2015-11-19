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

import static io.netty.handler.codec.http.HttpHeaders.Names.ACCEPT;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Names.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.COOKIE;
import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static io.netty.handler.codec.http.HttpHeaders.Names.ORIGIN;
import static io.netty.handler.codec.http.HttpHeaders.Names.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaders.Names.SEC_WEBSOCKET_KEY;
import static io.netty.handler.codec.http.HttpHeaders.Names.SEC_WEBSOCKET_VERSION;
import static io.netty.handler.codec.http.HttpHeaders.Names.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Names.UPGRADE;
import static io.netty.handler.codec.http.HttpHeaders.Names.USER_AGENT;
import static org.asynchttpclient.util.HttpUtils.*;
import static org.asynchttpclient.util.AuthenticatorUtils.perRequestAuthorizationHeader;
import static org.asynchttpclient.util.AuthenticatorUtils.perRequestProxyAuthorizationHeader;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.ws.WebSocketUtils.getKey;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.Charset;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.cookie.CookieEncoder;
import org.asynchttpclient.netty.request.body.NettyBody;
import org.asynchttpclient.netty.request.body.NettyBodyBody;
import org.asynchttpclient.netty.request.body.NettyByteArrayBody;
import org.asynchttpclient.netty.request.body.NettyByteBufferBody;
import org.asynchttpclient.netty.request.body.NettyCompositeByteArrayBody;
import org.asynchttpclient.netty.request.body.NettyDirectBody;
import org.asynchttpclient.netty.request.body.NettyFileBody;
import org.asynchttpclient.netty.request.body.NettyInputStreamBody;
import org.asynchttpclient.netty.request.body.NettyMultipartBody;
import org.asynchttpclient.netty.request.body.NettyReactiveStreamsBody;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.generator.ReactiveStreamsBodyGenerator;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.StringUtils;

public final class NettyRequestFactory {

    public static final String GZIP_DEFLATE = HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE;
    
    private final AsyncHttpClientConfig config;

    public NettyRequestFactory(AsyncHttpClientConfig config) {
        this.config = config;
    }

    private NettyBody body(Request request, boolean connect) {
        NettyBody nettyBody = null;
        if (!connect) {

            Charset bodyCharset = request.getCharset() == null ? DEFAULT_CHARSET : request.getCharset();

            if (request.getByteData() != null)
                nettyBody = new NettyByteArrayBody(request.getByteData());

            else if (request.getCompositeByteData() != null)
                nettyBody = new NettyCompositeByteArrayBody(request.getCompositeByteData());

            else if (request.getStringData() != null)
                nettyBody = new NettyByteBufferBody(StringUtils.charSequence2ByteBuffer(request.getStringData(), bodyCharset));

            else if (request.getByteBufferData() != null)
                nettyBody = new NettyByteBufferBody(request.getByteBufferData());

            else if (request.getStreamData() != null)
                nettyBody = new NettyInputStreamBody(request.getStreamData());

            else if (isNonEmpty(request.getFormParams())) {

                String contentType = null;
                if (!request.getHeaders().contains(CONTENT_TYPE))
                    contentType = HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED;

                nettyBody = new NettyByteBufferBody(urlEncodeFormParams(request.getFormParams(), bodyCharset), contentType);

            } else if (isNonEmpty(request.getBodyParts()))
                nettyBody = new NettyMultipartBody(request.getBodyParts(), request.getHeaders(), config);

            else if (request.getFile() != null)
                nettyBody = new NettyFileBody(request.getFile(), config);

            else if (request.getBodyGenerator() instanceof FileBodyGenerator) {
                FileBodyGenerator fileBodyGenerator = (FileBodyGenerator) request.getBodyGenerator();
                nettyBody = new NettyFileBody(fileBodyGenerator.getFile(), fileBodyGenerator.getRegionSeek(), fileBodyGenerator.getRegionLength(), config);

            } else if (request.getBodyGenerator() instanceof InputStreamBodyGenerator)
                nettyBody = new NettyInputStreamBody(InputStreamBodyGenerator.class.cast(request.getBodyGenerator()).getInputStream());
            else if (request.getBodyGenerator() instanceof ReactiveStreamsBodyGenerator)
                nettyBody = new NettyReactiveStreamsBody(ReactiveStreamsBodyGenerator.class.cast(request.getBodyGenerator()).getPublisher());
            else if (request.getBodyGenerator() != null)
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

    public NettyRequest newNettyRequest(Request request, boolean forceConnect, ProxyServer proxyServer, Realm realm, Realm proxyRealm) {

        Uri uri = request.getUri();
        HttpMethod method = forceConnect ? HttpMethod.CONNECT : HttpMethod.valueOf(request.getMethod());
        boolean connect = method == HttpMethod.CONNECT;

        boolean allowConnectionPooling = config.isKeepAlive();

        HttpVersion httpVersion = !allowConnectionPooling || (connect && proxyServer.isForceHttp10()) ? HttpVersion.HTTP_1_0 : HttpVersion.HTTP_1_1;
        String requestUri = requestUri(uri, proxyServer, connect);

        NettyBody body = body(request, connect);

        HttpRequest httpRequest;
        NettyRequest nettyRequest;
        if (body instanceof NettyDirectBody) {
            ByteBuf buf = NettyDirectBody.class.cast(body).byteBuf();
            httpRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri, buf);
            // body is passed as null as it's written directly with the request
            nettyRequest = new NettyRequest(httpRequest, null);

        } else if (body == null) {
            httpRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri);
            nettyRequest = new NettyRequest(httpRequest, null);

        } else {
            httpRequest = new DefaultHttpRequest(httpVersion, method, requestUri);
            nettyRequest = new NettyRequest(httpRequest, body);
        }

        HttpHeaders headers = httpRequest.headers();

        if (connect) {
            // assign proxy-auth as configured on request
            headers.set(PROXY_AUTHORIZATION, request.getHeaders().getAll(PROXY_AUTHORIZATION));
        
        } else {
            // assign headers as configured on request
            headers.set(request.getHeaders());

            if (isNonEmpty(request.getCookies()))
                headers.set(COOKIE, CookieEncoder.encode(request.getCookies()));

            if (config.isCompressionEnforced() && !headers.contains(ACCEPT_ENCODING))
                headers.set(ACCEPT_ENCODING, GZIP_DEFLATE);
        }

        if (body != null) {
            if (body.getContentLength() < 0)
                headers.set(TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
            else
                headers.set(CONTENT_LENGTH, body.getContentLength());

            if (body.getContentType() != null)
                headers.set(CONTENT_TYPE, body.getContentType());
        }

        // connection header and friends
        if (!connect && uri.isWebSocket()) {
            headers.set(UPGRADE, HttpHeaders.Values.WEBSOCKET)//
                    .set(CONNECTION, HttpHeaders.Values.UPGRADE)//
                    .set(ORIGIN, "http://" + uri.getHost() + ":" + uri.getExplicitPort())//
                    .set(SEC_WEBSOCKET_KEY, getKey())//
                    .set(SEC_WEBSOCKET_VERSION, "13");

        } else if (!headers.contains(CONNECTION)) {
            String connectionHeaderValue = connectionHeader(allowConnectionPooling, httpVersion);
            if (connectionHeaderValue != null)
                headers.set(CONNECTION, connectionHeaderValue);
        }

        if (!headers.contains(HOST))
            headers.set(HOST, hostHeader(request, uri));

        // don't override authorization but append
        addAuthorizationHeader(headers, perRequestAuthorizationHeader(realm));
        setProxyAuthorizationHeader(headers, perRequestProxyAuthorizationHeader(proxyRealm));

        // Add default accept headers
        if (!headers.contains(ACCEPT))
            headers.set(ACCEPT, "*/*");

        // Add default user agent
        if (!headers.contains(USER_AGENT) && config.getUserAgent() != null)
            headers.set(USER_AGENT, config.getUserAgent());

        return nettyRequest;
    }
    
    private String requestUri(Uri uri, ProxyServer proxyServer, boolean connect) {
        if (connect)
            // proxy tunnelling, connect need host and explicit port
            return getAuthority(uri);

        else if (proxyServer != null)
            // proxy over HTTP, need full url
            return uri.toUrl();

        else {
            // direct connection to target host: only path and query
            String path = getNonEmptyPath(uri);
            if (isNonEmpty(uri.getQuery()))
                return path + "?" + uri.getQuery();
            else
                return path;
        }
    }

    private String connectionHeader(boolean allowConnectionPooling, HttpVersion httpVersion) {
        
        if (httpVersion.isKeepAliveDefault()) {
            return allowConnectionPooling ? null : HttpHeaders.Values.CLOSE;
        } else {
            return allowConnectionPooling ? HttpHeaders.Values.KEEP_ALIVE : null;
        }
    }
}
