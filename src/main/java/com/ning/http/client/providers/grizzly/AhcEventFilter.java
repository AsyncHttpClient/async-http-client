/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
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

import com.ning.http.client.providers.grizzly.events.SSLSwitchingEvent;
import com.ning.http.client.providers.grizzly.events.GracefulCloseEvent;
import com.ning.http.client.providers.grizzly.websocket.GrizzlyWebSocketAdapter;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.cookie.CookieDecoder;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.ntlm.NTLMEngineException;
import com.ning.http.client.providers.grizzly.events.ContinueEvent;
import com.ning.http.client.uri.Uri;
import com.ning.http.client.ws.WebSocketUpgradeHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.utils.Exceptions;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.websockets.WebSocketHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.ning.http.client.providers.netty.util.HttpUtils.getNTLM;
import static com.ning.http.util.MiscUtils.isNonEmpty;
/**
 * AHC {@link HttpClientFilter} implementation.
 * 
 * @author Grizzly Team
 */
final class AhcEventFilter extends HttpClientFilter {
    private final static Logger LOGGER =
            LoggerFactory.getLogger(AhcEventFilter.class);

    private static final Map<Integer, StatusHandler> HANDLER_MAP =
            new HashMap<Integer, StatusHandler>(8);
    
    private static IOException maximumPooledConnectionExceededReason;
    
    private final GrizzlyAsyncHttpProvider provider;
    

    /**
     * Close bytes.
     */
    private static final byte[] CLOSE_BYTES = {
        (byte) 'c',
        (byte) 'l',
        (byte) 'o',
        (byte) 's',
        (byte) 'e'
    };
    /**
     * Keep-alive bytes.
     */
    private static final byte[] KEEPALIVE_BYTES = {
        (byte) 'k',
        (byte) 'e',
        (byte) 'e',
        (byte) 'p',
        (byte) '-',
        (byte) 'a',
        (byte) 'l',
        (byte) 'i',
        (byte) 'v',
        (byte) 'e'
    };
    
    // -------------------------------------------------------- Constructors

    AhcEventFilter(final GrizzlyAsyncHttpProvider provider,
            final int maxHerdersSizeProperty) {
        
        super(maxHerdersSizeProperty);
        this.provider = provider;
        HANDLER_MAP.put(HttpStatus.UNAUTHORIZED_401.getStatusCode(), AuthorizationHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.MOVED_PERMANENTLY_301.getStatusCode(), RedirectHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.FOUND_302.getStatusCode(), RedirectHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.SEE_OTHER_303.getStatusCode(), RedirectHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.TEMPORARY_REDIRECT_307.getStatusCode(), RedirectHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.PERMANENT_REDIRECT_308.getStatusCode(), RedirectHandler.INSTANCE);
    }

    // --------------------------------------- Methods from HttpClientFilter

    @Override
    public NextAction handleEvent(final FilterChainContext ctx,
            final FilterChainEvent event) throws IOException {
        
        if (event.type() == GracefulCloseEvent.class) {
            // Connection was closed.
            // This event is fired only for responses, which don't have
            // associated transfer-encoding or content-length.
            // We have to complete such a request-response processing gracefully.
            final GracefulCloseEvent closeEvent =
                    (GracefulCloseEvent) event;
            final HttpResponsePacket response = closeEvent.getHttpTxContext().responsePacket;
            response.getProcessingState().getHttpContext().attach(ctx);
            onHttpPacketParsed(response, ctx);
            return ctx.getStopAction();
        }
        return ctx.getInvokeAction();
    }

    @Override
    public void exceptionOccurred(final FilterChainContext ctx,
            final Throwable error) {
        ctx.getCloseable().closeWithReason(Exceptions.makeIOException(error));
    }

    @Override
    protected void onHttpContentParsed(final HttpContent content,
            final FilterChainContext ctx) {
        
        final HttpTransactionContext context =
                HttpTransactionContext.currentTransaction(content.getHttpHeader());
        final AsyncHandler handler = context.getAsyncHandler();
        if (handler != null && context.currentState != AsyncHandler.STATE.ABORT) {
            try {
                context.currentState = handler.onBodyPartReceived(
                        new GrizzlyResponseBodyPart(content, ctx.getConnection()));
            } catch (Exception e) {
                handler.onThrowable(e);
            }
        }
    }

