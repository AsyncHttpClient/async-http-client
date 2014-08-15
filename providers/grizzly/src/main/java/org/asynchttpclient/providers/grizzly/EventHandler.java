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

package org.asynchttpclient.providers.grizzly;

import static org.asynchttpclient.AsyncHandler.STATE.ABORT;
import static org.asynchttpclient.AsyncHandler.STATE.UPGRADE;
import static org.asynchttpclient.providers.grizzly.statushandler.StatusHandler.InvocationStatus.CONTINUE;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.MaxRedirectException;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.providers.grizzly.filters.events.ContinueEvent;
import org.asynchttpclient.providers.grizzly.statushandler.AuthorizationHandler;
import org.asynchttpclient.providers.grizzly.statushandler.ProxyAuthorizationHandler;
import org.asynchttpclient.providers.grizzly.statushandler.RedirectHandler;
import org.asynchttpclient.providers.grizzly.statushandler.StatusHandler;
import org.asynchttpclient.providers.grizzly.websocket.GrizzlyWebSocketAdapter;
import org.asynchttpclient.uri.UriComponents;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.ProcessingState;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.websockets.SimpleWebSocket;
import org.glassfish.grizzly.websockets.WebSocketHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.HttpRequestPacket;

public final class EventHandler {

    private static final Map<Integer, StatusHandler> HANDLER_MAP = new HashMap<Integer, StatusHandler>();

    static {
        HANDLER_MAP.put(HttpStatus.UNAUTHORIZED_401.getStatusCode(), AuthorizationHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407.getStatusCode(), ProxyAuthorizationHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.MOVED_PERMANENTLY_301.getStatusCode(), RedirectHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.FOUND_302.getStatusCode(), RedirectHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.SEE_OTHER_303.getStatusCode(), RedirectHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.TEMPORARY_REDIRECT_307.getStatusCode(), RedirectHandler.INSTANCE);
        HANDLER_MAP.put(HttpStatus.PERMANENT_REDIRECT_308.getStatusCode(), RedirectHandler.INSTANCE);
    }

    private final AsyncHttpClientConfig config;
    GrizzlyAsyncHttpProvider.Cleanup cleanup;

    // -------------------------------------------------------- Constructors

    EventHandler(final AsyncHttpClientConfig config) {
        this.config = config;
    }

    // ----------------------------------------------------- Event Callbacks

    public void exceptionOccurred(FilterChainContext ctx, Throwable error) {

        HttpTxContext.get(ctx).abort(error);
    }

    public void onHttpContentParsed(HttpContent content, FilterChainContext ctx) {

        final HttpTxContext context = HttpTxContext.get(ctx);
        final AsyncHandler handler = context.getHandler();
        if (handler != null && context.getCurrentState() != ABORT) {
            try {
                context.setCurrentState(handler.onBodyPartReceived(new GrizzlyResponseBodyPart(content, ctx.getConnection())));
            } catch (Exception e) {
                handler.onThrowable(e);
            }
        }

    }

    @SuppressWarnings("UnusedParameters")
    public void onHttpHeadersEncoded(HttpHeader httpHeader, FilterChainContext ctx) {
        final HttpTxContext context = HttpTxContext.get(ctx);
        final AsyncHandler handler = context.getHandler();
        if (handler instanceof TransferCompletionHandler) {
            ((TransferCompletionHandler) handler).onHeaderWriteCompleted();
        }
    }

    public void onHttpContentEncoded(HttpContent content, FilterChainContext ctx) {
        final HttpTxContext context = HttpTxContext.get(ctx);
        final AsyncHandler handler = context.getHandler();
        if (handler instanceof TransferCompletionHandler) {
            final int written = content.getContent().remaining();
            final long total = context.getTotalBodyWritten().addAndGet(written);
            ((TransferCompletionHandler) handler).onContentWriteProgress(written, total, content.getHttpHeader().getContentLength());
        }
    }

