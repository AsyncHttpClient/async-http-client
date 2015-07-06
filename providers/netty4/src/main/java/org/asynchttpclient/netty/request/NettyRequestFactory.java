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

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.hostHeader;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.urlEncodeFormParams;
import static org.asynchttpclient.util.HttpUtils.isSecure;
import static org.asynchttpclient.util.HttpUtils.isWebSocket;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.ws.WebSocketUtils.getKey;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map.Entry;

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
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.HttpUtils;
import org.asynchttpclient.util.StringUtils;

public final class NettyRequestFactory extends NettyRequestFactoryBase {

    public static final String GZIP_DEFLATE = HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE;

    public NettyRequestFactory(AsyncHttpClientConfig config) {
        super(config);
    }

    protected List<String> getProxyAuthorizationHeader(Request request) {
        return request.getHeaders().get(PROXY_AUTHORIZATION);
    }
    
    private NettyBody body(Request request, boolean connect) throws IOException {
        NettyBody nettyBody = null;
        if (!connect) {

            Charset bodyCharset = request.getBodyCharset() == null ? DEFAULT_CHARSET : request.getBodyCharset();

            if (request.getByteData() != null)
                nettyBody = new NettyByteArrayBody(request.getByteData());

            else if (request.getCompositeByteData() != null)
                nettyBody = new NettyCompositeByteArrayBody(request.getCompositeByteData());
                
            else if (request.getStringData() != null)
                nettyBody = new NettyByteBufferBody(StringUtils.charSequence2ByteBuffer(request.getStringData(), bodyCharset));

            else if (request.getByteBufferData() != null)
                nettyBody = new NettyByteBufferBody(request.getByteBufferData());

            else if (request.getStreamData() != null)
                nettyBody = new NettyInputStreamBody(request.getStreamData(), config);

            else if (isNonEmpty(request.getFormParams())) {

                String contentType = null;
                if (!request.getHeaders().containsKey(CONTENT_TYPE))
                    contentType = HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED;

                nettyBody = new NettyByteBufferBody(urlEncodeFormParams(request.getFormParams(), bodyCharset), contentType);

            } else if (isNonEmpty(request.getParts()))
                nettyBody = new NettyMultipartBody(request.getParts(), request.getHeaders(), config);

            else if (request.getFile() != null)
                nettyBody = new NettyFileBody(request.getFile(), config);

            else if (request.getBodyGenerator() instanceof FileBodyGenerator) {
                FileBodyGenerator fileBodyGenerator = (FileBodyGenerator) request.getBodyGenerator();
                nettyBody = new NettyFileBody(fileBodyGenerator.getFile(), fileBodyGenerator.getRegionSeek(), fileBodyGenerator.getRegionLength(), config);

            } else if (request.getBodyGenerator() instanceof InputStreamBodyGenerator)
                nettyBody = new NettyInputStreamBody(InputStreamBodyGenerator.class.cast(request.getBodyGenerator()).getInputStream(), config);

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

    public NettyRequest newNettyRequest(Request request, boolean forceConnect, ProxyServer proxyServer) throws IOException {

        Uri uri = request.getUri();
        HttpMethod method = forceConnect ? HttpMethod.CONNECT : HttpMethod.valueOf(request.getMethod());
        boolean connect = method == HttpMethod.CONNECT;
        
        boolean allowConnectionPooling = config.isAllowPoolingConnections() && (!HttpUtils.isSecure(uri) || config.isAllowPoolingSslConnections());
        
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

        if (!connect) {
            // assign headers as configured on request
            for (Entry<String, List<String>> header : request.getHeaders()) {
                headers.set(header.getKey(), header.getValue());
            }

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
        boolean webSocket = isWebSocket(uri.getScheme());
        if (!connect && webSocket) {
            headers.set(UPGRADE, HttpHeaders.Values.WEBSOCKET)//
            .set(CONNECTION, HttpHeaders.Values.UPGRADE)//
            .set(ORIGIN, "http://" + uri.getHost() + ":" + (uri.getPort() == -1 ? isSecure(uri.getScheme()) ? 443 : 80 : uri.getPort()))//
            .set(SEC_WEBSOCKET_KEY, getKey())//
            .set(SEC_WEBSOCKET_VERSION, "13");

        } else if (!headers.contains(CONNECTION)) {
            String connectionHeaderValue = connectionHeader(allowConnectionPooling, httpVersion == HttpVersion.HTTP_1_1);
            if (connectionHeaderValue != null)
                headers.set(CONNECTION, connectionHeaderValue);
        }

        if (!headers.contains(HOST))
            headers.set(HOST,  hostHeader(request, uri));

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        // don't override authorization but append
        addAuthorizationHeader(headers, systematicAuthorizationHeader(request, realm));

        setProxyAuthorizationHeader(headers, systematicProxyAuthorizationHeader(request, proxyServer, realm, connect));

        // Add default accept headers
        if (!headers.contains(ACCEPT))
            headers.set(ACCEPT, "*/*");

        // Add default user agent
        if (!headers.contains(USER_AGENT) && config.getUserAgent() != null)
            headers.set(USER_AGENT, config.getUserAgent());

        return nettyRequest;
    }
}