    @Override
    protected void onHttpHeadersEncoded(final HttpHeader httpHeader,
            final FilterChainContext ctx) {
        final HttpTransactionContext context =
                HttpTransactionContext.currentTransaction(httpHeader);
        final AsyncHandler handler = context.getAsyncHandler();
        if (handler instanceof TransferCompletionHandler) {
            ((TransferCompletionHandler) handler).onHeaderWriteCompleted();
        }
    }

    @Override
    protected void onHttpContentEncoded(final HttpContent content,
            final FilterChainContext ctx) {
        
        final HttpTransactionContext context =
                HttpTransactionContext.currentTransaction(content.getHttpHeader());
        
        final AsyncHandler handler = context.getAsyncHandler();
        if (handler instanceof TransferCompletionHandler) {
            final int written = content.getContent().remaining();
            final long total = context.totalBodyWritten.addAndGet(written);
            ((TransferCompletionHandler) handler).onContentWriteProgress(
                    written, total, content.getHttpHeader().getContentLength());
        }
    }

    @Override
    protected void onInitialLineParsed(final HttpHeader httpHeader,
            final FilterChainContext ctx) {
        
        super.onInitialLineParsed(httpHeader, ctx);
        if (httpHeader.isSkipRemainder()) {
            return;
        }
        final HttpResponsePacket responsePacket = (HttpResponsePacket) httpHeader;
        final HttpTransactionContext context =
                HttpTransactionContext.currentTransaction(httpHeader);
        final int status = responsePacket.getStatus();
        if (context.establishingTunnel && HttpStatus.OK_200.statusMatches(status)) {
            return;
        }
        if (HttpStatus.CONINTUE_100.statusMatches(status)) {
            ctx.notifyUpstream(new ContinueEvent(context));            
            return;
        }

        final StatusHandler sh = context.statusHandler;
        context.statusHandler = null;
        
        if (sh != null &&
                !sh.handlesStatus(status)) {
            context.invocationStatus = StatusHandler.InvocationStatus.CONTINUE;
        }
        
        final boolean isRedirectAllowed = isRedirectAllowed(context);
        
        if (context.invocationStatus == StatusHandler.InvocationStatus.CONTINUE) {
            if (HANDLER_MAP.containsKey(status)) {
                context.statusHandler = HANDLER_MAP.get(status);
            }
            if (context.statusHandler instanceof RedirectHandler
                    && !isRedirectAllowed) {
                context.statusHandler = null;
            }
        }
        
        if (isRedirectAllowed) {
            if (isRedirect(status)) {
                if (context.statusHandler == null) {
                    context.statusHandler = RedirectHandler.INSTANCE;
                }
                context.redirectCount.incrementAndGet();
                if (redirectCountExceeded(context)) {
                    httpHeader.setSkipRemainder(true);
                    context.abort(new MaxRedirectException());
                }
            } else {
                if (context.redirectCount.get() > 0) {
                    context.redirectCount.set(0);
                }
            }
        }
        final GrizzlyResponseStatus responseStatus =
                new GrizzlyResponseStatus(responsePacket,
                        context.getAhcRequest().getUri(),
                        provider.getClientConfig());
        
        context.responsePacket = responsePacket;
        context.responseStatus = responseStatus;
        if (context.statusHandler != null) {
            return;
        }
        if (context.currentState != AsyncHandler.STATE.ABORT) {
            try {
                final AsyncHandler handler = context.getAsyncHandler();
                if (handler != null) {
                    context.currentState = handler.onStatusReceived(responseStatus);
                    if (context.isWSRequest && context.currentState == AsyncHandler.STATE.ABORT) {
                        httpHeader.setSkipRemainder(true);
                        try {
                            context.done(handler.onCompleted());
                        } catch (Throwable e) {
                            context.abort(e);
                        }
                    }
                }
            } catch (Exception e) {
                httpHeader.setSkipRemainder(true);
                context.abort(e);
            }
        }
    }

