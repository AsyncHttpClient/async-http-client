/*
 * Copyright (c) 2013-2014 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly.filters;

import static org.asynchttpclient.providers.grizzly.filters.SwitchingSSLFilter.getHandshakeError;
import static org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider.NTLM_ENGINE;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getAuthority;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getNonEmptyPath;
import static org.asynchttpclient.util.AuthenticatorUtils.computeBasicAuthentication;
import static org.asynchttpclient.util.AuthenticatorUtils.computeDigestAuthentication;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.UpgradeHandler;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;
import org.asynchttpclient.providers.grizzly.GrizzlyResponseFuture;
import org.asynchttpclient.providers.grizzly.HttpTxContext;
import org.asynchttpclient.providers.grizzly.RequestInfoHolder;
import org.asynchttpclient.providers.grizzly.Utils;
import org.asynchttpclient.providers.grizzly.bodyhandler.BodyHandler;
import org.asynchttpclient.providers.grizzly.bodyhandler.BodyHandlerFactory;
import org.asynchttpclient.providers.grizzly.bodyhandler.ExpectHandler;
import org.asynchttpclient.providers.grizzly.filters.events.ContinueEvent;
import org.asynchttpclient.providers.grizzly.filters.events.SSLSwitchingEvent;
import org.asynchttpclient.providers.grizzly.filters.events.TunnelRequestEvent;
import org.asynchttpclient.uri.UriComponents;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.ProcessingState;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.CookieSerializerUtils;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.spdy.SpdySession;
import org.glassfish.grizzly.spdy.SpdyStream;
import org.glassfish.grizzly.ssl.SSLConnectionContext;
import org.glassfish.grizzly.ssl.SSLUtils;
import org.glassfish.grizzly.websockets.Version;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;

/**
 * This {@link org.glassfish.grizzly.filterchain.Filter} is typically the last in the {@FilterChain}. Its primary responsibility is converting the async-http-client
 * {@link Request} into a Grizzly {@link HttpRequestPacket}.
 * 
 * @since 1.7
 * @author The Grizzly Team
 */
public final class AsyncHttpClientFilter extends BaseFilter {

    private ConcurrentLinkedQueue<HttpRequestPacketImpl> requestCache = new ConcurrentLinkedQueue<HttpRequestPacketImpl>();
    private final Logger logger;

    private final AsyncHttpClientConfig config;
    private final GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider;
    private final BodyHandlerFactory bodyHandlerFactory;

    private static final Attribute<Boolean> PROXY_AUTH_FAILURE = Grizzly.DEFAULT_ATTRIBUTE_BUILDER
            .createAttribute(AsyncHttpClientFilter.class.getName() + "-PROXY-AUTH_FAILURE");

    // -------------------------------------------------------- Constructors

    public AsyncHttpClientFilter(GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider, final AsyncHttpClientConfig config) {
        this.grizzlyAsyncHttpProvider = grizzlyAsyncHttpProvider;
        this.config = config;
        bodyHandlerFactory = new BodyHandlerFactory(grizzlyAsyncHttpProvider);
        logger = GrizzlyAsyncHttpProvider.LOGGER;
    }

