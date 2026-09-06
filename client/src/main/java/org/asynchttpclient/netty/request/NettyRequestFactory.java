/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.util.AsciiString;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.netty.request.body.NettyBody;
import org.asynchttpclient.netty.request.body.NettyBodyBody;
import org.asynchttpclient.netty.request.body.NettyByteArrayBody;
import org.asynchttpclient.netty.request.body.NettyByteBufBody;
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
import org.asynchttpclient.util.StringUtils;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_LANGUAGE;
import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.ORIGIN;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.REFERER;
import static io.netty.handler.codec.http.HttpHeaderNames.SEC_WEBSOCKET_KEY;
import static io.netty.handler.codec.http.HttpHeaderNames.SEC_WEBSOCKET_VERSION;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.UPGRADE;
import static io.netty.handler.codec.http.HttpHeaderNames.USER_AGENT;
import static org.asynchttpclient.util.AuthenticatorUtils.perRequestAuthorizationHeader;
import static org.asynchttpclient.util.AuthenticatorUtils.perRequestProxyAuthorizationHeader;
import static org.asynchttpclient.util.HttpUtils.ACCEPT_ALL_HEADER_VALUE;
import static org.asynchttpclient.util.HttpUtils.GZIP_DEFLATE;
import static org.asynchttpclient.util.HttpUtils.filterOutBrotliFromAcceptEncoding;
import static org.asynchttpclient.util.HttpUtils.filterOutZstdFromAcceptEncoding;
import static org.asynchttpclient.util.HttpUtils.hostHeader;
import static org.asynchttpclient.util.HttpUtils.originHeader;
import static org.asynchttpclient.util.HttpUtils.urlEncodeFormParams;
import static org.asynchttpclient.util.MiscUtils.closeSilently;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.ws.WebSocketUtils.getWebSocketKey;

public final class NettyRequestFactory {

    private static final Integer ZERO_CONTENT_LENGTH = 0;

    /**
     * Known header-name spelling -> a pre-built {@link AsciiString} with the same bytes. Built once. When a
     * user-supplied {@code String} name is byte-identical to a known lowercase or Train-Case spelling, the
     * outbound header uses a shared {@code AsciiString} so {@code HttpHeadersEncoder} hits its bulk array-copy
     * branch instead of the per-char US-ASCII encode loop on the event loop. The map is keyed case-sensitively:
     * custom names and odd casing miss and are emitted verbatim, so on-wire bytes are never altered.
     */
    private static final Map<String, AsciiString> KNOWN_HEADER_NAMES = buildKnownHeaderNames();

    private static Map<String, AsciiString> buildKnownHeaderNames() {
        AsciiString[] names = {
                ACCEPT,
                ACCEPT_ENCODING,
                ACCEPT_LANGUAGE,
                AUTHORIZATION,
                CACHE_CONTROL,
                CONNECTION,
                CONTENT_LENGTH,
                CONTENT_TYPE,
                COOKIE,
                HOST,
                ORIGIN,
                REFERER,
                TRANSFER_ENCODING,
                USER_AGENT,
                new AsciiString("Accept"),
                new AsciiString("Accept-Encoding"),
                new AsciiString("Accept-Language"),
                new AsciiString("Authorization"),
                new AsciiString("Cache-Control"),
                new AsciiString("Connection"),
                new AsciiString("Content-Length"),
                new AsciiString("Content-Type"),
                new AsciiString("Cookie"),
                new AsciiString("Host"),
                new AsciiString("Origin"),
                new AsciiString("Referer"),
                new AsciiString("Transfer-Encoding"),
                new AsciiString("User-Agent")
        };
        Map<String, AsciiString> map = new HashMap<>((int) (names.length / 0.75f) + 1);
        for (AsciiString name : names) {
            map.put(name.toString(), name);
        }
        return map;
    }

    /**
     * Copy {@code source} request headers into the freshly created outbound {@code target}, interning
     * recognized header names to their shared {@link AsciiString} (see {@link #KNOWN_HEADER_NAMES}) so the
     * encode hot path bulk-copies instead of per-char encoding. Multi-value headers and ordering are preserved
     * (one {@code add} per stored entry, in iteration order). A name already stored as an {@code AsciiString}
     * is on the fast path already and passed through untouched.
     */
    // package-private for unit testing; not part of the public API.
    static void copyInternedHeaders(HttpHeaders source, HttpHeaders target) {
        Iterator<Map.Entry<CharSequence, CharSequence>> it = source.iteratorCharSequence();
        while (it.hasNext()) {
            Map.Entry<CharSequence, CharSequence> entry = it.next();
            CharSequence name = entry.getKey();
            if (name instanceof String) {
                AsciiString interned = KNOWN_HEADER_NAMES.get(name);
                if (interned != null) {
                    name = interned;
                }
            }
            target.add(name, entry.getValue());
        }
    }