    @Override
    protected void onHttpHeaderError(final HttpHeader httpHeader,
            final FilterChainContext ctx, final Throwable t)
            throws IOException {
        httpHeader.setSkipRemainder(true);
        HttpTransactionContext.currentTransaction(httpHeader).abort(t);
    }

    @Override
    protected void onHttpContentError(final HttpHeader httpHeader,
            final FilterChainContext ctx, final Throwable t)
            throws IOException {
        httpHeader.setSkipRemainder(true);
        HttpTransactionContext.currentTransaction(httpHeader).abort(t);
    }

    @SuppressWarnings(value = {"unchecked"})
    @Override
    protected boolean onHttpHeaderParsed(final HttpHeader httpHeader,
            final Buffer buffer, final FilterChainContext ctx) {
        super.onHttpHeaderParsed(httpHeader, buffer, ctx);
        LOGGER.debug("RESPONSE: {}", httpHeader);
        
        final HttpResponsePacket responsePacket =
                (HttpResponsePacket) httpHeader;
        
        // @TODO review this after Grizzly 2.3.20 is integrated
        final boolean isKeepAlive = checkKeepAlive(responsePacket);
        responsePacket.getProcessingState().setKeepAlive(isKeepAlive);
        
        if (httpHeader.isSkipRemainder()) {
            return false;
        }
        
        final HttpTransactionContext context =
                HttpTransactionContext.currentTransaction(httpHeader);
        if (context.establishingTunnel) {
            // finish request/response processing, because Grizzly itself
            // treats CONNECT traffic as part of request-response processing
            // and we don't want it be treated like that
            httpHeader.setExpectContent(false);
            return false;
        }
        
        final AsyncHandler handler = context.getAsyncHandler();
        final List<ResponseFilter> filters =
                provider.getClientConfig().getResponseFilters();
        final GrizzlyResponseHeaders responseHeaders =
                new GrizzlyResponseHeaders((HttpResponsePacket) httpHeader);
        if (!filters.isEmpty()) {
            FilterContext fc = new FilterContext.FilterContextBuilder()
                    .asyncHandler(handler)
                    .request(context.getAhcRequest())
                    .responseHeaders(responseHeaders)
                    .responseStatus(context.responseStatus)
                    .build();
            try {
                for (final ResponseFilter f : filters) {
                    fc = f.filter(fc);
                }
            } catch (Exception e) {
                context.abort(e);
            }
            if (fc.replayRequest()) {
                httpHeader.setSkipRemainder(true);
                
                final Request newRequest = fc.getRequest();
                final AsyncHandler newHandler = fc.getAsyncHandler();
                try {
                    final GrizzlyResponseFuture responseFuture = context.future;
                    final ConnectionManager m = context.provider.getConnectionManager();
                    final Connection c = m.openConnectionSync(newRequest, responseFuture);

                    final HttpTransactionContext newContext =
                            context.cloneAndStartTransactionFor(c, newRequest);
                    responseFuture.setAsyncHandler(newHandler);
                    responseFuture.setHttpTransactionCtx(newContext);
                    
                    try {
                        provider.execute(newContext);
                    } catch (IOException ioe) {
                        newContext.abort(ioe);
                    }
                } catch (Exception e) {
                    context.abort(e);
                }
                return false;
            }
        }
        if (context.statusHandler != null &&
                context.invocationStatus == StatusHandler.InvocationStatus.CONTINUE) {
            final boolean result =
                    context.statusHandler.handleStatus(
                            (HttpResponsePacket) httpHeader, context, ctx);
            if (!result) {
                httpHeader.setSkipRemainder(true);
                return false;
            }
        }
        if (context.isWSRequest) {
            try {
                context.protocolHandler.setConnection(ctx.getConnection());
                final GrizzlyWebSocketAdapter webSocketAdapter =
                        createWebSocketAdapter(context);
                context.webSocket = webSocketAdapter;
                final org.glassfish.grizzly.websockets.WebSocket ws =
                        webSocketAdapter.getGrizzlyWebSocket();
                
                if (context.currentState == AsyncHandler.STATE.UPGRADE) {
                    httpHeader.setChunked(false);
                    ws.onConnect();
                    WebSocketHolder.set(ctx.getConnection(), context.protocolHandler, ws);
                    ((WebSocketUpgradeHandler) context.getAsyncHandler()).onSuccess(context.webSocket);
                    final int wsTimeout = provider.getClientConfig().getWebSocketTimeout();
                    IdleTimeoutFilter.setCustomTimeout(ctx.getConnection(),
                            (wsTimeout <= 0) ? IdleTimeoutFilter.FOREVER : wsTimeout,
                            TimeUnit.MILLISECONDS);
                    context.done(handler.onCompleted());
                } else {
                    httpHeader.setSkipRemainder(true);
                    ((WebSocketUpgradeHandler) context.getAsyncHandler()).onClose(
                            context.webSocket, 1002,
                            "WebSocket protocol error: unexpected HTTP response status during handshake.");
                    context.done();
                }
            } catch (Throwable e) {
                httpHeader.setSkipRemainder(true);
                context.abort(e);
            }
        } else {
            if (context.currentState != AsyncHandler.STATE.ABORT) {
                try {
                    context.currentState = handler.onHeadersReceived(responseHeaders);
                } catch (Exception e) {
                    httpHeader.setSkipRemainder(true);
                    context.abort(e);
                }
            }
        }
        
        return false;
    }

