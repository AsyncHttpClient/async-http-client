/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Cookie;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.FluentStringsMap;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.UpgradeHandler;
import org.asynchttpclient.listener.TransferCompletionHandler;
import org.asynchttpclient.listener.TransferCompletionHandler.TransferAdapter;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;
import org.asynchttpclient.providers.grizzly.GrizzlyResponseFuture;
import org.asynchttpclient.providers.grizzly.HttpTransactionContext;
import org.asynchttpclient.providers.grizzly.Utils;
import org.asynchttpclient.providers.grizzly.bodyhandler.ExpectHandler;
import org.asynchttpclient.providers.grizzly.filters.events.ContinueEvent;
import org.asynchttpclient.providers.grizzly.filters.events.SSLSwitchingEvent;
import org.asynchttpclient.providers.grizzly.filters.events.TunnelRequestEvent;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.ProcessingState;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.CookieSerializerUtils;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.ssl.SSLConnectionContext;
import org.glassfish.grizzly.ssl.SSLUtils;
import org.glassfish.grizzly.websockets.Version;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpResponsePacket;

import static org.asynchttpclient.providers.grizzly.filters.SwitchingSSLFilter.getHandshakeError;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getAuthority;
import static org.asynchttpclient.util.MiscUtil.isNonEmpty;

/**
 * This {@link org.glassfish.grizzly.filterchain.Filter} is typically the last
 * in the {@FilterChain}.  Its primary responsibility is converting the
 * async-http-client {@link Request} into a Grizzly {@link HttpRequestPacket}.
 *
 * @since 1.7
 * @author The Grizzly Team
 */
public final class AsyncHttpClientFilter extends BaseFilter {

    private ConcurrentLinkedQueue<HttpRequestPacketImpl> requestCache
                    = new ConcurrentLinkedQueue<HttpRequestPacketImpl>();

    private final AsyncHttpClientConfig config;
    private final GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider;

    private static final Attribute<Boolean> PROXY_AUTH_FAILURE =
           Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(AsyncHttpClientFilter.class.getName() + "-PROXY-AUTH_FAILURE");

    // -------------------------------------------------------- Constructors


    public AsyncHttpClientFilter(GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider, final AsyncHttpClientConfig config) {
        this.grizzlyAsyncHttpProvider = grizzlyAsyncHttpProvider;

        this.config = config;

    }


    // --------------------------------------------- Methods from BaseFilter