    // --------------------------------------------- Methods from BaseFilter

    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {
        final HttpContent httpContent = ctx.getMessage();
        if (httpContent.isLast()) {
            // Perform the cleanup logic if it's the last chunk of the payload
            final HttpResponsePacket response = (HttpResponsePacket) httpContent.getHttpHeader();

            recycleRequestResponsePackets(ctx.getConnection(), response);
            return ctx.getStopAction();
        }

        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleWrite(final FilterChainContext ctx) throws IOException {

        Object message = ctx.getMessage();
        if (message instanceof RequestInfoHolder) {
            ctx.setMessage(null);
            if (!sendAsGrizzlyRequest((RequestInfoHolder) message, ctx)) {
                return ctx.getSuspendAction();
            }
        } else if (message instanceof Buffer) {
            return ctx.getInvokeAction();
        }

        return ctx.getStopAction();
    }

    @SuppressWarnings("unchecked")
    @Override
    public NextAction handleEvent(final FilterChainContext ctx, final FilterChainEvent event) throws IOException {

        final Object type = event.type();
        if (type == ContinueEvent.class) {
            final ContinueEvent continueEvent = (ContinueEvent) event;
            ((ExpectHandler) continueEvent.getContext().getBodyHandler()).finish(ctx);
        } else if (type == TunnelRequestEvent.class) {
            // Disable SSL for the time being...
            ctx.notifyDownstream(new SSLSwitchingEvent(false, ctx.getConnection()));
            ctx.suspend();
            TunnelRequestEvent tunnelRequestEvent = (TunnelRequestEvent) event;
            final ProxyServer proxyServer = tunnelRequestEvent.getProxyServer();
            final UriComponents requestUri = tunnelRequestEvent.getUri();

            RequestBuilder builder = new RequestBuilder();
            builder.setMethod(Method.CONNECT.getMethodString());
            builder.setUrl("http://" + getAuthority(requestUri));
            Request request = builder.build();

            AsyncHandler handler = new AsyncCompletionHandler() {
                @Override
                public Object onCompleted(Response response) throws Exception {
                    if (response.getStatusCode() != 200) {
                        PROXY_AUTH_FAILURE.set(ctx.getConnection(), Boolean.TRUE);
                    }
                    ctx.notifyDownstream(new SSLSwitchingEvent(true, ctx.getConnection()));
                    ctx.notifyDownstream(event);
                    return response;
                }
            };
            final GrizzlyResponseFuture future = new GrizzlyResponseFuture(grizzlyAsyncHttpProvider, request, handler, proxyServer);
            future.setDelegate(SafeFutureImpl.create());

            grizzlyAsyncHttpProvider.execute(ctx.getConnection(), request, handler, future, HttpTxContext.get(ctx));
            return ctx.getSuspendAction();
        }

        return ctx.getStopAction();
    }

    // ----------------------------------------------------- Private Methods

    private static void recycleRequestResponsePackets(final Connection c, final HttpResponsePacket response) {
        if (!Utils.isSpdyConnection(c)) {
            HttpRequestPacket request = response.getRequest();
            request.setExpectContent(false);
            response.recycle();
            request.recycle();
        }
    }

    private boolean sendAsGrizzlyRequest(
            final RequestInfoHolder requestInfoHolder,
            final FilterChainContext ctx) throws IOException {

        HttpTxContext httpTxContext = requestInfoHolder.getHttpTxContext();
        if (httpTxContext == null) {
            httpTxContext = HttpTxContext.create(requestInfoHolder);
        }

        if (checkProxyAuthFailure(ctx, httpTxContext)) {
            return true;
        }

        final Request request = httpTxContext.getRequest();
        final UriComponents uri = request.getURI();
        boolean secure = Utils.isSecure(uri);

        // If the request is secure, check to see if an error occurred during
        // the handshake. We have to do this here, as the error would occur
        // out of the scope of a HttpTxContext so there would be
        // no good way to communicate the problem to the caller.
        if (secure && checkHandshakeError(ctx, httpTxContext)) {
            return true;
        }

        if (isUpgradeRequest(httpTxContext.getHandler()) && isWSRequest(httpTxContext.getRequestUri())) {
            httpTxContext.setWSRequest(true);
            convertToUpgradeRequest(httpTxContext);
        }

        HttpRequestPacket requestPacket = requestCache.poll();
        if (requestPacket == null) {
            requestPacket = new HttpRequestPacketImpl();
        }
        
        final Method method = Method.valueOf(request.getMethod());
        
        requestPacket.setMethod(method);
        requestPacket.setProtocol(Protocol.HTTP_1_1);

        // Special handling for CONNECT.
        if (method == Method.CONNECT) {
            final int port = uri.getPort();
            requestPacket.setRequestURI(uri.getHost() + ':' + (port == -1 ? 443 : port));
        } else {
            requestPacket.setRequestURI(getNonEmptyPath(uri));
        }

        final BodyHandler bodyHandler = isPayloadAllowed(method) ?
                bodyHandlerFactory.getBodyHandler(request) :
                null;
        
        if (bodyHandler != null) {
            final long contentLength = request.getContentLength();
            if (contentLength >= 0) {
                requestPacket.setContentLengthLong(contentLength);
                requestPacket.setChunked(false);
            } else {
                requestPacket.setChunked(true);
            }
        }

        if (httpTxContext.isWSRequest()) {
            try {
                final URI wsURI = httpTxContext.getWsRequestURI().toURI();
                httpTxContext.setProtocolHandler(Version.RFC6455.createHandler(true));
                httpTxContext.setHandshake(httpTxContext.getProtocolHandler().createHandShake(wsURI));
                requestPacket = (HttpRequestPacket) httpTxContext.getHandshake().composeHeaders().getHttpHeader();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid WS URI: " + httpTxContext.getWsRequestURI());
            }
        }

        requestPacket.setSecure(secure);
        addQueryString(request, requestPacket);
        addHostHeader(request, uri, requestPacket);
        addGeneralHeaders(request, requestPacket);
        addCookies(request, requestPacket);
        addAuthorizationHeader(request, requestPacket);

        initTransferCompletionHandler(request, httpTxContext.getHandler());

        final HttpRequestPacket requestPacketLocal = requestPacket;
        FilterChainContext sendingCtx = ctx;

        if (secure) {
            // Check to see if the ProtocolNegotiator has given
            // us a different FilterChain to use. If so, we need
            // use a different FilterChainContext when invoking sendRequest().
            sendingCtx = checkAndHandleFilterChainUpdate(ctx, sendingCtx);
        }
        final Connection c = ctx.getConnection();
        final HttpContext httpCtx;
        if (!Utils.isSpdyConnection(c)) {
            httpCtx = HttpContext.newInstance(c, c, c, requestPacketLocal);
        } else {
            SpdySession session = SpdySession.get(c);
            final Lock lock = session.getNewClientStreamLock();
            try {
                lock.lock();
                SpdyStream stream = session.openStream(requestPacketLocal, session.getNextLocalStreamId(), 0, 0, 0, false,
                        !requestPacketLocal.isExpectContent());
                httpCtx = HttpContext.newInstance(stream, stream, stream, requestPacketLocal);
            } finally {
                lock.unlock();
            }
        }
        httpCtx.attach(ctx);
        HttpTxContext.set(ctx, httpTxContext);
        requestPacketLocal.getProcessingState().setHttpContext(httpCtx);
        requestPacketLocal.setConnection(c);
        
        return sendRequest(sendingCtx, request, requestPacketLocal,
                wrapWithExpectHandlerIfNeeded(bodyHandler, requestPacket));
    }

    @SuppressWarnings("unchecked")
    public boolean sendRequest(final FilterChainContext ctx,
            final Request request, final HttpRequestPacket requestPacket,
            final BodyHandler bodyHandler)
            throws IOException {

        boolean isWriteComplete = true;

        if (bodyHandler != null) {
            final HttpTxContext context = HttpTxContext.get(ctx);
            context.setBodyHandler(bodyHandler);
            if (logger.isDebugEnabled()) {
                logger.debug("REQUEST: {}", requestPacket);
            }
            isWriteComplete = bodyHandler.doHandle(ctx, request, requestPacket);
        } else {
            HttpContent content = HttpContent.builder(requestPacket).last(true).build();
            if (logger.isDebugEnabled()) {
                logger.debug("REQUEST: {}", requestPacket);
            }
            ctx.write(content, ctx.getTransportContext().getCompletionHandler());
        }

        return isWriteComplete;
    }

    private static FilterChainContext checkAndHandleFilterChainUpdate(final FilterChainContext ctx, final FilterChainContext sendingCtx) {
        FilterChainContext ctxLocal = sendingCtx;
        SSLConnectionContext sslCtx = SSLUtils.getSslConnectionContext(ctx.getConnection());
        if (sslCtx != null) {
            FilterChain fc = sslCtx.getNewConnectionFilterChain();

            if (fc != null) {
                // Create a new FilterChain context using the new
                // FilterChain.
                // TODO: We need to mark this connection somehow
                // as being only suitable for this type of
                // request.
                ctxLocal = obtainProtocolChainContext(ctx, fc);
            }
        }
        return ctxLocal;
    }

    /**
     * check if we need to wrap the BodyHandler with ExpectHandler
     */
    private static BodyHandler wrapWithExpectHandlerIfNeeded(
            final BodyHandler bodyHandler,
            final HttpRequestPacket requestPacket) {

        if (bodyHandler == null) {
            return null;
        }

        // check if we need to wrap the BodyHandler with ExpectHandler
        final MimeHeaders headers = requestPacket.getHeaders();
        final int expectHeaderIdx = headers.indexOf(Header.Expect, 0);

        return expectHeaderIdx != -1
                && headers.getValue(expectHeaderIdx).equalsIgnoreCase("100-Continue")
                ? new ExpectHandler(bodyHandler)
                : bodyHandler;
    }
        
    private static boolean isPayloadAllowed(final Method method) {
        return method.getPayloadExpectation() != Method.PayloadExpectation.NOT_ALLOWED;
    }
    
    private static void initTransferCompletionHandler(final Request request, final AsyncHandler h) throws IOException {
        if (h instanceof TransferCompletionHandler) {
            final FluentCaseInsensitiveStringsMap map = new FluentCaseInsensitiveStringsMap(request.getHeaders());
            TransferCompletionHandler.class.cast(h).headers(map);
        }
    }

    private static boolean checkHandshakeError(final FilterChainContext ctx, final HttpTxContext httpCtx) {
        Throwable t = getHandshakeError(ctx.getConnection());
        if (t != null) {
            httpCtx.abort(t);
            return true;
        }
        return false;
    }

    private static boolean checkProxyAuthFailure(final FilterChainContext ctx, final HttpTxContext httpCtx) {
        final Boolean failed = PROXY_AUTH_FAILURE.get(ctx.getConnection());
        if (failed != null && failed) {
            httpCtx.abort(new IllegalStateException("Unable to authenticate with proxy"));
            return true;
        }
        return false;
    }

    private static FilterChainContext obtainProtocolChainContext(final FilterChainContext ctx, final FilterChain completeProtocolFilterChain) {

        final FilterChainContext newFilterChainContext = completeProtocolFilterChain.obtainFilterChainContext(ctx.getConnection(),
                ctx.getStartIdx() + 1, completeProtocolFilterChain.size(), ctx.getFilterIdx() + 1);

        newFilterChainContext.setAddressHolder(ctx.getAddressHolder());
        newFilterChainContext.setMessage(ctx.getMessage());
        newFilterChainContext.getInternalContext().setIoEvent(ctx.getInternalContext().getIoEvent());
        ctx.getConnection().setProcessor(completeProtocolFilterChain);
        return newFilterChainContext;
    }

    private static void addHostHeader(final Request request, final UriComponents uri, final HttpRequestPacket requestPacket) {
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

    private void addAuthorizationHeader(final Request request, final HttpRequestPacket requestPacket) {
        Realm realm = request.getRealm();
        if (realm == null) {
            realm = config.getRealm();
        }
        if (realm != null && realm.getUsePreemptiveAuth()) {
            final String authHeaderValue = generateAuthHeader(realm);
            if (authHeaderValue != null) {
                requestPacket.addHeader(Header.Authorization, authHeaderValue);
            }
        }
    }

    private String generateAuthHeader(final Realm realm) {
        try {
            switch (realm.getAuthScheme()) {
            case BASIC:
                return computeBasicAuthentication(realm);
            case DIGEST:
                return computeDigestAuthentication(realm);
            case NTLM:
                return NTLM_ENGINE.generateType1Msg("NTLM " + realm.getNtlmDomain(), realm.getNtlmHost());
            default:
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isUpgradeRequest(final AsyncHandler handler) {
        return (handler instanceof UpgradeHandler);
    }

    private static boolean isWSRequest(final UriComponents requestUri) {
        return requestUri.getScheme().startsWith("ws");
    }

    private static void convertToUpgradeRequest(final HttpTxContext ctx) {
        
        final UriComponents requestUri = ctx.getRequestUri();

        ctx.setWsRequestURI(requestUri);
        ctx.setRequestUri(requestUri.withNewScheme(
                "ws".equals(requestUri.getScheme())
                        ? "http"
                        : "https"));
    }

    private void addGeneralHeaders(final Request request, final HttpRequestPacket requestPacket) {

        if (isNonEmpty(request.getHeaders())) {
            final FluentCaseInsensitiveStringsMap map = request.getHeaders();
            for (final Map.Entry<String, List<String>> entry : map.entrySet()) {
                final String headerName = entry.getKey();
                final List<String> headerValues = entry.getValue();
                if (isNonEmpty(headerValues)) {
                    for (int i = 0, len = headerValues.size(); i < len; i++) {
                        requestPacket.addHeader(headerName, headerValues.get(i));
                    }
                }
            }
        }

        final MimeHeaders headers = requestPacket.getHeaders();
        if (!headers.contains(Header.Connection)) {
            // final boolean canCache = context.provider.clientConfig.getAllowPoolingConnection();
            requestPacket.addHeader(Header.Connection, /* (canCache ? */"keep-alive" /* : "close") */);
        }

        if (!headers.contains(Header.Accept)) {
            requestPacket.addHeader(Header.Accept, "*/*");
        }

        if (!headers.contains(Header.UserAgent)) {
            requestPacket.addHeader(Header.UserAgent, config.getUserAgent());
        }
    }

    private void addCookies(final Request request, final HttpRequestPacket requestPacket) {

        final Collection<Cookie> cookies = request.getCookies();
        if (isNonEmpty(cookies)) {
            StringBuilder sb = new StringBuilder(128);
            org.glassfish.grizzly.http.Cookie[] gCookies = new org.glassfish.grizzly.http.Cookie[cookies.size()];
            convertCookies(cookies, gCookies);
            CookieSerializerUtils.serializeClientCookies(sb, false, true, gCookies);
            requestPacket.addHeader(Header.Cookie, sb.toString());
        }
    }

    private static void convertCookies(final Collection<Cookie> cookies, final org.glassfish.grizzly.http.Cookie[] gCookies) {
        int idx = 0;
        if (!cookies.isEmpty()) {
            for (final Cookie cookie : cookies) {
                final org.glassfish.grizzly.http.Cookie gCookie = new org.glassfish.grizzly.http.Cookie(cookie.getName(), cookie.getValue());
                gCookie.setDomain(cookie.getDomain());
                gCookie.setPath(cookie.getPath());
                gCookie.setMaxAge(cookie.getMaxAge());
                gCookie.setSecure(cookie.isSecure());
                gCookies[idx] = gCookie;
                idx++;
            }
        }
    }

    private static void addQueryString(final Request request, final HttpRequestPacket requestPacket) {

        String query = request.getURI().getQuery();
        if (isNonEmpty(query)) {
            requestPacket.setQueryString(query);
        }
    }

    class HttpRequestPacketImpl extends HttpRequestPacket {

        private ProcessingState processingState = new ProcessingState();

        // -------------------------------------- Methods from HttpRequestPacketImpl

        @Override
        public ProcessingState getProcessingState() {
            return processingState;
        }

        @Override
        public void recycle() {
            super.recycle();
            processingState.recycle();
            requestCache.add(this);
        }
    }
}