    @SuppressWarnings(value = {"unchecked"})
    @Override
    protected boolean onHttpPacketParsed(final HttpHeader httpHeader,
            final FilterChainContext ctx) {
        final Connection connection = ctx.getConnection();
        
        final boolean result = super.onHttpPacketParsed(httpHeader, ctx);
        
        if (httpHeader.isSkipRemainder()) {
            cleanup(httpHeader.getProcessingState().getHttpContext());
            return result;
        }
        
        final HttpTransactionContext context =
                HttpTransactionContext.currentTransaction(httpHeader);
        if (context.establishingTunnel && HttpStatus.OK_200.statusMatches(
                ((HttpResponsePacket) httpHeader).getStatus())) {
            context.establishingTunnel = false;
            context.tunnelEstablished(connection);
            try {
                provider.execute(context);
                return result;
            } catch (IOException e) {
                context.abort(e);
                return result;
            }
        } else {
            cleanup(httpHeader.getProcessingState().getHttpContext());
            final AsyncHandler handler = context.getAsyncHandler();
            if (handler != null) {
                try {
                    context.done(handler.onCompleted());
                } catch (Throwable e) {
                    context.abort(e);
                }
            } else {
                context.done();
            }
            return result;
        }
    }

    // ----------------------------------------------------- Private Methods
    private static GrizzlyWebSocketAdapter createWebSocketAdapter(
            final HttpTransactionContext context) {
        
        return GrizzlyWebSocketAdapter.newInstance(
                context.provider.getClientConfig().getAsyncHttpProviderConfig(),
                context.protocolHandler);
    }

    private static boolean isRedirectAllowed(final HttpTransactionContext ctx) {
        final Request r = ctx.getAhcRequest();
        
        return r.getFollowRedirect() != null
                ? r.getFollowRedirect()
                : ctx.redirectsAllowed;
    }

    private static void cleanup(final HttpContext httpContext) {
        final HttpTransactionContext context =
                HttpTransactionContext.cleanupTransaction(httpContext);
        
        if (!context.isReuseConnection()) {
            final Connection c = (Connection) httpContext.getCloseable();
            final ConnectionManager cm = context.provider.getConnectionManager();
            if (!httpContext.getRequest().getProcessingState().isStayAlive() ||
                    !cm.canReturnConnection(c) || !cm.returnConnection(context.getAhcRequest(), c)) {
                //                context.abort());
                if (maximumPooledConnectionExceededReason == null) {
                    maximumPooledConnectionExceededReason
                            = new IOException("Maximum pooled connections exceeded");
                }
                c.closeWithReason(maximumPooledConnectionExceededReason);
            }
        }
    }

    private static boolean redirectCountExceeded(final HttpTransactionContext context) {
        return context.redirectCount.get() > context.maxRedirectCount;
    }

