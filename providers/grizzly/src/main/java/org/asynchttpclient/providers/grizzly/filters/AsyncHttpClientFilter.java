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
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;
import org.asynchttpclient.providers.grizzly.GrizzlyResponseFuture;
import org.asynchttpclient.providers.grizzly.HttpTransactionContext;
import org.asynchttpclient.providers.grizzly.Utils;
import org.asynchttpclient.providers.grizzly.bodyhandler.ExpectHandler;
import org.asynchttpclient.providers.grizzly.filters.events.ContinueEvent;
import org.asynchttpclient.providers.grizzly.filters.events.SSLSwitchingEvent;
import org.asynchttpclient.providers.grizzly.filters.events.TunnelRequestEvent;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
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
import java.util.concurrent.Callable;

import static org.asynchttpclient.util.AsyncHttpProviderUtils.getAuthority;
import static org.asynchttpclient.util.MiscUtil.isNonEmpty;

public final class AsyncHttpClientFilter extends BaseFilter {


    private final AsyncHttpClientConfig config;
    private GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider;


    // -------------------------------------------------------- Constructors


    public AsyncHttpClientFilter(GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider, final AsyncHttpClientConfig config) {
        this.grizzlyAsyncHttpProvider = grizzlyAsyncHttpProvider;

        this.config = config;

    }


    // --------------------------------------------- Methods from BaseFilter


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
            ctx.notifyDownstream(new SSLSwitchingEvent(false, ctx.getConnection(), null));
            ctx.suspend();
            final ProxyServer proxyServer = ((TunnelRequestEvent) event).getProxyServer();
            final URI requestUri = ((TunnelRequestEvent) event).getUri();
            RequestBuilder builder = new RequestBuilder();
            builder.setMethod(Method.CONNECT.getMethodString());
            builder.setUrl("http://" + getAuthority(requestUri));
            AsyncHandler handler = new AsyncCompletionHandler() {
                @Override
                public Object onCompleted(Response response) throws Exception {
                    ctx.notifyDownstream(new SSLSwitchingEvent(true, ctx.getConnection(), null));
                    ctx.notifyDownstream(event);
                    return response;
                }
            };
            Request request = builder.build();
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


