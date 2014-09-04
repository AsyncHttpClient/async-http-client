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
package org.asynchttpclient.providers.netty.request;

import static org.asynchttpclient.providers.netty.util.HttpUtils.isNTLM;
import static org.asynchttpclient.providers.netty.util.HttpUtils.isSecure;
import static org.asynchttpclient.providers.netty.util.HttpUtils.isWebSocket;
import static org.asynchttpclient.providers.netty.util.HttpUtils.useProxyConnect;
import static org.asynchttpclient.providers.netty.ws.WebSocketUtils.getKey;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getAuthority;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getNonEmptyPath;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.keepAliveHeaderValue;
import static org.asynchttpclient.util.AuthenticatorUtils.computeBasicAuthentication;
import static org.asynchttpclient.util.AuthenticatorUtils.computeDigestAuthentication;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map.Entry;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Param;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.cookie.CookieEncoder;
import org.asynchttpclient.generators.FileBodyGenerator;
import org.asynchttpclient.generators.InputStreamBodyGenerator;
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.ntlm.NTLMEngineException;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.request.body.NettyBody;
import org.asynchttpclient.providers.netty.request.body.NettyBodyBody;
import org.asynchttpclient.providers.netty.request.body.NettyByteArrayBody;
import org.asynchttpclient.providers.netty.request.body.NettyFileBody;
import org.asynchttpclient.providers.netty.request.body.NettyInputStreamBody;
import org.asynchttpclient.providers.netty.request.body.NettyMultipartBody;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.UTF8UrlEncoder;

public final class NettyRequestFactory {

    public static final String GZIP_DEFLATE = HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE;

    private final AsyncHttpClientConfig config;
    private final NettyAsyncHttpProviderConfig nettyConfig;

    public NettyRequestFactory(AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig) {
        this.config = config;
        this.nettyConfig = nettyConfig;
    }

    private String requestUri(Uri uri, ProxyServer proxyServer, HttpMethod method) {
        if (method == HttpMethod.CONNECT)
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
        String host = request.getVirtualHost() != null ? request.getVirtualHost() : uri.getHost();
        return request.getVirtualHost() != null || uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    private String authorizationHeader(Request request, Uri uri, ProxyServer proxyServer, Realm realm) throws IOException {

        String authorizationHeader = null;

        if (realm != null && realm.getUsePreemptiveAuth()) {

            switch (realm.getAuthScheme()) {
            case BASIC:
                authorizationHeader = computeBasicAuthentication(realm);
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce())) {
                    try {
                        authorizationHeader = computeDigestAuthentication(realm);
                    } catch (NoSuchAlgorithmException e) {
                        throw new SecurityException(e);
                    }
                }
                break;
            case NTLM:
                String domain;
                if (proxyServer != null && proxyServer.getNtlmDomain() != null) {
                    domain = proxyServer.getNtlmDomain();
                } else {
                    domain = realm.getNtlmDomain();
                }
                try {
                    String msg = NTLMEngine.INSTANCE.generateType1Msg("NTLM " + domain, realm.getNtlmHost());
                    authorizationHeader = "NTLM " + msg;
                } catch (NTLMEngineException e) {
                    throw new IOException(e);
                }
                break;
            case KERBEROS:
            case SPNEGO:

                String host;
                if (proxyServer != null)
                    host = proxyServer.getHost();
                else if (request.getVirtualHost() != null)
                    host = request.getVirtualHost();
                else
                    host = uri.getHost();

                try {
                    authorizationHeader = "Negotiate " + SpnegoEngine.instance().generateToken(host);
                } catch (Throwable e) {
                    throw new IOException(e);
                }
                break;
            case NONE:
                break;
            default:
                throw new IllegalStateException("Invalid Authentication " + realm);
            }
        }