    private static boolean isRedirect(final int status) {
        return HttpStatus.MOVED_PERMANENTLY_301.statusMatches(status)
                || HttpStatus.FOUND_302.statusMatches(status)
                || HttpStatus.SEE_OTHER_303.statusMatches(status)
                || HttpStatus.TEMPORARY_REDIRECT_307.statusMatches(status)
                || HttpStatus.PERMANENT_REDIRECT_308.statusMatches(status);
    }

    private static boolean checkKeepAlive(final HttpResponsePacket response) {
        final int statusCode = response.getStatus();
        final boolean isExpectContent = response.isExpectContent();
        
        boolean keepAlive = !statusDropsConnection(statusCode) ||
                (!isExpectContent || !response.isChunked() || response.getContentLength() == -1); // double-check the transfer encoding here
        
        if (keepAlive) {
            // Check the Connection header
            final DataChunk cVal =
                    response.getHeaders().getValue(Header.Connection);
            
            if (response.getProtocol().compareTo(Protocol.HTTP_1_1) < 0) {
                // HTTP 1.0 response
                // "Connection: keep-alive" should be specified explicitly
                keepAlive = cVal != null && cVal.equalsIgnoreCase(KEEPALIVE_BYTES);
            } else {
                // HTTP 1.1+
                // keep-alive by default, if there's no "Connection: close"
                keepAlive = cVal == null || !cVal.equalsIgnoreCase(CLOSE_BYTES);
            }
        }
        
        return keepAlive;
    }
    
    /**
     * Determine if we must drop the connection because of the HTTP status
     * code. Use the same list of codes as Apache/httpd.
     */
    private static boolean statusDropsConnection(int status) {
        return status == 400 /* SC_BAD_REQUEST */ ||
               status == 408 /* SC_REQUEST_TIMEOUT */ ||
               status == 411 /* SC_LENGTH_REQUIRED */ ||
               status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */ ||
               status == 414 /* SC_REQUEST_URI_TOO_LARGE */ ||
               status == 417 /* FAILED EXPECTATION */ || 
               status == 500 /* SC_INTERNAL_SERVER_ERROR */ ||
               status == 503 /* SC_SERVICE_UNAVAILABLE */ ||
               status == 501 /* SC_NOT_IMPLEMENTED */ ||
               status == 505 /* SC_VERSION_NOT_SUPPORTED */;
    }
    
    // ------------------------------------------------------- Inner Classes
    private static final class AuthorizationHandler implements StatusHandler {

        static final AuthorizationHandler INSTANCE = new AuthorizationHandler();
        // -------------------------------------- Methods from StatusHandler

        @Override
        public boolean handlesStatus(int statusCode) {
            return HttpStatus.UNAUTHORIZED_401.statusMatches(statusCode);
        }

