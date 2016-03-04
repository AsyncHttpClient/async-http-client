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

import com.ning.http.client.providers.grizzly.events.GracefulCloseEvent;
import com.ning.http.client.providers.grizzly.websocket.GrizzlyWebSocketAdapter;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Realm.AuthScheme;
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
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.utils.Exceptions;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.websockets.WebSocketHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.ning.http.util.AsyncHttpProviderUtils.*;
import static com.ning.http.util.MiscUtils.isNonEmpty;
import org.glassfish.grizzly.EmptyCompletionHandler;
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
    
    private static IOException notKeepAliveReason;
    
    private final GrizzlyAsyncHttpProvider provider;
    
    // -------------------------------------------------------- Constructors

    AhcEventFilter(final GrizzlyAsyncHttpProvider provider,
            final int maxHerdersSizeProperty) {
        
        super(maxHerdersSizeProperty);
        this.provider = provider;
        HANDLER_MAP.put(HttpStatus.UNAUTHORIZED_401.getStatusCode(), AuthorizationHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407.getStatusCode(), ProxyAuthorizationHandler.INSTANCE);
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
            context.totalBodyWritten += written;
            final long total = context.totalBodyWritten;
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
                context.redirectCount++;
                if (redirectCountExceeded(context)) {
                    httpHeader.setSkipRemainder(true);
                    context.abort(new MaxRedirectException());
                }
            } else {
                context.redirectCount = 0;
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
                    final Connection c = m.openSync(newRequest);

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
        HttpTransactionContext.cleanupTransaction(httpContext,
                new EmptyCompletionHandler<HttpTransactionContext>() {
            @Override
            public void completed(HttpTransactionContext context) {
                if (!context.isReuseConnection()) {
                    final Connection c = (Connection) httpContext.getCloseable();
                    if (!httpContext.getRequest().getProcessingState().isStayAlive()) {
                        if (notKeepAliveReason == null) {
                            notKeepAliveReason
                                    = new IOException("HTTP keep-alive was disabled for this connection");
                        }
                        c.closeWithReason(notKeepAliveReason);
                    } else {
                        final ConnectionManager cm = context.provider.getConnectionManager();
                        cm.returnConnection(c);
                    }
                }
            }
        });
    }

    private static boolean redirectCountExceeded(final HttpTransactionContext context) {
        return context.redirectCount > context.maxRedirectCount;
    }

    private static boolean isRedirect(final int status) {
        return HttpStatus.MOVED_PERMANENTLY_301.statusMatches(status)
                || HttpStatus.FOUND_302.statusMatches(status)
                || HttpStatus.SEE_OTHER_303.statusMatches(status)
                || HttpStatus.TEMPORARY_REDIRECT_307.statusMatches(status)
                || HttpStatus.PERMANENT_REDIRECT_308.statusMatches(status);
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

            Realm realm = getRealm(httpTransactionContext);
            
            if (authHeaders.isEmpty() || realm == null) {
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
            
            final GrizzlyAsyncHttpProvider provider =
                    httpTransactionContext.provider;
            final Request req = httpTransactionContext.getAhcRequest();

            try {
                final boolean isContinueAuth;
                
                String ntlmAuthenticate = getNTLM(authHeaders);

                final Realm newRealm;
                if (ntlmAuthenticate != null) {
                    final Connection connection = ctx.getConnection();
                    // NTLM
                    // Connection-based auth
                    newRealm = ntlmChallenge(connection,
                            ntlmAuthenticate,
                            req, realm, false);
                    isContinueAuth = !Utils.isNtlmEstablished(connection);
                } else {
                    // Request-based auth
                    isContinueAuth = false;
                    
                    final String firstAuthHeader = authHeaders.get(0);

                    newRealm = new Realm.RealmBuilder()
                            .clone(realm)
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
                    c = m.openSync(req);
                }
                
                final Request nextRequest = new RequestBuilder(req)
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
                } catch (IOException ioe) {
                    newContext.abort(ioe);
                }
            } catch (Exception e) {
                httpTransactionContext.abort(e);
            }
            
            return false;
        }
    } // END AuthorizationHandler

    private static final class ProxyAuthorizationHandler implements StatusHandler {

        static final ProxyAuthorizationHandler INSTANCE = new ProxyAuthorizationHandler();
        // -------------------------------------- Methods from StatusHandler

        @Override
        public boolean handlesStatus(int statusCode) {
            return HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407.statusMatches(statusCode);
        }

        @SuppressWarnings(value = {"unchecked"})
        @Override
        public boolean handleStatus(final HttpResponsePacket responsePacket,
                final HttpTransactionContext httpTransactionContext,
                final FilterChainContext ctx) {
            final List<String> proxyAuthHeaders =
                    listOf(responsePacket.getHeaders()
                            .values(Header.ProxyAuthenticate));
            
            final ProxyServer proxyServer = httpTransactionContext.getProxyServer();
            
            if (proxyAuthHeaders.isEmpty() || proxyServer == null) {
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
            
            final GrizzlyAsyncHttpProvider provider =
                    httpTransactionContext.provider;
                        
            final Request req = httpTransactionContext.getAhcRequest();

            try {
                String ntlmAuthenticate = getNTLM(proxyAuthHeaders);

                final Realm newRealm;
                if (ntlmAuthenticate != null) {
                    // NTLM
                    // Connection-based auth
                    newRealm = ntlmProxyChallenge(ctx.getConnection(),
                            ntlmAuthenticate,
                            req, proxyServer);
                } else {
                    final String firstAuthHeader = proxyAuthHeaders.get(0);
                    
                     // BASIC or DIGEST
                    newRealm = proxyServer.realmBuilder()
                            .setUri(req.getUri())//
                            .setOmitQuery(true)//
                            .setMethodName(req.getMethod())//
                            .setUsePreemptiveAuth(true)//
                            .parseProxyAuthenticateHeader(firstAuthHeader)//
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
                    c = m.openSync(req);
                }
                
                final Request nextRequest = new RequestBuilder(req)
                        .setRealm(newRealm)
                        .build();
                
                final HttpTransactionContext newContext
                        = httpTransactionContext.cloneAndStartTransactionFor(
                                c, nextRequest);
                newContext.invocationStatus = InvocationStatus.STOP;
                
                try {
                    provider.execute(newContext);
                } catch (IOException ioe) {
                    newContext.abort(ioe);
                }
            } catch (Exception e) {
                httpTransactionContext.abort(e);
            }
            
            return false;
        }
    } // END ProxyAuthorizationHandler
    
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
            
            final Uri origUri = httpTransactionContext.lastRedirectUri == null
                    ? req.getUri()
                    : httpTransactionContext.lastRedirectUri;
            
            final Uri redirectUri = Uri.create(origUri, redirectURL);
            httpTransactionContext.lastRedirectUri = redirectUri;
            
            final Request nextRequest = newRequest(httpTransactionContext,
                    redirectUri, responsePacket,
                    getRealm(httpTransactionContext),
                    sendAsGet(responsePacket, httpTransactionContext));
            
            try {
                responsePacket.setSkipRemainder(true); // ignore the remainder of the response
                
                final Connection c;

                // @TODO we may want to ditch the keep-alive connection if the response payload is too large
                if (responsePacket.getProcessingState().isKeepAlive() &&
                        isSameHostAndProtocol(origUri, redirectUri)) {
                    // if it's HTTP keep-alive connection - reuse the
                    // same Grizzly Connection
                    c = ctx.getConnection();
                    httpTransactionContext.reuseConnection();
                } else {
                    // if it's not keep-alive - take new Connection from the pool
                    final ConnectionManager m = provider.getConnectionManager();
                    c = m.openSync(nextRequest);
                }
                
                final HttpTransactionContext newContext =
                        httpTransactionContext.cloneAndStartTransactionFor(
                                c, nextRequest);
                
                newContext.invocationStatus = InvocationStatus.CONTINUE;
                try {
                    provider.execute(newContext);
                } catch (IOException ioe) {
                    newContext.abort(ioe);
                }
                
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
    } // END RedirectHandler
        

    // ----------------------------------------------------- Private Methods
    private static Request newRequest(final HttpTransactionContext ctx,
            final Uri newUri, final HttpResponsePacket response,
            final Realm realm, boolean asGet) {
        final Request prototype = ctx.getAhcRequest();
        final FluentCaseInsensitiveStringsMap prototypeHeaders =
                prototype.getHeaders();
        
        prototypeHeaders.remove(Header.Host.toString());
        prototypeHeaders.remove(Header.ContentLength.toString());
        
        if (asGet)
            prototypeHeaders.remove(Header.ContentType.toString());
        if (realm != null && realm.getScheme() == AuthScheme.NTLM) {
            prototypeHeaders.remove(Header.Authorization.toString());
            prototypeHeaders.remove(Header.ProxyAuthorization.toString());
        }
        
        final RequestBuilder builder = new RequestBuilder(prototype);
        if (asGet) {
            builder.setMethod("GET");
        }
        builder.setUrl(newUri.toString());
        for (String cookieStr : response.getHeaders().values(Header.SetCookie)) {
            builder.addOrReplaceCookie(CookieDecoder.decode(cookieStr));
        }
                
        return builder.build();
    }
    
    private static Realm getRealm(final HttpTransactionContext httpTransactionContext) {
        final Realm realm = httpTransactionContext.getAhcRequest().getRealm();
        
        return realm != null
                ? realm
                : httpTransactionContext.provider.getClientConfig().getRealm();
    }
 
    private static Realm ntlmChallenge(final Connection c,
            final String wwwAuth,
            final Request request,
            final Realm realm,
            final boolean proxyInd)
            throws NTLMEngineException {

        final FluentCaseInsensitiveStringsMap headers = request.getHeaders();
        if (wwwAuth.equals("NTLM")) {
            // server replied bare NTLM => we didn't preemptively sent Type1Msg
            String challengeHeader = NTLMEngine.INSTANCE.generateType1Msg();

            addNTLMAuthorizationHeader(headers, challengeHeader, proxyInd);
        } else {
            // probably receiving Type2Msg, so we issue Type3Msg
            addType3NTLMAuthorizationHeader(wwwAuth, headers, realm, proxyInd);
            // we mark NTLM as established for the Connection to
            // avoid preemptive NTLM
            Utils.setNtlmEstablished(c);
        }
        
        return new Realm.RealmBuilder().clone(realm)//
                            .setUri(request.getUri())//
                            .setMethodName(request.getMethod())//
                            .build();
    }

    private static Realm ntlmProxyChallenge(final Connection c,
            final String wwwAuth, final Request request,
            final ProxyServer proxyServer)
            throws NTLMEngineException {
        
        final FluentCaseInsensitiveStringsMap headers = request.getHeaders();
        headers.remove(Header.ProxyAuthorization.toString());

        Realm realm = proxyServer.realmBuilder()//
                .setScheme(AuthScheme.NTLM)//
                .setUri(request.getUri())//
                .setMethodName(request.getMethod()).build();
        
        addType3NTLMAuthorizationHeader(wwwAuth, headers, realm, true);
        // we mark NTLM as established for the Connection to
        // avoid preemptive NTLM
        Utils.setNtlmEstablished(c);

        return realm;
    }
    
    private static void addNTLMAuthorizationHeader(
            FluentCaseInsensitiveStringsMap headers,
            String challengeHeader, boolean proxyInd) {
        headers.add(authorizationHeaderName(proxyInd), "NTLM " + challengeHeader);
    }

    private static void addType3NTLMAuthorizationHeader(String auth,
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

    private static String authorizationHeaderName(final boolean proxyInd) {
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
    
} // END AsyncHttpClientEventFilter