    /**
     * Adds the request's cookies to any {@code Cookie} header the caller set, instead of replacing it. Where
     * both name one cookie the caller's wins. The caller's text is kept verbatim: a decode and re-encode would
     * drop values the strict decoder rejects. One field only, as RFC 6265 Section 5.4 requires.
     */
    private void mergeCookies(List<Cookie> cookies, HttpHeaders headers) {
        List<String> callerHeaders = headers.getAll(COOKIE);
        if (callerHeaders.isEmpty()) {
            if (isNonEmpty(cookies)) {
                headers.set(COOKIE, cookieEncoder.encode(cookies));
            }
            return;
        }
        if (callerHeaders.size() == 1 && !isNonEmpty(cookies)) {
            // Nothing to merge: leave the caller's bytes alone, unless the field is blank.
            if (callerHeaders.get(0).trim().isEmpty()) {
                headers.remove(COOKIE);
            }
            return;
        }

        StringBuilder merged = new StringBuilder();
        Set<String> callerNames = new HashSet<>(4);
        for (String callerHeader : callerHeaders) {
            // Appended as one run, trimming only the delimiters at the ends so the join cannot double them.
            int start = 0;
            int end = callerHeader.length();
            while (start < end && (callerHeader.charAt(start) == ';' || callerHeader.charAt(start) <= ' ')) {
                start++;
            }
            while (end > start && (callerHeader.charAt(end - 1) == ';' || callerHeader.charAt(end - 1) <= ' ')) {
                end--;
            }
            if (start == end) {
                continue;
            }
            if (merged.length() > 0) {
                merged.append("; ");
            }
            merged.append(callerHeader, start, end);
            for (String pair : callerHeader.split(";")) {
                int eq = pair.indexOf('=');
                // Case-sensitive, but trimmed: "a = 1" still names "a".
                String name = (eq >= 0 ? pair.substring(0, eq) : pair).trim();
                if (!name.isEmpty()) {
                    callerNames.add(name);
                }
            }
        }

        List<Cookie> notSetByCaller = new ArrayList<>(cookies.size());
        for (Cookie cookie : cookies) {
            if (!callerNames.contains(cookie.name())) {
                notSetByCaller.add(cookie);
            }
        }
        if (!notSetByCaller.isEmpty()) {
            if (merged.length() > 0) {
                merged.append("; ");
            }
            merged.append(cookieEncoder.encode(notSetByCaller));
        }

        if (merged.length() == 0) {
            headers.remove(COOKIE);
        } else {
            headers.set(COOKIE, merged.toString());
        }
    }

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
        } else if (request.getByteBufData() != null) {
            nettyBody = new NettyByteBufBody(request.getByteBufData());
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
            InputStreamBodyGenerator inStreamGenerator = (InputStreamBodyGenerator) request.getBodyGenerator();
            nettyBody = new NettyInputStreamBody(inStreamGenerator.getInputStream(), inStreamGenerator.getContentLength());
        } else if (request.getBodyGenerator() != null) {
            nettyBody = new NettyBodyBody(request.getBodyGenerator().createBody(), config);
        }

        return nettyBody;
    }

    public void addAuthorizationHeader(HttpHeaders headers, String authorizationHeader) {
        if (authorizationHeader != null) {
            // don't override authorization but append
            headers.add(AUTHORIZATION, authorizationHeader);
        }
    }

    public void setProxyAuthorizationHeader(HttpHeaders headers, String proxyAuthorizationHeader) {
        if (proxyAuthorizationHeader != null) {
            headers.set(PROXY_AUTHORIZATION, proxyAuthorizationHeader);
        }
    }

    private static void addProxyCustomHeaders(HttpHeaders headers, Request request, ProxyServer proxyServer) {
        Function<Request, HttpHeaders> customHeaders = proxyServer.getCustomHeaders();
        if (customHeaders == null) {
            return;
        }
        HttpHeaders proxyHeaders = customHeaders.apply(request);
        if (proxyHeaders == null) {
            return;
        }
        for (String name : proxyHeaders.names()) {
            // Framing is the message's, not the caller's: on a CONNECT, which has no body, Netty would
            // write a chunk terminator into the tunnel. Upgrade goes with them.
            if (CONTENT_LENGTH.contentEqualsIgnoreCase(name) || TRANSFER_ENCODING.contentEqualsIgnoreCase(name)
                    || UPGRADE.contentEqualsIgnoreCase(name)) {
                continue;
            }
            if (CONNECTION.contentEqualsIgnoreCase(name)) {
                // List-valued, and replacing it would drop the close token keepAlive=false put there.
                headers.add(name, proxyHeaders.getAll(name));
            } else {
                // Replace: a second Host line is a 400 from a conforming recipient (RFC 9112 section 3.2).
                headers.set(name, proxyHeaders.getAll(name));
            }
        }
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
            ByteBuf buf = ((NettyDirectBody) body).byteBuf();
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
            copyInternedHeaders(request.getHeaders(), headers);

            // Unconditional: it also folds several caller-set Cookie fields into one.
            mergeCookies(request.getCookies(), headers);

            String userDefinedAcceptEncoding = headers.get(ACCEPT_ENCODING);
            if (userDefinedAcceptEncoding != null) {
                if (config.isEnableAutomaticDecompression()) {
                    if (!Brotli.isAvailable()) {
                        // Brotli is not available.
                        // For manual decompression by user, any encoding may suite, so leave untouched
                        headers.set(ACCEPT_ENCODING, filterOutBrotliFromAcceptEncoding(userDefinedAcceptEncoding));
                    }
                    if (!Zstd.isAvailable()) {
                        // zstd is not available.
                        // For manual decompression by user, any encoding may suit, so leave untouched
                        headers.set(ACCEPT_ENCODING, filterOutZstdFromAcceptEncoding(userDefinedAcceptEncoding));
                    }
                }
            } else if (config.isCompressionEnforced()) {
                // Add Accept Encoding header if compression is enforced
                headers.set(ACCEPT_ENCODING, GZIP_DEFLATE);
                if (Brotli.isAvailable()) {
                    headers.add(ACCEPT_ENCODING, HttpHeaderValues.BR);
                }
                if (Zstd.isAvailable()) {
                    headers.add(ACCEPT_ENCODING, HttpHeaderValues.ZSTD);
                }
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
        // Only set proxy auth on a request sent to an HTTP(S) proxy: either over plain HTTP (the origin
        // request carries an absolute URI straight to the proxy) or on a CONNECT (sent to the proxy to open
        // the tunnel). A SOCKS proxy tunnels at the transport layer, so the request reaches the ORIGIN — a
        // Proxy-Authorization header would leak the proxy credentials to the origin. Guard on the proxy type.
        // A ws:// request is tunnelled through CONNECT the same way wss:// is (see
        // NettyRequestSender.needConnect), so the upgrade request that follows also reaches the origin, not
        // the proxy; exclude it from the plain-HTTP branch the same way wss:// already is.
        // Custom headers are proxy-scoped too, so they travel under the same gate, and before the generated
        // header so a realm still decides Proxy-Authorization.
        if ((connect || (!uri.isSecured() && !uri.isWebSocket())) && proxyServer != null && proxyServer.getProxyType().isHttp()) {
            try {
                addProxyCustomHeaders(headers, request, proxyServer);
            } catch (RuntimeException | Error e) {
                // Caller code, and a CR or LF in what it returns throws here too. Nothing owns nettyRequest
                // until this method returns, so a throw would strand the body it already holds.
                nettyRequest.release();
                if (body instanceof NettyBodyBody) {
                    closeSilently(((NettyBodyBody) body).getBody());
                }
                throw e;
            }
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

    private static String requestUri(Uri uri, ProxyServer proxyServer, boolean connect) {
        if (connect) {
            // proxy tunnelling, connect need host and explicit port
            return uri.getAuthority();

        } else if (proxyServer != null && !uri.isSecured() && !uri.isWebSocket() && proxyServer.getProxyType().isHttp()) {
            // proxy over HTTP, need full url, minus the userinfo: this request line is sent to the proxy in
            // the clear and RFC 9110 §4.2.4 forbids userinfo in a generated request target. A ws:// request
            // is tunnelled through CONNECT, so its upgrade request reaches the origin in origin-form (below)
            return uri.toUrlWithoutUserInfo();

        } else {
            // direct connection to target host or tunnel already connected: only path and query
            return uri.toRelativeUrl();
        }
    }

    private static CharSequence connectionHeader(boolean keepAlive, HttpVersion httpVersion) {
        if (httpVersion.isKeepAliveDefault()) {
            return keepAlive ? null : HttpHeaderValues.CLOSE;
        } else {
            return keepAlive ? HttpHeaderValues.KEEP_ALIVE : null;
        }
    }
}
