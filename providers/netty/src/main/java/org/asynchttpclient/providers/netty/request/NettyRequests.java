/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.request;

import static org.asynchttpclient.providers.netty.util.HttpUtil.*;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.DEFAULT_CHARSET;
import static org.asynchttpclient.util.MiscUtil.isNonEmpty;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.multipart.MultipartRequestEntity;
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.ntlm.NTLMEngineException;
import org.asynchttpclient.org.jboss.netty.handler.codec.http.CookieEncoder;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProvider;
import org.asynchttpclient.providers.netty.ws.WebSocketUtil;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.AuthenticatorUtils;
import org.asynchttpclient.util.UTF8UrlEncoder;

public class NettyRequests {

    public static HttpRequest newNettyRequest(AsyncHttpClientConfig config, Request request, URI uri, boolean allowConnect, ProxyServer proxyServer) throws IOException {

        HttpMethod method = null;
        if (allowConnect && proxyServer != null && isSecure(uri))
            method = HttpMethod.CONNECT;
        else
            method = HttpMethod.valueOf(request.getMethod());
        
        String host = null;
        HttpVersion httpVersion;
        String requestUri;
        Map<String, Object> headers = new HashMap<String, Object>();
        ByteBuf content = null;
        boolean webSocket = isWebSocket(uri);

        if (request.getVirtualHost() != null) {
            host = request.getVirtualHost();
        } else {
            host = AsyncHttpProviderUtils.getHost(uri);
        }

        if (method == HttpMethod.CONNECT) {
            httpVersion = HttpVersion.HTTP_1_0;
            requestUri = AsyncHttpProviderUtils.getAuthority(uri);
        } else {
            httpVersion = HttpVersion.HTTP_1_1;
            if (proxyServer != null && !(isSecure(uri) && config.isUseRelativeURIsWithSSLProxies()))
                requestUri = uri.toString();
            else if (uri.getRawQuery() != null)
                requestUri = uri.getRawPath() + "?" + uri.getRawQuery();
            else
                requestUri = uri.getRawPath();
        }

        if (webSocket) {
            headers.put(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET);
            headers.put(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE);
            headers.put(HttpHeaders.Names.ORIGIN, "http://" + uri.getHost() + ":" + (uri.getPort() == -1 ? isSecure(uri.getScheme()) ? 443 : 80 : uri.getPort()));
            headers.put(HttpHeaders.Names.SEC_WEBSOCKET_KEY, WebSocketUtil.getKey());
            headers.put(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "13");
        }

        if (host != null) {
            if (request.getVirtualHost() != null || uri.getPort() == -1) {
                headers.put(HttpHeaders.Names.HOST, host);
            } else {
                headers.put(HttpHeaders.Names.HOST, host + ":" + uri.getPort());
            }
        } else {
            host = "127.0.0.1";
        }

        if (method != HttpMethod.CONNECT) {
            FluentCaseInsensitiveStringsMap h = request.getHeaders();
            if (h != null) {
                for (Entry<String, List<String>> header : h) {
                    String name = header.getKey();
                    if (!HttpHeaders.Names.HOST.equalsIgnoreCase(name)) {
                        for (String value : header.getValue()) {
                            headers.put(name, value);
                        }
                    }
                }
            }

            if (config.isCompressionEnabled()) {
                headers.put(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
            }
        } else {
            List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
            if (isNTLM(auth)) {
                headers.put(HttpHeaders.Names.PROXY_AUTHORIZATION, auth.get(0));
            }
        }
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        if (realm != null && realm.getUsePreemptiveAuth()) {

            String domain = realm.getNtlmDomain();
            if (proxyServer != null && proxyServer.getNtlmDomain() != null) {
                domain = proxyServer.getNtlmDomain();
            }

            String authHost = realm.getNtlmHost();
            if (proxyServer != null && proxyServer.getHost() != null) {
                host = proxyServer.getHost();
            }

            switch (realm.getAuthScheme()) {
            case BASIC:
                headers.put(HttpHeaders.Names.AUTHORIZATION, AuthenticatorUtils.computeBasicAuthentication(realm));
                break;
            case DIGEST:
                if (isNonEmpty(realm.getNonce())) {
                    try {
                        headers.put(HttpHeaders.Names.AUTHORIZATION, AuthenticatorUtils.computeDigestAuthentication(realm));
                    } catch (NoSuchAlgorithmException e) {
                        throw new SecurityException(e);
                    }
                }
                break;
            case NTLM:
                try {
                    String msg = NTLMEngine.INSTANCE.generateType1Msg("NTLM " + domain, authHost);
                    headers.put(HttpHeaders.Names.AUTHORIZATION, "NTLM " + msg);
                } catch (NTLMEngineException e) {
                    throw new IOException(e);
                }
                break;
            case KERBEROS:
            case SPNEGO:
                String challengeHeader = null;
                String server = proxyServer == null ? host : proxyServer.getHost();
                try {
                    challengeHeader = SpnegoEngine.instance().generateToken(server);
                } catch (Throwable e) {
                    throw new IOException(e);
                }
                headers.put(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);
                break;
            case NONE:
                break;
            default:
                throw new IllegalStateException("Invalid Authentication " + realm);
            }
        }

        if (!webSocket && !request.getHeaders().containsKey(HttpHeaders.Names.CONNECTION)) {
            headers.put(HttpHeaders.Names.CONNECTION, AsyncHttpProviderUtils.keepAliveHeaderValue(config));
        }

        if (proxyServer != null) {
            if (!request.getHeaders().containsKey("Proxy-Connection")) {
                headers.put("Proxy-Connection", AsyncHttpProviderUtils.keepAliveHeaderValue(config));
            }

            if (proxyServer.getPrincipal() != null) {
                if (isNonEmpty(proxyServer.getNtlmDomain())) {

                    List<String> auth = request.getHeaders().get(HttpHeaders.Names.PROXY_AUTHORIZATION);
                    if (!isNTLM(auth)) {
                        try {
                            String msg = NTLMEngine.INSTANCE.generateType1Msg(proxyServer.getNtlmDomain(), proxyServer.getHost());
                            headers.put(HttpHeaders.Names.PROXY_AUTHORIZATION, "NTLM " + msg);
                        } catch (NTLMEngineException e) {
                            IOException ie = new IOException();
                            ie.initCause(e);
                            throw ie;
                        }
                    }
                } else {
                    headers.put(HttpHeaders.Names.PROXY_AUTHORIZATION, AuthenticatorUtils.computeBasicAuthentication(proxyServer));
                }
            }
        }

        // Add default accept headers
        if (!request.getHeaders().containsKey(HttpHeaders.Names.ACCEPT)) {
            headers.put(HttpHeaders.Names.ACCEPT, "*/*");
        }

        String userAgentHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.USER_AGENT);
        if (userAgentHeader != null) {
            headers.put(HttpHeaders.Names.USER_AGENT, userAgentHeader);
        } else if (config.getUserAgent() != null) {
            headers.put(HttpHeaders.Names.USER_AGENT, config.getUserAgent());
        } else {
            headers.put(HttpHeaders.Names.USER_AGENT, AsyncHttpProviderUtils.constructUserAgent(NettyAsyncHttpProvider.class, config));
        }

        boolean hasDeferredContent = false;
        if (method != HttpMethod.CONNECT) {
            if (isNonEmpty(request.getCookies())) {
                headers.put(HttpHeaders.Names.COOKIE, CookieEncoder.encodeClientSide(request.getCookies(), config.isRfc6265CookieEncoding()));
            }

            if (method != HttpMethod.HEAD && method != HttpMethod.OPTIONS && method != HttpMethod.TRACE) {

                String bodyCharset = request.getBodyEncoding() == null ? DEFAULT_CHARSET : request.getBodyEncoding();

                if (request.getByteData() != null) {
                    headers.put(HttpHeaders.Names.CONTENT_LENGTH, request.getByteData().length);
                    content = Unpooled.wrappedBuffer(request.getByteData());

                } else if (request.getStringData() != null) {
                    byte[] bytes = request.getStringData().getBytes(bodyCharset);
                    headers.put(HttpHeaders.Names.CONTENT_LENGTH, bytes.length);
                    content = Unpooled.wrappedBuffer(bytes);

                } else if (request.getStreamData() != null) {
                    hasDeferredContent = true;
                    headers.put(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);

                } else if (isNonEmpty(request.getParams())) {
                    StringBuilder sb = new StringBuilder();
                    for (final Entry<String, List<String>> paramEntry : request.getParams()) {
                        final String key = paramEntry.getKey();
                        for (final String value : paramEntry.getValue()) {
                            UTF8UrlEncoder.appendEncoded(sb, key);
                            sb.append("=");
                            UTF8UrlEncoder.appendEncoded(sb, value);
                            sb.append("&");
                        }
                    }
                    sb.setLength(sb.length() - 1);
                    byte[] bytes = sb.toString().getBytes(bodyCharset);
                    headers.put(HttpHeaders.Names.CONTENT_LENGTH, bytes.length);
                    content = Unpooled.wrappedBuffer(bytes);

                    if (!request.getHeaders().containsKey(HttpHeaders.Names.CONTENT_TYPE)) {
                        headers.put(HttpHeaders.Names.CONTENT_TYPE, HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED);
                    }

                } else if (request.getParts() != null) {
                    // FIXME use Netty multipart
                    MultipartRequestEntity mre = AsyncHttpProviderUtils.createMultipartRequestEntity(request.getParts(), request.getHeaders());

                    headers.put(HttpHeaders.Names.CONTENT_TYPE, mre.getContentType());
                    headers.put(HttpHeaders.Names.CONTENT_LENGTH, mre.getContentLength());
                    hasDeferredContent = true;

                } else if (request.getFile() != null) {
                    File file = request.getFile();
                    if (!file.isFile()) {
                        throw new IOException(String.format("File %s is not a file or doesn't exist", file.getAbsolutePath()));
                    }
                    headers.put(HttpHeaders.Names.CONTENT_LENGTH, file.length());
                    hasDeferredContent = true;

                } else if (request.getBodyGenerator() != null) {
                    hasDeferredContent = true;
                }
            }
        }

        HttpRequest nettyRequest;
        if (hasDeferredContent) {
            nettyRequest = new DefaultHttpRequest(httpVersion, method, requestUri);
        } else if (content != null) {
            nettyRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri, content);
        } else {
            nettyRequest = new DefaultFullHttpRequest(httpVersion, method, requestUri);
        }
        for (Entry<String, Object> header : headers.entrySet()) {
            nettyRequest.headers().set(header.getKey(), header.getValue());
        }
        return nettyRequest;
    }
}
