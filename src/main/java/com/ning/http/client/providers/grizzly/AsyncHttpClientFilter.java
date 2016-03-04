/*
 * Copyright (c) 2012-2016 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.grizzly;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.UpgradeHandler;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.providers.grizzly.events.ContinueEvent;
import com.ning.http.client.providers.grizzly.events.SSLSwitchingEvent;
import com.ning.http.client.uri.Uri;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.MiscUtils;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.CookieSerializerUtils;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HeaderValue;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.websockets.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grizzly higher level async HTTP client filter, that works as a bridge between
 * AHC and Grizzly HTTP APIs.
 * 
 * @author Grizzly team
 */
final class AsyncHttpClientFilter extends BaseFilter {
    private final static Logger LOGGER = LoggerFactory.getLogger(AsyncHttpClientFilter.class);
    
    private final static Attribute<Boolean> USED_CONNECTION =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                    AsyncHttpClientFilter.class.getName() + ".used-connection");
    
    // Lazy NTLM instance holder
    private static class NTLM_INSTANCE_HOLDER {
        private final static NTLMEngine ntlmEngine = new NTLMEngine();
    }
    
    private static final HeaderValue KEEP_ALIVE_VALUE = HeaderValue.newHeaderValue("keep-alive");
    private static final HeaderValue CLOSE_VALUE = HeaderValue.newHeaderValue("close");

    private final AsyncHttpClientConfig config;

    // -------------------------------------------------------- Constructors
    AsyncHttpClientFilter(final GrizzlyAsyncHttpProvider provider) {
        this.config = provider.getClientConfig();
    }

    // --------------------------------------------- Methods from BaseFilter
    @Override
    public NextAction handleWrite(final FilterChainContext ctx) throws IOException {
        final Object message = ctx.getMessage();
        if (message instanceof HttpTransactionContext) {
            ctx.setMessage(null);
            if (!sendAsGrizzlyRequest((HttpTransactionContext) message, ctx)) {
                return ctx.getSuspendAction();
            } else {
                return ctx.getStopAction();
            }
        }
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleEvent(final FilterChainContext ctx, final FilterChainEvent event) throws IOException {
        final Object type = event.type();
        if (type == ContinueEvent.class) {
            final ContinueEvent continueEvent = (ContinueEvent) event;
            continueEvent.getContext().payloadGenerator.continueConfirmed(ctx);
        }
        return ctx.getStopAction();
    }
    // ----------------------------------------------------- Private Methods

    private boolean sendAsGrizzlyRequest(final HttpTransactionContext httpTxCtx,
            final FilterChainContext ctx) throws IOException {
        
        final Connection connection = ctx.getConnection();
        
        final boolean isUsedConnection = Boolean.TRUE.equals(USED_CONNECTION.get(connection));
        if (!isUsedConnection) {
            USED_CONNECTION.set(connection, Boolean.TRUE);
        }
        
        final Request ahcRequest = httpTxCtx.getAhcRequest();
        if (isUpgradeRequest(httpTxCtx.getAsyncHandler()) &&
                isWSRequest(httpTxCtx.requestUri)) {
            httpTxCtx.isWSRequest = true;
            convertToUpgradeRequest(httpTxCtx);
        }
        final Request req = httpTxCtx.getAhcRequest();
        final Method method = Method.valueOf(ahcRequest.getMethod());
        final Uri uri = req.getUri();
        boolean secure = "https".equals(uri.getScheme());
        final ProxyServer proxy = httpTxCtx.getProxyServer();
        final boolean useProxy = proxy != null;
        final boolean isEstablishingConnectTunnel = useProxy &&
                (secure || httpTxCtx.isWSRequest) &&
                !httpTxCtx.isTunnelEstablished(connection);
        
        if (isEstablishingConnectTunnel) {
            // once the tunnel is established, sendAsGrizzlyRequest will
            // be called again and we'll finally send the request over the tunnel
            return establishConnectTunnel(proxy, httpTxCtx, uri, ctx);
        }        
        final HttpRequestPacket.Builder builder = HttpRequestPacket.builder()
                .protocol(Protocol.HTTP_1_1)
                .method(method);

        if (useProxy && !((secure || httpTxCtx.isWSRequest) &&
                config.isUseRelativeURIsWithConnectProxies())) {
            builder.uri(uri.toUrl());
        } else {
            builder.uri(AsyncHttpProviderUtils.getNonEmptyPath(uri))
                   .query(uri.getQuery());
        }

        HttpRequestPacket requestPacket;
        final PayloadGenerator payloadGenerator = isPayloadAllowed(method)
                ? PayloadGenFactory.getPayloadGenerator(ahcRequest)
                : null;
        
        if (payloadGenerator != null) {
            final long contentLength = ahcRequest.getContentLength();
            if (contentLength >= 0) {
                builder.contentLength(contentLength)
                       .chunked(false);
            } else {
                builder.chunked(true);
            }
        }
        if (httpTxCtx.isWSRequest) {
            try {
                final URI wsURI = httpTxCtx.wsRequestURI.toJavaNetURI();
                secure = "wss".equalsIgnoreCase(wsURI.getScheme());
                httpTxCtx.protocolHandler = Version.RFC6455.createHandler(true);
                httpTxCtx.handshake = httpTxCtx.protocolHandler.createClientHandShake(wsURI);
                requestPacket = (HttpRequestPacket) httpTxCtx.handshake.composeHeaders().getHttpHeader();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid WS URI: " + httpTxCtx.wsRequestURI);
            }
        } else {
            requestPacket = builder.build();
        }
        
        requestPacket.setSecure(secure);
        setupKeepAlive(requestPacket, connection);
        
        copyHeaders(ahcRequest, requestPacket);
        addCookies(ahcRequest, requestPacket);
        addHostHeaderIfNeeded(ahcRequest, uri, requestPacket);
        addServiceHeaders(requestPacket);
        addAcceptHeaders(requestPacket);
        
        final Realm realm = getRealm(ahcRequest);
        addAuthorizationHeader(ahcRequest, requestPacket, realm,
                uri, proxy, isUsedConnection);
        
        if (useProxy) {
            addProxyHeaders(ahcRequest, requestPacket, realm, proxy,
                    isUsedConnection, false);
        }

        ctx.notifyDownstream(new SSLSwitchingEvent(connection, secure,
                uri.getHost(), uri.getPort()));

       final boolean isFullySent = sendRequest(httpTxCtx, ctx, requestPacket,
                wrapWithExpectHandlerIfNeeded(payloadGenerator, requestPacket));
       if (isFullySent) {
           httpTxCtx.onRequestFullySent();
       }
       
       return isFullySent;
    }

    private boolean establishConnectTunnel(final ProxyServer proxy,
            final HttpTransactionContext httpCtx, final Uri uri,
            final FilterChainContext ctx) throws IOException {
        
        final Connection connection = ctx.getConnection();
        final HttpRequestPacket requestPacket = HttpRequestPacket.builder()
                .protocol(Protocol.HTTP_1_0)
                .method(Method.CONNECT)
                .uri(AsyncHttpProviderUtils.getAuthority(uri))
                .build();
        
        setupKeepAlive(requestPacket, connection);
        
        httpCtx.establishingTunnel = true;

        final Request request = httpCtx.getAhcRequest();
        addHostHeaderIfNeeded(request, uri, requestPacket);
        addServiceHeaders(requestPacket);
        
        final Realm realm = getRealm(request);
        addAuthorizationHeader(request, requestPacket, realm, uri, proxy, false);
        addProxyHeaders(request, requestPacket, realm, proxy, false, true);
        
        // turn off SSL, because CONNECT will be sent in plain mode
        ctx.notifyDownstream(new SSLSwitchingEvent(connection, false));
        
        return sendRequest(httpCtx, ctx, requestPacket, null);
    }

    @SuppressWarnings({"unchecked"})
    private boolean sendRequest(final HttpTransactionContext httpTxCtx,
                     final FilterChainContext ctx,
                     final HttpRequestPacket requestPacket,
                     final PayloadGenerator payloadGenerator)
    throws IOException {
        
        final Connection connection = httpTxCtx.getConnection();
        final Request request = httpTxCtx.getAhcRequest();
        final AsyncHandler h = httpTxCtx.getAsyncHandler();
        
        // create HttpContext and mutually bind it with HttpTransactionContext
        final HttpContext httpCtx = new AhcHttpContext(
                connection, connection, connection, requestPacket, httpTxCtx);
        HttpTransactionContext.bind(httpCtx, httpTxCtx);
        
        requestPacket.getProcessingState().setHttpContext(httpCtx);
        httpCtx.attach(ctx);
        
        if (h instanceof TransferCompletionHandler) {
            final FluentCaseInsensitiveStringsMap map
                    = new FluentCaseInsensitiveStringsMap(request.getHeaders());
            TransferCompletionHandler.class.cast(h).headers(map);
        }

        requestPacket.setConnection(ctx.getConnection());
        
        boolean isWriteComplete = true;
        
        if (payloadGenerator != null) { // Check if the HTTP request has body
            httpTxCtx.payloadGenerator = payloadGenerator;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("REQUEST: " + requestPacket.toString());
            }
            isWriteComplete = payloadGenerator.generate(ctx, request, requestPacket);
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("REQUEST: " + requestPacket.toString());
            }
            ctx.write(requestPacket, ctx.getTransportContext().getCompletionHandler());
        }

        
        return isWriteComplete;
    }
    
    /**
     * check if we need to wrap the PayloadGenerator with ExpectHandler
     */
    private PayloadGenerator wrapWithExpectHandlerIfNeeded(final PayloadGenerator payloadGenerator, final HttpRequestPacket requestPacket) {
        if (payloadGenerator == null) {
            return null;
        }
        // check if we need to wrap the PayloadGenerator with ExpectWrapper
        final MimeHeaders headers = requestPacket.getHeaders();
        final int expectHeaderIdx = headers.indexOf(Header.Expect, 0);
        return expectHeaderIdx != -1 && headers.getValue(expectHeaderIdx).equalsIgnoreCase("100-Continue")
                ? PayloadGenFactory.wrapWithExpect(payloadGenerator)
                : payloadGenerator;
    }

    private boolean isPayloadAllowed(final Method method) {
        return method.getPayloadExpectation() != Method.PayloadExpectation.NOT_ALLOWED;
    }

    private void addAuthorizationHeader(final Request req,
            final HttpRequestPacket requestPacket,
            final Realm realm,
            final Uri uri, ProxyServer proxy,
            final boolean isUsedConnection) throws IOException {
        
        if (!isUsedConnection) {
            final String conAuth =
                    AuthenticatorUtils.perConnectionAuthorizationHeader(
                            req, uri, proxy, realm);
            if (conAuth != null) {
                requestPacket.addHeader(Header.Authorization, conAuth);
            }
        }
        
        final String reqAuth = AuthenticatorUtils.perRequestAuthorizationHeader(
                req, uri, realm);
        if (reqAuth != null) {
            requestPacket.addHeader(Header.Authorization, reqAuth);
        }
    }

    private void addProxyHeaders(
            final Request req,
            final HttpRequestPacket requestPacket,
            final Realm realm,
            final ProxyServer proxy,
            final boolean isUsedConnection,
            final boolean isConnect) throws IOException {
        
        setKeepAliveForHeader(Header.ProxyConnection, requestPacket);
        setProxyAuthorizationHeader(req, requestPacket, proxy, realm,
                isUsedConnection, isConnect);
    }

    private void setProxyAuthorizationHeader(final Request req,
            final HttpRequestPacket requestPacket, final ProxyServer proxy,
            final Realm realm, final boolean isUsedConnection,
            final boolean isConnect) throws IOException {
        final String reqAuth = AuthenticatorUtils.perRequestProxyAuthorizationHeader(
                req, realm, proxy, isConnect);
        
        if (reqAuth != null) {
            requestPacket.setHeader(Header.ProxyAuthorization, reqAuth);
            return;
        }
        
        if (!isUsedConnection) {
            final String conAuth =
                    AuthenticatorUtils.perConnectionProxyAuthorizationHeader(
                            req, proxy, isConnect);
            if (conAuth != null) {
                requestPacket.setHeader(Header.ProxyAuthorization, conAuth);
            }
        }
    }

    private void addHostHeaderIfNeeded(final Request request, final Uri uri,
            final HttpRequestPacket requestPacket) {
        if (!requestPacket.containsHeader(Header.Host)) {
            String host = request.getVirtualHost();
            if (host != null) {
                requestPacket.addHeader(Header.Host, host);
            } else {
                if (uri.getPort() == -1) {
                    requestPacket.addHeader(Header.Host, uri.getHost());
                } else {
                    requestPacket.addHeader(Header.Host, uri.getHost() + ':' + uri.getPort());
                }
            }
        }
    }

    private Realm getRealm(final Request request) {
        return request.getRealm() != null ? request.getRealm() : config.getRealm();
    }

    private String generateAuthHeader(final Connection c, final Realm realm) {
        try {
            switch (realm.getScheme()) {
                case BASIC:
                    return AuthenticatorUtils.computeBasicAuthentication(realm);
                case DIGEST:
                    return AuthenticatorUtils.computeDigestAuthentication(realm);
                case NTLM:
                    return !Utils.getAndSetNtlmAttempted(c) ? "NTLM " + NTLM_INSTANCE_HOLDER.ntlmEngine.generateType1Msg() : null;
                default:
                    return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isUpgradeRequest(final AsyncHandler handler) {
        return handler instanceof UpgradeHandler;
    }

    private boolean isWSRequest(final Uri requestUri) {
        return requestUri.getScheme().startsWith("ws");
    }

    private void convertToUpgradeRequest(final HttpTransactionContext ctx) {
        final Uri requestUri = ctx.requestUri;
        ctx.wsRequestURI = requestUri;
        ctx.requestUri = requestUri.withNewScheme("ws".equals(requestUri.getScheme()) ? "http" : "https");
    }

    private void copyHeaders(final Request request, final HttpRequestPacket requestPacket) {
        final FluentCaseInsensitiveStringsMap map = request.getHeaders();
        if (MiscUtils.isNonEmpty(map)) {
            for (final Map.Entry<String, List<String>> entry : map.entrySet()) {
                final String headerName = entry.getKey();
                final List<String> headerValues = entry.getValue();
                if (MiscUtils.isNonEmpty(headerValues)) {
                    for (final String headerValue : headerValues) {
                        requestPacket.addHeader(headerName, headerValue);
                    }
                }
            }
        }
    }

    private void addServiceHeaders(final HttpRequestPacket requestPacket) {
        final MimeHeaders headers = requestPacket.getHeaders();

        if (!headers.contains(Header.UserAgent)) {
            headers.addValue(Header.UserAgent).setString(config.getUserAgent());
        }
        
        setKeepAliveForHeader(Header.Connection, requestPacket);
    }

    private void setKeepAliveForHeader(final Header header,
            final HttpRequestPacket requestPacket) {
        
        final MimeHeaders headers = requestPacket.getHeaders();

        // Assign Connection: ... if needed
        if (!headers.contains(header)) {
            if (requestPacket.getProcessingState().isKeepAlive()) {
                headers.addValue(header).setBytes(KEEP_ALIVE_VALUE.getByteArray());
            } else if (Protocol.HTTP_1_1.equals(requestPacket.getProtocol())) {
                headers.addValue(header).setBytes(CLOSE_VALUE.getByteArray());
            }
//            switch (requestPacket.getProtocol()) {
//                case HTTP_0_9:
//                case HTTP_1_0:
//                    if (requestPacket.getProcessingState().isKeepAlive()) {
//                        headers.addValue(header).setBytes(KEEP_ALIVE_VALUE.getByteArray());
//                    }
//                    break;
//                case HTTP_1_1:
//                    if (!requestPacket.getProcessingState().isKeepAlive()) {
//                        headers.addValue(header).setBytes(CLOSE_VALUE.getByteArray());
//                    }
//                    break;
//            }
        }
    }

    private void addAcceptHeaders(final HttpRequestPacket requestPacket) {
        final MimeHeaders headers = requestPacket.getHeaders();
        if (config.isCompressionEnforced() && !headers.contains(Header.AcceptEncoding)) {
            headers.addValue(Header.AcceptEncoding).setString("gzip");
        }
        if (!headers.contains(Header.Accept)) {
            headers.addValue(Header.Accept).setString("*/*");
        }
    }

    private void addCookies(final Request request, final HttpRequestPacket requestPacket) {
        final Collection<Cookie> cookies = request.getCookies();
        if (MiscUtils.isNonEmpty(cookies)) {
            StringBuilder sb = new StringBuilder(128);
            org.glassfish.grizzly.http.Cookie[] gCookies = new org.glassfish.grizzly.http.Cookie[cookies.size()];
            convertCookies(cookies, gCookies);
            CookieSerializerUtils.serializeClientCookies(sb, gCookies);
            requestPacket.addHeader(Header.Cookie, sb.toString());
        }
    }

    private void convertCookies(final Collection<Cookie> cookies, final org.glassfish.grizzly.http.Cookie[] gCookies) {
        int idx = 0;
        for (final Cookie cookie : cookies) {
            gCookies[idx++] = new org.glassfish.grizzly.http.Cookie(cookie.getName(), cookie.getValue());
        }
    }
    
    private void setupKeepAlive(final HttpRequestPacket request,
            final Connection connection) {
        request.getProcessingState().setKeepAlive(
                ConnectionManager.isKeepAlive(connection));
    }
} // END AsyncHttpClientFiler
