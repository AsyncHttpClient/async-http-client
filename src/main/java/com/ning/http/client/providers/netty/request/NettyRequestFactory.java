/*
 * Copyright (c) 2014-2015 AsyncHttpClient Project. All rights reserved.
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
package com.ning.http.client.providers.netty.request;

import static com.ning.http.client.providers.netty.ws.WebSocketUtils.getKey;
import static com.ning.http.util.AsyncHttpProviderUtils.*;
import static com.ning.http.util.AuthenticatorUtils.perRequestAuthorizationHeader;
import static com.ning.http.util.AuthenticatorUtils.perRequestProxyAuthorizationHeader;
import static com.ning.http.util.MiscUtils.isNonEmpty;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.cookie.CookieEncoder;
import com.ning.http.client.generators.FileBodyGenerator;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.request.body.NettyBody;
import com.ning.http.client.providers.netty.request.body.NettyBodyBody;
import com.ning.http.client.providers.netty.request.body.NettyByteArrayBody;
import com.ning.http.client.providers.netty.request.body.NettyByteBufferBody;
import com.ning.http.client.providers.netty.request.body.NettyCompositeByteArrayBody;
import com.ning.http.client.providers.netty.request.body.NettyDirectBody;
import com.ning.http.client.providers.netty.request.body.NettyFileBody;
import com.ning.http.client.providers.netty.request.body.NettyInputStreamBody;
import com.ning.http.client.providers.netty.request.body.NettyMultipartBody;
import com.ning.http.client.uri.Uri;
import com.ning.http.util.StringUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map.Entry;

public final class NettyRequestFactory {

    public static final String GZIP_DEFLATE = HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE;

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyConfig;

    public NettyRequestFactory(AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig) {
        this.config = config;
        this.nettyConfig = nettyConfig;
    }

    private String requestUri(Uri uri, ProxyServer proxyServer, boolean connect) {
        if (connect)
            return getAuthority(uri);

        else if (proxyServer != null && !(useProxyConnect(uri) && config.isUseRelativeURIsWithConnectProxies()))
            return uri.toUrl();

        else {
            String path = getNonEmptyPath(uri);
            if (isNonEmpty(uri.getQuery()))
                return path + "?" + uri.getQuery();
            else
                return path;
        }
    }

    private String hostHeader(Request request, Uri uri) {
        String virtualHost = request.getVirtualHost();
        if (virtualHost != null)
            return virtualHost;
        else {
            String host = uri.getHost();
            int port = uri.getPort();
            return port == -1 || port == getSchemeDefaultPort(uri.getScheme()) ? host : host + ":" + port;
        }
    }

    private NettyBody body(Request request, boolean connect) throws IOException {
        NettyBody nettyBody = null;
        if (!connect) {

            Charset bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : Charset.forName(request.getBodyEncoding());

            if (request.getByteData() != null)
                nettyBody = new NettyByteArrayBody(request.getByteData());

            else if (request.getCompositeByteData() != null)
                nettyBody = new NettyCompositeByteArrayBody(request.getCompositeByteData());

            else if (request.getStringData() != null)
                nettyBody = new NettyByteBufferBody(StringUtils.charSequence2ByteBuffer(request.getStringData(), bodyCharset));

            else if (request.getStreamData() != null)
                nettyBody = new NettyInputStreamBody(request.getStreamData());

            else if (isNonEmpty(request.getFormParams())) {

                String contentType = null;
                if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE))
                    contentType = HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED;

                nettyBody = new NettyByteBufferBody(urlEncodeFormParams(request.getFormParams(), bodyCharset), contentType);

            } else if (isNonEmpty(request.getParts()))
                nettyBody = new NettyMultipartBody(request.getParts(), request.getHeaders(), nettyConfig);

            else if (request.getFile() != null)
                nettyBody = new NettyFileBody(request.getFile(), nettyConfig);

            else if (request.getBodyGenerator() instanceof FileBodyGenerator) {
                FileBodyGenerator fileBodyGenerator = (FileBodyGenerator) request.getBodyGenerator();
                nettyBody = new NettyFileBody(fileBodyGenerator.getFile(), fileBodyGenerator.getRegionSeek(),
                        fileBodyGenerator.getRegionLength(), nettyConfig);

            } else if (request.getBodyGenerator() instanceof InputStreamBodyGenerator)
                nettyBody = new NettyInputStreamBody(InputStreamBodyGenerator.class.cast(request.getBodyGenerator()).getInputStream());

            else if (request.getBodyGenerator() != null)
                nettyBody = new NettyBodyBody(request.getBodyGenerator().createBody(), nettyConfig);
        }

        return nettyBody;
    }

    public void addAuthorizationHeader(HttpHeaders headers, String authorizationHeader) {
        if (authorizationHeader != null)
            // don't override authorization but append
            headers.add(HttpHeaders.Names.AUTHORIZATION, authorizationHeader);
    }
    
    public void setProxyAuthorizationHeader(HttpHeaders headers, String proxyAuthorizationHeader) {
        if (proxyAuthorizationHeader != null)
            headers.set(HttpHeaders.Names.PROXY_AUTHORIZATION, proxyAuthorizationHeader);
    }
    
    public NettyRequest newNettyRequest(Request request, Uri uri, boolean forceConnect, ProxyServer proxyServer)
            throws IOException {

        HttpMethod method = forceConnect ? HttpMethod.CONNECT : HttpMethod.valueOf(request.getMethod());
        boolean connect = method == HttpMethod.CONNECT;
        
        boolean allowConnectionPooling = config.isAllowPoolingConnections() && (!isSecure(uri) || config.isAllowPoolingSslConnections());
        
        HttpVersion httpVersion = (connect && proxyServer.isForceHttp10()) ? HttpVersion.HTTP_1_0 : HttpVersion.HTTP_1_1;
        String requestUri = requestUri(uri, proxyServer, connect);

        NettyBody body = body(request, connect);

        HttpRequest httpRequest;
        NettyRequest nettyRequest;
        if (body instanceof NettyDirectBody) {
            ChannelBuffer buffer = NettyDirectBody.class.cast(body).channelBuffer();
            httpRequest = new DefaultHttpRequest(httpVersion, method, requestUri);
            // body is passed as null as it's written directly with the request
            httpRequest.setContent(buffer);
            nettyRequest = new NettyRequest(httpRequest, null);

        } else {
            httpRequest = new DefaultHttpRequest(httpVersion, method, requestUri);
            nettyRequest = new NettyRequest(httpRequest, body);
        }

        HttpHeaders headers = httpRequest.headers();

        if (method != HttpMethod.CONNECT) {
            // assign headers as configured on request
            for (Entry<String, List<String>> header : request.getHeaders()) {
                headers.set(header.getKey(), header.getValue());
            }

            if (isNonEmpty(request.getCookies()))
                headers.set(HttpHeaders.Names.COOKIE, CookieEncoder.encode(request.getCookies()));

            if (config.isCompressionEnforced() && !headers.contains(HttpHeaders.Names.ACCEPT_ENCODING))
                headers.set(HttpHeaders.Names.ACCEPT_ENCODING, GZIP_DEFLATE);
        }

        if (body != null) {
            if (body.getContentLength() < 0)
                headers.set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
            else
                headers.set(HttpHeaders.Names.CONTENT_LENGTH, body.getContentLength());

            if (body.getContentType() != null)
                headers.set(HttpHeaders.Names.CONTENT_TYPE, body.getContentType());
        }

        // connection header and friends
        boolean webSocket = isWebSocket(uri.getScheme());
        if (method != HttpMethod.CONNECT && webSocket) {
            String origin = "http://" + uri.getHost() + ":" + (uri.getPort() == -1 ? isSecure(uri.getScheme()) ? 443 : 80 : uri.getPort());
            headers.set(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET)//
                    .set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE)//
                    .set(HttpHeaders.Names.ORIGIN, origin)//
                    .set(HttpHeaders.Names.SEC_WEBSOCKET_KEY, getKey())//
                    .set(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "13");

        } else if (!headers.contains(HttpHeaders.Names.CONNECTION)) {
            String connectionHeaderValue = connectionHeader(allowConnectionPooling, httpVersion == HttpVersion.HTTP_1_1);
            if (connectionHeaderValue != null)
                headers.set(HttpHeaders.Names.CONNECTION, connectionHeaderValue);
        }

        if (!headers.contains(HttpHeaders.Names.HOST))
            headers.set(HttpHeaders.Names.HOST, hostHeader(request, uri));

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        // don't override authorization but append
        addAuthorizationHeader(headers, perRequestAuthorizationHeader(request, uri, realm));

        setProxyAuthorizationHeader(headers, perRequestProxyAuthorizationHeader(request, realm, proxyServer, connect));

        // Add default accept headers
        if (!headers.contains(HttpHeaders.Names.ACCEPT))
            headers.set(HttpHeaders.Names.ACCEPT, "*/*");

        // Add default user agent
        if (!headers.contains(HttpHeaders.Names.USER_AGENT) && config.getUserAgent() != null)
            headers.set(HttpHeaders.Names.USER_AGENT, config.getUserAgent());

        return nettyRequest;
    }
}