        return authorizationHeader;
    }

    private String proxyAuthorizationHeader(Request request, ProxyServer proxyServer, HttpMethod method) throws IOException {

        String proxyAuthorization = null;

        if (method == HttpMethod.CONNECT) {
            List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
            if (isNTLM(auth)) {
                proxyAuthorization = auth.get(0);
            }

        } else if (proxyServer != null && proxyServer.getPrincipal() != null) {
            if (isNonEmpty(proxyServer.getNtlmDomain())) {
                List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
                if (!isNTLM(auth)) {
                    try {
                        String msg = NTLMEngine.INSTANCE.generateType1Msg(proxyServer.getNtlmDomain(), proxyServer.getHost());
                        proxyAuthorization = "NTLM " + msg;
                    } catch (NTLMEngineException e) {
                        IOException ie = new IOException();
                        ie.initCause(e);
                        throw ie;
                    }
                }
            } else {
                proxyAuthorization = computeBasicAuthentication(proxyServer);
            }
        }

        return proxyAuthorization;
    }

    private byte[] computeBodyFromParams(List<Param> params, Charset bodyCharset) {

        StringBuilder sb = new StringBuilder();
        for (Param param : params) {
            UTF8UrlEncoder.appendEncoded(sb, param.getName());
            sb.append('=');
            UTF8UrlEncoder.appendEncoded(sb, param.getValue());
            sb.append('&');
        }
        sb.setLength(sb.length() - 1);
        return sb.toString().getBytes(bodyCharset);
    }

    private NettyBody body(Request request, HttpMethod method) throws IOException {
        NettyBody nettyBody = null;
        if (method != HttpMethod.CONNECT) {

            Charset bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : Charset.forName(request.getBodyEncoding());

            if (request.getByteData() != null) {
                nettyBody = new NettyByteArrayBody(request.getByteData());

            } else if (request.getStringData() != null) {
                nettyBody = new NettyByteArrayBody(request.getStringData().getBytes(bodyCharset));

            } else if (request.getStreamData() != null) {
                nettyBody = new NettyInputStreamBody(request.getStreamData());

            } else if (isNonEmpty(request.getFormParams())) {

                String contentType = null;
                if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE))
                    contentType = HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED;

                nettyBody = new NettyByteArrayBody(computeBodyFromParams(request.getFormParams(), bodyCharset), contentType);

            } else if (isNonEmpty(request.getParts())) {
                nettyBody = new NettyMultipartBody(request.getParts(), request.getHeaders(), nettyConfig);

            } else if (request.getFile() != null) {
                nettyBody = new NettyFileBody(request.getFile(), nettyConfig);

            } else if (request.getBodyGenerator() instanceof FileBodyGenerator) {
                FileBodyGenerator fileBodyGenerator = (FileBodyGenerator) request.getBodyGenerator();
                nettyBody = new NettyFileBody(fileBodyGenerator.getFile(), fileBodyGenerator.getRegionSeek(), fileBodyGenerator.getRegionLength(), nettyConfig);

            } else if (request.getBodyGenerator() instanceof InputStreamBodyGenerator) {
                nettyBody = new NettyInputStreamBody(InputStreamBodyGenerator.class.cast(request.getBodyGenerator()).getInputStream());

            } else if (request.getBodyGenerator() != null) {
                nettyBody = new NettyBodyBody(request.getBodyGenerator().createBody(), nettyConfig);
            }
        }

        return nettyBody;
    }

    public NettyRequest newNettyRequest(Request request, Uri uri, boolean forceConnect, ProxyServer proxyServer) throws IOException {

        HttpMethod method = forceConnect ? HttpMethod.CONNECT : HttpMethod.valueOf(request.getMethod());
        HttpVersion httpVersion = method == HttpMethod.CONNECT ? HttpVersion.HTTP_1_0 : HttpVersion.HTTP_1_1;
        String requestUri = requestUri(uri, proxyServer, method);

        NettyBody body = body(request, method);

        HttpRequest httpRequest;
        NettyRequest nettyRequest;
        if (body instanceof NettyByteArrayBody) {
            byte[] bytes = NettyByteArrayBody.class.cast(body).getBytes();
            httpRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri, Unpooled.wrappedBuffer(bytes));
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
            headers.set(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET)//
            .set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE)//
            .set(HttpHeaders.Names.ORIGIN, "http://" + uri.getHost() + ":" + (uri.getPort() == -1 ? isSecure(uri.getScheme()) ? 443 : 80 : uri.getPort()))//
            .set(HttpHeaders.Names.SEC_WEBSOCKET_KEY, getKey())//
            .set(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "13");

        } else if (!headers.contains(HttpHeaders.Names.CONNECTION)) {
            headers.set(HttpHeaders.Names.CONNECTION, keepAliveHeaderValue(config));
        }

        String hostHeader = hostHeader(request, uri);
        if (hostHeader != null)
            headers.set(HttpHeaders.Names.HOST, hostHeader);

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        String authorizationHeader = authorizationHeader(request, uri, proxyServer, realm);
        if (authorizationHeader != null)
            // don't override authorization but append
            headers.add(HttpHeaders.Names.AUTHORIZATION, authorizationHeader);

        String proxyAuthorizationHeader = proxyAuthorizationHeader(request, proxyServer, method);
        if (proxyAuthorizationHeader != null)
            headers.set(HttpHeaders.Names.PROXY_AUTHORIZATION, proxyAuthorizationHeader);

        // Add default accept headers
        if (!headers.contains(HttpHeaders.Names.ACCEPT))
            headers.set(HttpHeaders.Names.ACCEPT, "*/*");

        // Add default user agent
        if (!headers.contains(HttpHeaders.Names.USER_AGENT) && config.getUserAgent() != null)
            headers.set(HttpHeaders.Names.USER_AGENT, config.getUserAgent());

        return nettyRequest;
    }
}