    public void onInitialLineParsed(HttpHeader httpHeader, FilterChainContext ctx) {

        //super.onInitialLineParsed(httpHeader, ctx);
        if (httpHeader.isSkipRemainder()) {
            return;
        }
        final HttpTxContext context = HttpTxContext.get(ctx);
        final int status = ((HttpResponsePacket) httpHeader).getStatus();
        if (HttpStatus.CONINTUE_100.statusMatches(status)) {
            ctx.notifyUpstream(new ContinueEvent(context));
            return;
        }

        StatusHandler statusHandler = context.getStatusHandler();
        context.setStatusHandler(null);
        if (statusHandler != null && !statusHandler.handlesStatus(status)) {
            context.setStatusHandler(null);
            context.setInvocationStatus(CONTINUE);
        }

        if (context.getInvocationStatus() == CONTINUE) {
            if (HANDLER_MAP.containsKey(status)) {
                context.setStatusHandler(HANDLER_MAP.get(status));
            }
            if (context.getStatusHandler() instanceof RedirectHandler) {
                if (!isRedirectAllowed(context)) {
                    context.setStatusHandler(null);
                }
            }
        }
        if (isRedirectAllowed(context)) {
            if (isRedirect(status)) {
                if (context.getStatusHandler() == null) {
                    context.setStatusHandler(RedirectHandler.INSTANCE);
                }
                context.getRedirectCount().incrementAndGet();
                if (redirectCountExceeded(context)) {
                    httpHeader.setSkipRemainder(true);
                    context.abort(new MaxRedirectException());
                }
            } else {
                if (context.getRedirectCount().get() > 0) {
                    context.getRedirectCount().set(0);
                }
            }
        }
        final GrizzlyResponseStatus responseStatus =
                new GrizzlyResponseStatus((HttpResponsePacket) httpHeader,
                        context.getRequest().getURI(), config);
        context.setResponseStatus(responseStatus);
        if (context.getStatusHandler() != null) {
            return;
        }

        if (context.getCurrentState() != ABORT) {
            try {
                final AsyncHandler handler = context.getHandler();
                if (handler != null) {
                    context.setCurrentState(handler.onStatusReceived(responseStatus));
                    if (context.isWSRequest() && context.getCurrentState() == ABORT) {
                        httpHeader.setSkipRemainder(true);
                        try {
                            context.result(handler.onCompleted());
                            context.done();
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

    public void onHttpHeaderError(final HttpHeader httpHeader, final FilterChainContext ctx, final Throwable t) {

        httpHeader.setSkipRemainder(true);
        HttpTxContext.get(ctx).abort(t);
    }

    public void onHttpContentError(final HttpHeader httpHeader, final FilterChainContext ctx, final Throwable t) {

        httpHeader.setSkipRemainder(true);
        HttpTxContext.get(ctx).abort(t);
    }

    @SuppressWarnings({ "unchecked" })
    public void onHttpHeadersParsed(HttpHeader httpHeader, FilterChainContext ctx) {

        //super.onHttpHeadersParsed(httpHeader, ctx);
        GrizzlyAsyncHttpProvider.LOGGER.debug("RESPONSE: {}", httpHeader);
        processKeepAlive(ctx.getConnection(), httpHeader);
        final HttpTxContext context = HttpTxContext.get(ctx);

        if (httpHeader.isSkipRemainder()) {
            return;
        }

        final AsyncHandler handler = context.getHandler();
        final GrizzlyResponseHeaders responseHeaders = new GrizzlyResponseHeaders((HttpResponsePacket) httpHeader);
        if (context.getProvider().getClientConfig().hasResponseFilters()) {
            final List<ResponseFilter> filters = context.getProvider().getClientConfig().getResponseFilters();
            FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(context.getRequest())
                    .responseHeaders(responseHeaders).responseStatus(context.getResponseStatus()).build();
            try {
                for (int i = 0, len = filters.size(); i < len; i++) {
                    final ResponseFilter f = filters.get(i);
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
                    final ConnectionManager m = context.getProvider().getConnectionManager();
                    final Connection c = m.obtainConnection(newRequest, context.getFuture());
                    final HttpTxContext newContext = context.copy();
                    newContext.setRequest(newRequest);
                    context.setFuture(null);
                    context.getProvider().execute(c, newRequest, newHandler, context.getFuture(), newContext);
                } catch (Exception e) {
                    context.abort(e);
                }
                return;
            }
        }
        if (context.getStatusHandler() != null && context.getInvocationStatus() == CONTINUE) {
            final boolean result = context.getStatusHandler().handleStatus(((HttpResponsePacket) httpHeader), context, ctx);
            if (!result) {
                httpHeader.setSkipRemainder(true);
                return;
            }
        }
        if (context.isWSRequest()) {
            try {
                //in case of DIGEST auth protocol handler is null and just returning here is working
                if (context.getProtocolHandler() == null) {
                    return;
                    //context.protocolHandler = Version.DRAFT17.createHandler(true);
                    //context.currentState = AsyncHandler.STATE.UPGRADE;
                }

                context.getProtocolHandler().setConnection(ctx.getConnection());

                final GrizzlyWebSocketAdapter webSocketAdapter = createWebSocketAdapter(context);
                context.setWebSocket(webSocketAdapter);
                SimpleWebSocket ws = webSocketAdapter.getGrizzlyWebSocket();
                if (context.getCurrentState() == UPGRADE) {
                    httpHeader.setChunked(false);
                    ws.onConnect();
                    WebSocketHolder.set(ctx.getConnection(), context.getProtocolHandler(), ws);
                    ((WebSocketUpgradeHandler) context.getHandler()).onSuccess(context.getWebSocket());
                    final int wsTimeout = context.getProvider().getClientConfig().getWebSocketTimeout();
                    IdleTimeoutFilter.setCustomTimeout(ctx.getConnection(), ((wsTimeout <= 0) ? IdleTimeoutFilter.FOREVER : wsTimeout),
                            TimeUnit.MILLISECONDS);
                    context.result(handler.onCompleted());
                } else {
                    httpHeader.setSkipRemainder(true);
                    ((WebSocketUpgradeHandler) context.getHandler()).onClose(context.getWebSocket(), 1002,
                            "WebSocket protocol error: unexpected HTTP response status during handshake.");
                    context.result(null);
                }
            } catch (Throwable e) {
                httpHeader.setSkipRemainder(true);
                context.abort(e);
            }
        } else {
            if (context.getCurrentState() != ABORT) {
                try {
                    context.setCurrentState(handler.onHeadersReceived(responseHeaders));
                } catch (Exception e) {
                    httpHeader.setSkipRemainder(true);
                    context.abort(e);
                }
            }
        }

    }

    public boolean onHttpHeaderParsed(final HttpHeader httpHeader,
            final Buffer buffer, final FilterChainContext ctx) {
        final HttpRequestPacket request = ((HttpResponsePacket) httpHeader).getRequest();
        if (Method.CONNECT.equals(request.getMethod())) {
            // finish request/response processing, because Grizzly itself
            // treats CONNECT traffic as part of request-response processing
            // and we don't want it be treated like that
            httpHeader.setExpectContent(false);
        }

        return false;
    }

    @SuppressWarnings("rawtypes")
    public boolean onHttpPacketParsed(HttpHeader httpHeader, FilterChainContext ctx) {

        Utils.removeRequestInFlight(ctx.getConnection());

        if (cleanup != null) {
            cleanup.cleanup(ctx);
        }

        if (httpHeader.isSkipRemainder()) {
            if (Utils.getRequestInFlightCount(ctx.getConnection()) == 0) {
                cleanup(ctx);
            }
            return false;
        }

        final HttpTxContext context = HttpTxContext.get(ctx);
        cleanup(ctx);
        final AsyncHandler handler = context.getHandler();
        if (handler != null) {
            try {
                context.result(handler.onCompleted());
            } catch (Throwable e) {
                context.abort(e);
            }
        } else {
            context.done();
        }
        return false;
    }

    // ----------------------------------------------------- Private Methods

    @SuppressWarnings("rawtypes")
    private static void processKeepAlive(final Connection c, final HttpHeader header) {
        final ProcessingState state = header.getProcessingState();
        final String connectionHeader = header.getHeader(Header.Connection);
        if (connectionHeader == null) {
            state.setKeepAlive(header.getProtocol() == Protocol.HTTP_1_1);
        } else {
            if ("close".equals(connectionHeader.toLowerCase(Locale.ENGLISH))) {
                ConnectionManager.markConnectionAsDoNotCache(c);
                state.setKeepAlive(false);
            } else {
                state.setKeepAlive(true);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static GrizzlyWebSocketAdapter createWebSocketAdapter(final HttpTxContext context) {
        SimpleWebSocket ws = new SimpleWebSocket(context.getProtocolHandler());
        AsyncHttpProviderConfig config = context.getProvider().getClientConfig().getAsyncHttpProviderConfig();
        boolean bufferFragments = true;
        if (config instanceof GrizzlyAsyncHttpProviderConfig) {
            bufferFragments = (Boolean) ((GrizzlyAsyncHttpProviderConfig) config)
                    .getProperty(GrizzlyAsyncHttpProviderConfig.Property.BUFFER_WEBSOCKET_FRAGMENTS);
        }

        return new GrizzlyWebSocketAdapter(ws, bufferFragments);
    }

    private static boolean isRedirectAllowed(final HttpTxContext ctx) {
        return ctx.getRequest().getFollowRedirect() != null? ctx.getRequest().getFollowRedirect().booleanValue() : ctx.isRedirectsAllowed();
    }

    @SuppressWarnings("rawtypes")
    private static HttpTxContext cleanup(final FilterChainContext ctx) {

        final Connection c = ctx.getConnection();
        final HttpTxContext context = HttpTxContext.remove(ctx);
        if (!Utils.isSpdyConnection(c) && !Utils.isIgnored(c)) {
            final ConnectionManager manager = context.getProvider().getConnectionManager();
            //if (!manager.canReturnConnection(c)) {
            //    context.abort(
            //            new IOException("Maximum pooled connections exceeded"));
            //} else {
            if (!manager.returnConnection(c)) {
                ctx.getConnection().close();
            }
            //}
        }

        return context;

    }

    private static boolean redirectCountExceeded(final HttpTxContext context) {
        return (context.getRedirectCount().get() > context.getMaxRedirectCount());
    }

    public static boolean isRedirect(final int status) {

        return HttpStatus.MOVED_PERMANENTLY_301.statusMatches(status)//
                || HttpStatus.FOUND_302.statusMatches(status)//
                || HttpStatus.SEE_OTHER_303.statusMatches(status)//
                || HttpStatus.TEMPORARY_REDIRECT_307.statusMatches(status)
                || HttpStatus.PERMANENT_REDIRECT_308.statusMatches(status);
    }

    // ----------------------------------------------------- Private Methods

    public static Request newRequest(final UriComponents uri, final HttpResponsePacket response, final HttpTxContext ctx, boolean asGet) {

        final RequestBuilder builder = new RequestBuilder(ctx.getRequest());
        if (asGet) {
            builder.setMethod(Method.GET.getMethodString());
        }
        builder.setUrl(uri.toString());

        if (!ctx.getProvider().getClientConfig().isRemoveQueryParamOnRedirect())
            builder.addQueryParams(ctx.getRequest().getQueryParams());
        
        if (response.getHeader(Header.Cookie) != null) {
            for (String cookieStr : response.getHeaders().values(Header.Cookie)) {
                Cookie c = CookieDecoder.decode(cookieStr);
                if (c != null) {
                    builder.addOrReplaceCookie(c);
                }
            }
        }
        return builder.build();
    }

} // END AsyncHttpClientEventFilter