    @Override
    public NextAction handleRead(final FilterChainContext ctx)
    throws IOException {
        final HttpContent httpContent = ctx.getMessage();
        if (httpContent.isLast()) {
            // Perform the cleanup logic if it's the last chunk of the payload
            final HttpResponsePacket response =
                    (HttpResponsePacket) httpContent.getHttpHeader();
            
            recycleRequestResponsePackets(ctx.getConnection(), response);
            return ctx.getStopAction();
        }

        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleWrite(final FilterChainContext ctx)
    throws IOException {

        Object message = ctx.getMessage();
        if (message instanceof Request) {
            ctx.setMessage(null);
            if (!sendAsGrizzlyRequest((Request) message, ctx)) {
                return ctx.getSuspendAction();
            }
        } else if (message instanceof Buffer) {
            return ctx.getInvokeAction();
        }

        return ctx.getStopAction();
    }

    @SuppressWarnings("unchecked")
    @Override
    public NextAction handleEvent(final FilterChainContext ctx,
                                  final FilterChainEvent event)
    throws IOException {

        final Object type = event.type();
        if (type == ContinueEvent.class) {
            final ContinueEvent
                    continueEvent = (ContinueEvent) event;
            ((ExpectHandler) continueEvent.getContext().getBodyHandler()).finish(ctx);
        } else if (type == TunnelRequestEvent.class) {
            // Disable SSL for the time being...
            ctx.notifyDownstream(new SSLSwitchingEvent(false, ctx.getConnection()));
            ctx.suspend();
            TunnelRequestEvent tunnelRequestEvent = (TunnelRequestEvent) event;
            final ProxyServer proxyServer = tunnelRequestEvent.getProxyServer();
            final URI requestUri = tunnelRequestEvent.getUri();

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
            final GrizzlyResponseFuture future =
                    new GrizzlyResponseFuture(grizzlyAsyncHttpProvider,
                                              request,
                                              handler,
                                              proxyServer);
            future.setDelegate(SafeFutureImpl.create());

            grizzlyAsyncHttpProvider.execute(ctx.getConnection(),
                                             request,
                                             handler,
                                             future);
            return ctx.getSuspendAction();
        }

        return ctx.getStopAction();

    }


    // ----------------------------------------------------- Private Methods

    private static void recycleRequestResponsePackets(final Connection c,
                                                      final HttpResponsePacket response) {
        if (!Utils.isSpdyConnection(c)) {
            HttpRequestPacket request = response.getRequest();
            request.setExpectContent(false);
            response.recycle();
            request.recycle();
        }
    }
    
    private boolean sendAsGrizzlyRequest(final Request request,
                                         final FilterChainContext ctx)
    throws IOException {

        final HttpTransactionContext httpCtx = HttpTransactionContext.get(ctx.getConnection());

        if (checkProxyAuthFailure(ctx, httpCtx)) {
            return true;
        }

        final URI uri = httpCtx.getRequest().getURI();
        boolean secure = Utils.isSecure(uri);

        // If the request is secure, check to see if an error occurred during
        // the handshake.  We have to do this here, as the error would occur
        // out of the scope of a HttpTransactionContext so there would be
        // no good way to communicate the problem to the caller.
        if (secure && checkHandshakeError(ctx, httpCtx)) {
            return true;
        }

        if (isUpgradeRequest(httpCtx.getHandler()) && isWSRequest(httpCtx.getRequestUrl())) {
            httpCtx.setWSRequest(true);
            convertToUpgradeRequest(httpCtx);
        }

        HttpRequestPacket requestPacket = requestCache.poll();
        if (requestPacket == null) {
            requestPacket = new HttpRequestPacketImpl();
        }
        requestPacket.setMethod(request.getMethod());
        requestPacket.setProtocol(Protocol.HTTP_1_1);

        // Special handling for CONNECT.
        if (Method.CONNECT.matchesMethod(request.getMethod())) {
            final int port = uri.getPort();
            requestPacket.setRequestURI(uri.getHost() + ':' + (port == -1 ? 443 : port));
        } else {
            requestPacket.setRequestURI(uri.getPath());
        }

        if (GrizzlyAsyncHttpProvider.requestHasEntityBody(request)) {
            final long contentLength = request.getContentLength();
            if (contentLength > 0) {
                requestPacket.setContentLengthLong(contentLength);
                requestPacket.setChunked(false);
            } else {
                requestPacket.setChunked(true);
            }
        }

        if (httpCtx.isWSRequest() && !httpCtx.isEstablishingTunnel()) {
            try {
                final URI wsURI = new URI(httpCtx.getWsRequestURI());
                httpCtx.setProtocolHandler(Version.RFC6455.createHandler(true));
                httpCtx.setHandshake(
                        httpCtx.getProtocolHandler().createHandShake(wsURI));
                requestPacket = (HttpRequestPacket)
                        httpCtx.getHandshake().composeHeaders().getHttpHeader();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid WS URI: " + httpCtx.getWsRequestURI());
            }
        }

        requestPacket.setSecure(secure);
        addQueryString(request, requestPacket);
        addHostHeader(request, uri, requestPacket);
        addGeneralHeaders(request, requestPacket);
        addCookies(request, requestPacket);

        initTransferCompletionHandler(request, httpCtx.getHandler());

        final HttpRequestPacket requestPacketLocal = requestPacket;
        FilterChainContext sendingCtx = ctx;

        if (secure) {
            // Check to see if the ProtocolNegotiator has given
            // us a different FilterChain to use.  If so, we need
            // use a different FilterChainContext when invoking sendRequest().
            sendingCtx = checkAndHandleFilterChainUpdate(ctx, sendingCtx);
        }

        return grizzlyAsyncHttpProvider.sendRequest(sendingCtx,
                                                    request,
                                                    requestPacketLocal);

    }

    private static FilterChainContext checkAndHandleFilterChainUpdate(final FilterChainContext ctx,
                                                                      final FilterChainContext sendingCtx) {
        FilterChainContext ctxLocal = sendingCtx;
        SSLConnectionContext sslCtx =
                SSLUtils.getSslConnectionContext(ctx.getConnection());
        if (sslCtx != null) {
            FilterChain fc = sslCtx.getNewConnectionFilterChain();

            if (fc != null) {
                // Create a new FilterChain context using the new
                // FilterChain.
                // TODO:  We need to mark this connection somehow
                //        as being only suitable for this type of
                //        request.
                ctxLocal = obtainProtocolChainContext(ctx, fc);
            }
        }
        return ctxLocal;
    }

    private static void initTransferCompletionHandler(final Request request,
                                                      final AsyncHandler h)
    throws IOException {
        if (h instanceof TransferCompletionHandler) {
            final FluentCaseInsensitiveStringsMap map =
                    new FluentCaseInsensitiveStringsMap(request.getHeaders());
            TransferCompletionHandler.class.cast(h)
                    .transferAdapter(new TransferAdapter(map));
        }
    }

    private static boolean checkHandshakeError(final FilterChainContext ctx,
                                               final HttpTransactionContext httpCtx) {
            Throwable t = getHandshakeError(ctx.getConnection());
            if (t != null) {
                httpCtx.abort(t);
                return true;
            }
        return false;
    }

    private static boolean checkProxyAuthFailure(final FilterChainContext ctx,
                                                 final HttpTransactionContext httpCtx) {
        final Boolean failed = PROXY_AUTH_FAILURE.get(ctx.getConnection());
        if (failed != null && failed) {
            httpCtx.abort(new IllegalStateException("Unable to authenticate with proxy"));
            return true;
        }
        return false;
    }

    private static FilterChainContext obtainProtocolChainContext(
            final FilterChainContext ctx,
            final FilterChain completeProtocolFilterChain) {

        final FilterChainContext newFilterChainContext =
                completeProtocolFilterChain.obtainFilterChainContext(
                        ctx.getConnection(),
                        ctx.getStartIdx() + 1,
                        completeProtocolFilterChain.size(),
                        ctx.getFilterIdx() + 1);

        newFilterChainContext.setAddressHolder(ctx.getAddressHolder());
        newFilterChainContext.setMessage(ctx.getMessage());
        newFilterChainContext.getInternalContext().setIoEvent(
                ctx.getInternalContext().getIoEvent());
        ctx.getConnection().setProcessor(completeProtocolFilterChain);
        return newFilterChainContext;
    }

    private static void addHostHeader(final Request request,
                                      final URI uri,
                                      final HttpRequestPacket requestPacket) {
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

    private static boolean isUpgradeRequest(final AsyncHandler handler) {
        return (handler instanceof UpgradeHandler);
    }

    private static boolean isWSRequest(final String requestUri) {
        return (requestUri.charAt(0) == 'w' && requestUri.charAt(1) == 's');
    }

    private static void convertToUpgradeRequest(final HttpTransactionContext ctx) {
        final int colonIdx = ctx.getRequestUrl().indexOf(':');

        if (colonIdx < 2 || colonIdx > 3) {
            throw new IllegalArgumentException("Invalid websocket URL: " + ctx.getRequestUrl());
        }

        final StringBuilder sb = new StringBuilder(ctx.getRequestUrl());
        sb.replace(0, colonIdx, ((colonIdx == 2) ? "http" : "https"));
        ctx.setWsRequestURI(ctx.getRequestUrl());
        ctx.setRequestUrl(sb.toString());
    }

    private void addGeneralHeaders(final Request request,
                                   final HttpRequestPacket requestPacket) {

        if (request.hasHeaders()) {
            final FluentCaseInsensitiveStringsMap map = request.getHeaders();
            for (final Map.Entry<String, List<String>> entry : map.entrySet()) {
                final String headerName = entry.getKey();
                final List<String> headerValues = entry.getValue();
                if (isNonEmpty(headerValues)) {
                    for (int i = 0, len = headerValues.size(); i < len; i++) {
                        requestPacket.addHeader(headerName,
                                                headerValues.get(i));
                    }
                }
            }
        }

        final MimeHeaders headers = requestPacket.getHeaders();
        if (!headers.contains(Header.Connection)) {
            //final boolean canCache = context.provider.clientConfig.getAllowPoolingConnection();
            requestPacket.addHeader(Header.Connection, /*(canCache ? */"keep-alive" /*: "close")*/);
        }

        if (!headers.contains(Header.Accept)) {
            requestPacket.addHeader(Header.Accept, "*/*");
        }

        if (!headers.contains(Header.UserAgent)) {
            requestPacket.addHeader(Header.UserAgent, config.getUserAgent());
        }

    }


    private void addCookies(final Request request,
                                   final HttpRequestPacket requestPacket) {

        final Collection<Cookie> cookies = request.getCookies();
        if (isNonEmpty(cookies)) {
            StringBuilder sb = new StringBuilder(128);
            org.glassfish.grizzly.http.Cookie[] gCookies =
                    new org.glassfish.grizzly.http.Cookie[cookies.size()];
            convertCookies(cookies, gCookies);
            CookieSerializerUtils.serializeClientCookies(sb, false, config.isRfc6265CookieEncoding(), gCookies);
            requestPacket.addHeader(Header.Cookie, sb.toString());
        }

    }


    private static void convertCookies(final Collection<Cookie> cookies,
                                       final org.glassfish.grizzly.http.Cookie[] gCookies) {
        int idx = 0;
        if (!cookies.isEmpty()) {
            for (final Cookie cookie : cookies) {
                final org.glassfish.grizzly.http.Cookie gCookie =
                        new org.glassfish.grizzly.http.Cookie(cookie.getName(),
                                                              cookie.getValue());
                gCookie.setDomain(cookie.getDomain());
                gCookie.setPath(cookie.getPath());
                gCookie.setVersion(cookie.getVersion());
                gCookie.setMaxAge(cookie.getMaxAge());
                gCookie.setSecure(cookie.isSecure());
                gCookies[idx] = gCookie;
                idx++;
            }
        }

    }


    private static void addQueryString(final Request request,
                                       final HttpRequestPacket requestPacket) {

        final FluentStringsMap map = request.getQueryParams();
        if (isNonEmpty(map)) {
            StringBuilder sb = new StringBuilder(128);
            for (final Map.Entry<String, List<String>> entry : map.entrySet()) {
                final String name = entry.getKey();
                final List<String> values = entry.getValue();
                if (isNonEmpty(values)) {
                    try {
                        for (int i = 0, len = values.size(); i < len; i++) {
                            final String value = values.get(i);
                            if (isNonEmpty(value)) {
                                sb.append(URLEncoder.encode(name, "UTF-8")).append('=')
                                    .append(URLEncoder.encode(values.get(i),
                                                              "UTF-8")).append('&');
                            } else {
                                sb.append(URLEncoder.encode(name, "UTF-8")).append('&');
                            }
                        }
                    } catch (UnsupportedEncodingException ignored) {
                    }
                }
            }
            sb.setLength(sb.length() - 1);
            String queryString = sb.toString();

            requestPacket.setQueryString(queryString);
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