        @SuppressWarnings(value = {"unchecked"})
        @Override
        public boolean handleStatus(final HttpResponsePacket responsePacket,
                final HttpTransactionContext httpTransactionContext,
                final FilterChainContext ctx) {
            final List<String> authHeaders = listOf(responsePacket.getHeaders()
                    .values(Header.WWWAuthenticate));
            
            if (authHeaders.isEmpty()) {
                throw new IllegalStateException("401 response received, but no WWW-Authenticate header was present");
            }
            
            final GrizzlyAsyncHttpProvider provider =
                    httpTransactionContext.provider;
                        
            Realm realm = httpTransactionContext.getAhcRequest().getRealm();
            if (realm == null) {
                realm = provider.getClientConfig().getRealm();
            }
            if (realm == null) {
                httpTransactionContext.invocationStatus = InvocationStatus.STOP;
                final AsyncHandler ah = httpTransactionContext.getAsyncHandler();
                
                if (ah != null) {
                    try {
                        ah.onStatusReceived(
                                httpTransactionContext.responseStatus);
                    } catch (Exception e) {
                        httpTransactionContext.abort(e);
                    }
                }
                return true;
            }
            final Request req = httpTransactionContext.getAhcRequest();

            try {
                final boolean isContinueAuth;
                
                String ntlmAuthenticate = getNTLM(authHeaders);

                final Realm newRealm;
                if (ntlmAuthenticate != null) {
                    // NTLM
                    // Connection-based auth
                    isContinueAuth = true;
                    
                    newRealm = ntlmChallenge(ctx.getConnection(),
                            ntlmAuthenticate, req,
                            req.getHeaders(), realm);
                } else {
                    // Request-based auth
                    isContinueAuth = false;
                    
                    final String firstAuthHeader = authHeaders.get(0);

                    newRealm = new Realm.RealmBuilder()
                            .clone(realm)
                            .setScheme(realm.getScheme())
                            .setUri(req.getUri())
                            .setMethodName(req.getMethod())
                            .setUsePreemptiveAuth(true)
                            .parseWWWAuthenticateHeader(firstAuthHeader)
                            .build();
                }

                responsePacket.setSkipRemainder(true); // ignore the remainder of the response
                
                final Connection c;
                
                // @TODO we may want to ditch the keep-alive connection if the response payload is too large
                if (responsePacket.getProcessingState().isKeepAlive()) {
                    // if it's HTTP keep-alive connection - reuse the
                    // same Grizzly Connection
                    c = ctx.getConnection();
                    httpTransactionContext.reuseConnection();
                } else {
                    // if it's not keep-alive - take new Connection from the pool
                    final ConnectionManager m = provider.getConnectionManager();
                    c = m.openConnectionSync(req, httpTransactionContext.future);
                }
                
                final Request nextRequest = new RequestBuilder(req)
                        .setHeaders(req.getHeaders())
                        .setRealm(newRealm)
                        .build();
                
                final HttpTransactionContext newContext
                        = httpTransactionContext.cloneAndStartTransactionFor(
                                c, nextRequest);
                if (!isContinueAuth) {
                    newContext.invocationStatus = InvocationStatus.STOP;
                }
                
                try {
                    provider.execute(newContext);
                    return false;
                } catch (IOException ioe) {
                    newContext.abort(ioe);
                    return false;
                }
            } catch (Exception e) {
                httpTransactionContext.abort(e);
            }
            
            return false;
        }

        private Realm ntlmChallenge(final Connection c,
                final String wwwAuth, final Request request,
                final FluentCaseInsensitiveStringsMap headers,
                final Realm realm)
                throws NTLMEngineException {

            if (wwwAuth.equals("NTLM")) {
                // server replied bare NTLM => we didn't preemptively sent Type1Msg
                String challengeHeader = NTLMEngine.INSTANCE.generateType1Msg();

                addNTLMAuthorizationHeader(headers, challengeHeader, false);
            } else {
                // probably receiving Type2Msg, so we issue Type3Msg
                addType3NTLMAuthorizationHeader(wwwAuth, headers, realm, false);
                // we mark NTLM as established for the Connection to
                // avoid preemptive NTLM
                Utils.setNtlmEstablished(c);
            }

            return new Realm.RealmBuilder().clone(realm)//
                    .setUri(request.getUri())//
                    .setMethodName(request.getMethod())//
                    .build();
        }
    
        private void addNTLMAuthorizationHeader(
                FluentCaseInsensitiveStringsMap headers,
                String challengeHeader, boolean proxyInd) {
            headers.add(authorizationHeaderName(proxyInd), "NTLM " + challengeHeader);
        }

        private void addType3NTLMAuthorizationHeader(String auth,
                FluentCaseInsensitiveStringsMap headers, Realm realm,
                boolean proxyInd) throws NTLMEngineException {
            headers.remove(authorizationHeaderName(proxyInd));

            if (isNonEmpty(auth) && auth.startsWith("NTLM ")) {
                String serverChallenge = auth.substring("NTLM ".length()).trim();
                String challengeHeader = NTLMEngine.INSTANCE.generateType3Msg(
                        realm.getPrincipal(), realm.getPassword(),
                        realm.getNtlmDomain(), realm.getNtlmHost(), serverChallenge);
                addNTLMAuthorizationHeader(headers, challengeHeader, proxyInd);
            }
        }
        
        private String authorizationHeaderName(boolean proxyInd) {
            return proxyInd
                    ? Header.ProxyAuthorization.toString()
                    : Header.Authorization.toString();
        }
        