    private boolean sendAsGrizzlyRequest(final Request request,
                                         final FilterChainContext ctx)
    throws IOException {

        final HttpTransactionContext httpCtx = HttpTransactionContext.get(ctx.getConnection());
        Throwable t = SwitchingSSLFilter.getHandshakeError(ctx.getConnection());
        if (t != null) {
            httpCtx.abort(t);
            return true;
        }
        if (isUpgradeRequest(httpCtx.getHandler()) && isWSRequest(httpCtx.getRequestUrl())) {
            httpCtx.setWSRequest(true);
            convertToUpgradeRequest(httpCtx);
        }
        final URI uri = httpCtx.getRequest().getURI();
        final HttpRequestPacket.Builder builder = HttpRequestPacket.builder();
        boolean secure = Utils.isSecure(uri);
        builder.method(request.getMethod());
        builder.protocol(Protocol.HTTP_1_1);
        addHostHeader(request, uri, builder);
        if (Method.CONNECT.matchesMethod(request.getMethod())) {
            builder.uri(uri.getHost() + ':' + (uri.getPort() == -1 ? 443 : uri.getPort()));
        } else {
            builder.uri(uri.getPath());
        }

        if (GrizzlyAsyncHttpProvider.requestHasEntityBody(request)) {
            final long contentLength = request.getContentLength();
            if (contentLength > 0) {
                builder.contentLength(contentLength);
                builder.chunked(false);
            } else {
                builder.chunked(true);
            }
        }

        HttpRequestPacket requestPacket;
        if (httpCtx.isWSRequest() && !httpCtx.isEstablishingTunnel()) {
            try {
                final URI wsURI = new URI(httpCtx.getWsRequestURI());
                httpCtx.setProtocolHandler(Version.DRAFT17.createHandler(true));
                httpCtx.setHandshake(
                        httpCtx.getProtocolHandler().createHandShake(wsURI));
                requestPacket = (HttpRequestPacket)
                        httpCtx.getHandshake().composeHeaders().getHttpHeader();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid WS URI: " + httpCtx.getWsRequestURI());
            }
        } else {
            requestPacket = builder.build();
        }
        requestPacket.setSecure(secure);
        addQueryString(request, requestPacket);
        addHeaders(request, requestPacket);
        addCookies(request, requestPacket);

        final AsyncHandler h = httpCtx.getHandler();
        if (h != null) {
            if (TransferCompletionHandler.class.isAssignableFrom(
                    h.getClass())) {
                final FluentCaseInsensitiveStringsMap map =
                        new FluentCaseInsensitiveStringsMap(
                                request.getHeaders());
                TransferCompletionHandler.class.cast(h)
                        .transferAdapter(new GrizzlyTransferAdapter(map));
            }
        }
        final HttpRequestPacket requestPacketLocal = requestPacket;
        final Callable<Boolean> action = new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                FilterChainContext sendingCtx = ctx;

                // Check to see if the ProtocolNegotiator has given
                // us a different FilterChain to use.
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
                        sendingCtx = obtainProtocolChainContext(ctx, fc);
                    }
                }
                return grizzlyAsyncHttpProvider.sendRequest(sendingCtx, request,
                                                            requestPacketLocal);
            }
        };

        try {
            return action.call();
        } catch (Exception e) {
            httpCtx.abort(e);
            return true;
        }

    }

    private FilterChainContext obtainProtocolChainContext(
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

    private void addHostHeader(final Request request,
                               final URI uri,
                               final HttpRequestPacket.Builder builder) {
        String host = request.getVirtualHost();
        if (host != null) {
            builder.header(Header.Host, host);
        } else {
            if (uri.getPort() == -1) {
                builder.header(Header.Host, uri.getHost());
            } else {
                builder.header(Header.Host, uri.getHost() + ':' + uri.getPort());
            }
        }
    }

    private boolean isUpgradeRequest(final AsyncHandler handler) {
        return (handler instanceof UpgradeHandler);
    }


    private boolean isWSRequest(final String requestUri) {
        return (requestUri.charAt(0) == 'w' && requestUri.charAt(1) == 's');
    }


    private void convertToUpgradeRequest(final HttpTransactionContext ctx) {
        final int colonIdx = ctx.getRequestUrl().indexOf(':');

        if (colonIdx < 2 || colonIdx > 3) {
            throw new IllegalArgumentException("Invalid websocket URL: " + ctx.getRequestUrl());
        }

        final StringBuilder sb = new StringBuilder(ctx.getRequestUrl());
        sb.replace(0, colonIdx, ((colonIdx == 2) ? "http" : "https"));
        ctx.setWsRequestURI(ctx.getRequestUrl());
        ctx.setRequestUrl(sb.toString());
    }

    private void addHeaders(final Request request,
                            final HttpRequestPacket requestPacket) throws IOException {

        final FluentCaseInsensitiveStringsMap map = request.getHeaders();
        if (isNonEmpty(map)) {
            for (final Map.Entry<String, List<String>> entry : map.entrySet()) {
                final String headerName = entry.getKey();
                final List<String> headerValues = entry.getValue();
                if (isNonEmpty(headerValues)) {
                    for (final String headerValue : headerValues) {
                        requestPacket.addHeader(headerName, headerValue);
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
            CookieSerializerUtils.serializeClientCookies(sb, gCookies);
            requestPacket.addHeader(Header.Cookie, sb.toString());
        }

    }


    private void convertCookies(final Collection<Cookie> cookies,
                                final org.glassfish.grizzly.http.Cookie[] gCookies) {
        int idx = 0;
        for (final Cookie cookie : cookies) {
            final org.glassfish.grizzly.http.Cookie gCookie =
                    new org.glassfish.grizzly.http.Cookie(cookie.getName(), cookie.getValue());
            gCookie.setDomain(cookie.getDomain());
            gCookie.setPath(cookie.getPath());
            gCookie.setVersion(cookie.getVersion());
            gCookie.setMaxAge(cookie.getMaxAge());
            gCookie.setSecure(cookie.isSecure());
            gCookies[idx] = gCookie;
            idx++;
        }

    }


    private void addQueryString(final Request request,
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

    public static final class GrizzlyTransferAdapter extends TransferCompletionHandler.TransferAdapter {


        // -------------------------------------------------------- Constructors


        public GrizzlyTransferAdapter(FluentCaseInsensitiveStringsMap headers) throws IOException {
            super(headers);
        }


        // ---------------------------------------- Methods from TransferAdapter


        @Override
        public void getBytes(byte[] bytes) {
            // TODO implement
        }

    } // END GrizzlyTransferAdapter
}