        private static List<String> listOf(final Iterable<String> values) {
            final List<String> list = new ArrayList<String>(2);
            for (String value : values) {
                list.add(value);
            }
            
            return list;
        }
    } // END AuthorizationHandler

    private static final class RedirectHandler implements StatusHandler {

        static final RedirectHandler INSTANCE = new RedirectHandler();

        // ------------------------------------------ Methods from StatusHandler
        @Override
        public boolean handlesStatus(int statusCode) {
            return isRedirect(statusCode);
        }

        @SuppressWarnings(value = {"unchecked"})
        @Override
        public boolean handleStatus(final HttpResponsePacket responsePacket,
                final HttpTransactionContext httpTransactionContext,
                final FilterChainContext ctx) {
            final String redirectURL = responsePacket.getHeader(Header.Location);
            if (redirectURL == null) {
                throw new IllegalStateException("redirect received, but no location header was present");
            }
            
            
            final Request req = httpTransactionContext.getAhcRequest();
            final GrizzlyAsyncHttpProvider provider = httpTransactionContext.provider;
            
            final Uri orig = httpTransactionContext.lastRedirectURI == null
                    ? req.getUri()
                    : Uri.create(req.getUri(),
                            httpTransactionContext.lastRedirectURI);
            
            httpTransactionContext.lastRedirectURI = redirectURL;
            Request requestToSend;
            Uri uri = Uri.create(orig, redirectURL);
            if (!uri.toUrl().equalsIgnoreCase(orig.toUrl())) {
                requestToSend = newRequest(uri, responsePacket,
                        httpTransactionContext,
                        sendAsGet(responsePacket, httpTransactionContext));
            } else {
                httpTransactionContext.statusHandler = null;
                httpTransactionContext.invocationStatus = InvocationStatus.CONTINUE;
                try {
                    httpTransactionContext.getAsyncHandler().onStatusReceived(
                            httpTransactionContext.responseStatus);
                } catch (Exception e) {
                    httpTransactionContext.abort(e);
                }
                return true;
            }
            final ConnectionManager m = provider.getConnectionManager();
            try {
                final Connection c = m.openConnectionSync(requestToSend, httpTransactionContext.future);
                if (switchingSchemes(orig, uri)) {
                    try {
                        notifySchemeSwitch(ctx, c, uri);
                    } catch (IOException ioe) {
                        httpTransactionContext.abort(ioe);
                    }
                }
                final HttpTransactionContext newContext =
                        httpTransactionContext.cloneAndStartTransactionFor(
                                c, requestToSend);
                
                newContext.invocationStatus = InvocationStatus.CONTINUE;
                provider.execute(newContext);
                return false;
            } catch (Exception e) {
                httpTransactionContext.abort(e);
            }
            httpTransactionContext.invocationStatus = InvocationStatus.CONTINUE;
            return true;
        }

        // ------------------------------------------------- Private Methods
        private boolean sendAsGet(final HttpResponsePacket response,
                final HttpTransactionContext ctx) {
            final int statusCode = response.getStatus();
            return !(statusCode < 302 || statusCode > 303) &&
                    !(statusCode == 302 && ctx.provider.getClientConfig().isStrict302Handling());
        }

        private boolean switchingSchemes(final Uri oldUri, final Uri newUri) {
            return !oldUri.getScheme().equals(newUri.getScheme());
        }

        private void notifySchemeSwitch(final FilterChainContext ctx,
                final Connection c, final Uri uri) throws IOException {
            ctx.notifyDownstream(new SSLSwitchingEvent("https".equals(uri.getScheme()), c));
        }
    } // END RedirectHandler

    // ----------------------------------------------------- Private Methods
    private static Request newRequest(final Uri uri,
            final HttpResponsePacket response,
            final HttpTransactionContext ctx, boolean asGet) {
        final RequestBuilder builder = new RequestBuilder(ctx.getAhcRequest());
        if (asGet) {
            builder.setMethod("GET");
        }
        builder.setUrl(uri.toString());
        for (String cookieStr : response.getHeaders().values(Header.Cookie)) {
            builder.addOrReplaceCookie(CookieDecoder.decode(cookieStr));
        }
        return builder.build();
    }
    
} // END AsyncHttpClientEventFilter
