package org.asynchttpclient.providers.netty4;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import static io.netty.handler.codec.http.HttpResponseStatus.SEE_OTHER;
import static io.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.asynchttpclient.providers.netty4.util.HttpUtil.HTTP;
import static org.asynchttpclient.providers.netty4.util.HttpUtil.WEBSOCKET;
import static org.asynchttpclient.providers.netty4.util.HttpUtil.isNTLM;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.STATE;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Cookie;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.MaxRedirectException;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.ntlm.NTLMEngineException;
import org.asynchttpclient.org.jboss.netty.handler.codec.http.CookieDecoder;
import org.asynchttpclient.providers.netty4.spnego.SpnegoEngine;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.websocket.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Sharable
public class NettyChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyChannelHandler.class);

    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;
    private final Channels channels;
    private final AtomicBoolean isClose;
    private final Protocol httpProtocol = new HttpProtocol();
    private final Protocol webSocketProtocol = new WebSocketProtocol();

    public NettyChannelHandler(AsyncHttpClientConfig config, NettyRequestSender requestSender, Channels channels, AtomicBoolean isClose) {
        this.config = config;
        this.requestSender = requestSender;
        this.channels = channels;
        this.isClose = isClose;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object e) throws Exception {

        Constants.IN_IO_THREAD.set(Boolean.TRUE);

        Object attribute = Channels.getDefaultAttribute(ctx);

        if (attribute instanceof Callback) {
            Callback ac = (Callback) attribute;
            if (e instanceof LastHttpContent || !(e instanceof HttpContent)) {
                ac.call();
                Channels.setDefaultAttribute(ctx, DiscardEvent.INSTANCE);
            }

        } else if (attribute instanceof NettyResponseFuture) {
            Protocol p = (ctx.pipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
            NettyResponseFuture<?> future = (NettyResponseFuture<?>) attribute;

            if (!(future.isIgnoreNextContents() && e instanceof HttpContent)) {
                p.handle(ctx, future, e);
            }

        } else if (attribute != DiscardEvent.INSTANCE) {
            try {
                LOGGER.trace("Closing an orphan channel {}", ctx.channel());
                ctx.channel().close();
            } catch (Throwable t) {
            }
        }
    }

    public void channelInactive(ChannelHandlerContext ctx) throws Exception {

        if (isClose.get()) {
            return;
        }

        try {
            super.channelInactive(ctx);
        } catch (Exception ex) {
            LOGGER.trace("super.channelClosed", ex);
        }

        channels.removeFromPool(ctx);
        Object attachment = Channels.getDefaultAttribute(ctx);
        LOGGER.debug("Channel Closed: {} with attachment {}", ctx.channel(), attachment);

        if (attachment instanceof Callback) {
            Callback callback = (Callback) attachment;
            Channels.setDefaultAttribute(ctx, callback.future());
            callback.call();

        } else if (attachment instanceof NettyResponseFuture<?>) {
            NettyResponseFuture future = (NettyResponseFuture) attachment;
            future.touch();

            if (!config.getIOExceptionFilters().isEmpty() && applyIoExceptionFiltersAndReplayRequest(ctx, future, new IOException("Channel Closed"))) {
                return;
            }

            Protocol p = (ctx.pipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
            p.onClose(ctx);

            if (future != null && !future.isDone() && !future.isCancelled()) {
                if (!requestSender.retry(ctx.channel(), future)) {
                    channels.abort(future, new IOException("Remotely Closed"));
                }
            } else {
                channels.closeChannel(ctx);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        Channel channel = ctx.channel();
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        NettyResponseFuture<?> future = null;

        if (cause instanceof PrematureChannelClosureException) {
            return;
        }

        LOGGER.debug("Unexpected I/O exception on channel {}", channel, cause);

        try {
            if (cause instanceof ClosedChannelException) {
                return;
            }

            Object attribute = Channels.getDefaultAttribute(ctx);
            if (attribute instanceof NettyResponseFuture<?>) {
                future = (NettyResponseFuture<?>) attribute;
                future.attachChannel(null, false);
                future.touch();

                if (cause instanceof IOException) {

                    // FIXME why drop the original exception and create a new
                    // one?
                    if (!config.getIOExceptionFilters().isEmpty()) {
                        if (applyIoExceptionFiltersAndReplayRequest(ctx, future, new IOException("Channel Closed"))) {
                            return;
                        }
                    } else {
                        // Close the channel so the recovering can occurs.
                        try {
                            ctx.channel().close();
                        } catch (Throwable t) {
                            // Swallow.
                        }
                        return;
                    }
                }

                if (NettyResponseFutures.abortOnReadCloseException(cause) || NettyResponseFutures.abortOnWriteCloseException(cause)) {
                    LOGGER.debug("Trying to recover from dead Channel: {}", channel);
                    return;
                }
            } else if (attribute instanceof Callback) {
                future = Callback.class.cast(attribute).future();
            }
        } catch (Throwable t) {
            cause = t;
        }

        if (future != null) {
            try {
                LOGGER.debug("Was unable to recover Future: {}", future);
                channels.abort(future, cause);
            } catch (Throwable t) {
                LOGGER.error(t.getMessage(), t);
            }
        }

        Protocol p = (ctx.pipeline().get(HttpClientCodec.class) != null ? httpProtocol : webSocketProtocol);
        p.onError(ctx, e);

        channels.closeChannel(ctx);
        // FIXME not really sure
        // ctx.fireChannelRead(e);
        ctx.close();
    }

    private boolean applyIoExceptionFiltersAndReplayRequest(ChannelHandlerContext ctx, NettyResponseFuture<?> future, IOException e) throws IOException {

        boolean replayed = false;

        FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(future.getAsyncHandler()).request(future.getRequest()).ioException(e).build();
        for (IOExceptionFilter asyncFilter : config.getIOExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            } catch (FilterException efe) {
                channels.abort(future, efe);
            }
        }

        if (fc.replayRequest()) {
            requestSender.replayRequest(future, fc, ctx);
            replayed = true;
        }
        return replayed;
    }

    private boolean redirect(Request request, NettyResponseFuture<?> future, HttpResponse response, final ChannelHandlerContext ctx) throws Exception {

        int statusCode = response.getStatus().code();
        boolean redirectEnabled = request.isRedirectOverrideSet() ? request.isRedirectEnabled() : config.isRedirectEnabled();
        if (redirectEnabled && (statusCode == MOVED_PERMANENTLY.code() || statusCode == FOUND.code() || statusCode == SEE_OTHER.code() || statusCode == TEMPORARY_REDIRECT.code())) {

            if (future.incrementAndGetCurrentRedirectCount() < config.getMaxRedirects()) {
                // We must allow 401 handling again.
                future.getAndSetAuth(false);

                String location = response.headers().get(HttpHeaders.Names.LOCATION);
                URI uri = AsyncHttpProviderUtils.getRedirectUri(future.getURI(), location);

                if (!uri.toString().equals(future.getURI().toString())) {
                    final RequestBuilder nBuilder = new RequestBuilder(future.getRequest());
                    if (config.isRemoveQueryParamOnRedirect()) {
                        nBuilder.setQueryParameters(null);
                    }

                    // FIXME what about 307?
                    if (!(statusCode < FOUND.code() || statusCode > SEE_OTHER.code()) && !(statusCode == FOUND.code() && config.isStrict302Handling())) {
                        nBuilder.setMethod(HttpMethod.GET.name());
                    }

                    // in case of a redirect from HTTP to HTTPS, those values
                    // might be different
                    final boolean initialConnectionKeepAlive = future.isKeepAlive();
                    final String initialPoolKey = channels.getPoolKey(future);

                    future.setURI(uri);
                    String newUrl = uri.toString();
                    if (request.getUrl().startsWith(WEBSOCKET)) {
                        newUrl = newUrl.replace(HTTP, WEBSOCKET);
                    }

                    LOGGER.debug("Redirecting to {}", newUrl);
                    for (String cookieStr : future.getHttpResponse().headers().getAll(HttpHeaders.Names.SET_COOKIE)) {
                        for (Cookie c : CookieDecoder.decode(cookieStr)) {
                            nBuilder.addOrReplaceCookie(c);
                        }
                    }

                    for (String cookieStr : future.getHttpResponse().headers().getAll(HttpHeaders.Names.SET_COOKIE2)) {
                        for (Cookie c : CookieDecoder.decode(cookieStr)) {
                            nBuilder.addOrReplaceCookie(c);
                        }
                    }

                    Callback callback = new Callback(future) {
                        public void call() throws Exception {
                            if (!(initialConnectionKeepAlive && ctx.channel().isActive() && channels.offerToPool(initialPoolKey, ctx.channel()))) {
                                channels.finishChannel(ctx);
                            }
                        }
                    };

                    if (HttpHeaders.isTransferEncodingChunked(response)) {
                        // We must make sure there is no bytes left before
                        // executing the next request.
                        Channels.setDefaultAttribute(ctx, callback);
                    } else {
                        callback.call();
                    }

                    Request target = nBuilder.setUrl(newUrl).build();
                    future.setRequest(target);
                    requestSender.execute(target, future);
                    return true;
                }
            } else {
                throw new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects());
            }
        }
        return false;
    }

    private final class HttpProtocol implements Protocol {

        private Realm kerberosChallenge(List<String> proxyAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm,
                NettyResponseFuture<?> future) throws NTLMEngineException {

            URI uri = request.getURI();
            String host = request.getVirtualHost() == null ? AsyncHttpProviderUtils.getHost(uri) : request.getVirtualHost();
            String server = proxyServer == null ? host : proxyServer.getHost();
            try {
                String challengeHeader = SpnegoEngine.instance().generateToken(server);
                headers.remove(HttpHeaders.Names.AUTHORIZATION);
                headers.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);

                Realm.RealmBuilder realmBuilder;
                if (realm != null) {
                    realmBuilder = new Realm.RealmBuilder().clone(realm);
                } else {
                    realmBuilder = new Realm.RealmBuilder();
                }
                return realmBuilder.setUri(uri.getRawPath()).setMethodName(request.getMethod()).setScheme(Realm.AuthScheme.KERBEROS).build();
            } catch (Throwable throwable) {
                if (isNTLM(proxyAuth)) {
                    return ntlmChallenge(proxyAuth, request, proxyServer, headers, realm, future);
                }
                channels.abort(future, throwable);
                return null;
            }
        }

        private Realm ntlmChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm,
                NettyResponseFuture<?> future) throws NTLMEngineException {

            boolean useRealm = (proxyServer == null && realm != null);

            String ntlmDomain = useRealm ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
            String ntlmHost = useRealm ? realm.getNtlmHost() : proxyServer.getHost();
            String principal = useRealm ? realm.getPrincipal() : proxyServer.getPrincipal();
            String password = useRealm ? realm.getPassword() : proxyServer.getPassword();

            Realm newRealm;
            if (realm != null && !realm.isNtlmMessageType2Received()) {
                String challengeHeader = NTLMEngine.INSTANCE.generateType1Msg(ntlmDomain, ntlmHost);

                URI uri = request.getURI();
                headers.add(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);
                newRealm = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme()).setUri(uri.getRawPath()).setMethodName(request.getMethod())
                        .setNtlmMessageType2Received(true).build();
                future.getAndSetAuth(false);
            } else {
                addType3NTLMAuthorizationHeader(wwwAuth, headers, principal, password, ntlmDomain, ntlmHost);

                Realm.RealmBuilder realmBuilder;
                Realm.AuthScheme authScheme;
                if (realm != null) {
                    realmBuilder = new Realm.RealmBuilder().clone(realm);
                    authScheme = realm.getAuthScheme();
                } else {
                    realmBuilder = new Realm.RealmBuilder();
                    authScheme = Realm.AuthScheme.NTLM;
                }
                newRealm = realmBuilder.setScheme(authScheme).setUri(request.getURI().getPath()).setMethodName(request.getMethod()).build();
            }

            return newRealm;
        }

        private Realm ntlmProxyChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers, Realm realm,
                NettyResponseFuture<?> future) throws NTLMEngineException {
            future.getAndSetAuth(false);
            headers.remove(HttpHeaders.Names.PROXY_AUTHORIZATION);

            addType3NTLMAuthorizationHeader(wwwAuth, headers, proxyServer.getPrincipal(), proxyServer.getPassword(), proxyServer.getNtlmDomain(), proxyServer.getHost());

            Realm newRealm;
            Realm.RealmBuilder realmBuilder;
            if (realm != null) {
                realmBuilder = new Realm.RealmBuilder().clone(realm);
            } else {
                realmBuilder = new Realm.RealmBuilder();
            }
            newRealm = realmBuilder// .setScheme(realm.getAuthScheme())
                    .setUri(request.getURI().getPath()).setMethodName(request.getMethod()).build();

            return newRealm;
        }

        private void addType3NTLMAuthorizationHeader(List<String> auth, FluentCaseInsensitiveStringsMap headers, String username, String password, String domain, String workstation)
                throws NTLMEngineException {
            headers.remove(HttpHeaders.Names.AUTHORIZATION);

            if (isNTLM(auth)) {
                String serverChallenge = auth.get(0).trim().substring("NTLM ".length());
                String challengeHeader = NTLMEngine.INSTANCE.generateType3Msg(username, password, domain, workstation, serverChallenge);

                headers.add(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);
            }
        }

        private List<String> getAuthorizationToken(Iterable<Entry<String, String>> list, String headerAuth) {
            ArrayList<String> l = new ArrayList<String>();
            for (Entry<String, String> e : list) {
                if (e.getKey().equalsIgnoreCase(headerAuth)) {
                    l.add(e.getValue().trim());
                }
            }
            return l;
        }

        private void finishUpdate(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx, boolean lastValidChunk) throws IOException {
            if (lastValidChunk && future.isKeepAlive()) {
                channels.drainChannel(ctx, future);
            } else {
                if (future.isKeepAlive() && ctx.channel().isActive() && channels.offerToPool(channels.getPoolKey(future), ctx.channel())) {
                    markAsDone(future, ctx);
                    return;
                }
                channels.finishChannel(ctx);
            }
            markAsDone(future, ctx);
        }

        private final boolean updateBodyAndInterrupt(final NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponseBodyPart c) throws Exception {
            boolean state = handler.onBodyPartReceived(c) != STATE.CONTINUE;
            if (c.closeUnderlyingConnection()) {
                future.setKeepAlive(false);
            }
            return state;
        }

        private void markAsDone(final NettyResponseFuture<?> future, final ChannelHandlerContext ctx) throws MalformedURLException {
            // We need to make sure everything is OK before adding the
            // connection back to the pool.
            try {
                future.done();
            } catch (Throwable t) {
                // Never propagate exception once we know we are done.
                LOGGER.debug(t.getMessage(), t);
            }

            if (!future.isKeepAlive() || !ctx.channel().isActive()) {
                channels.closeChannel(ctx);
            }
        }

        private boolean applyResponseFiltersAndReplayRequest(ChannelHandlerContext ctx, NettyResponseFuture future, HttpResponseStatus status, HttpResponseHeaders responseHeaders)
                throws IOException {

            boolean replayed = false;

            AsyncHandler handler = future.getAsyncHandler();
            FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(future.getRequest()).responseStatus(status).responseHeaders(responseHeaders)
                    .build();

            for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                try {
                    fc = asyncFilter.filter(fc);
                    // FIXME Is it work protecting against this?
                    if (fc == null) {
                        throw new NullPointerException("FilterContext is null");
                    }
                } catch (FilterException efe) {
                    channels.abort(future, efe);
                }
            }

            // The handler may have been wrapped.
            handler = fc.getAsyncHandler();
            future.setAsyncHandler(handler);

            // The request has changed
            if (fc.replayRequest()) {
                requestSender.replayRequest(future, fc, ctx);
                future.setIgnoreNextContents(true);
                replayed = true;
            }
            return replayed;
        }

        @Override
        public void handle(final ChannelHandlerContext ctx, final NettyResponseFuture future, final Object e) throws Exception {
            future.touch();

            // The connect timeout occurred.
            if (future.isCancelled() || future.isDone()) {
                channels.finishChannel(ctx);
                return;
            }

            HttpRequest nettyRequest = future.getNettyRequest();
            AsyncHandler handler = future.getAsyncHandler();
            Request request = future.getRequest();
            ProxyServer proxyServer = future.getProxyServer();
            try {
                if (e instanceof HttpResponse) {
                    HttpResponse response = (HttpResponse) e;

                    LOGGER.debug("\n\nRequest {}\n\nResponse {}\n", nettyRequest, response);

                    int statusCode = response.getStatus().code();
                    HttpResponseStatus status = new ResponseStatus(future.getURI(), response);
                    HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response.headers());
                    final FluentCaseInsensitiveStringsMap headers = request.getHeaders();
                    final RequestBuilder builder = new RequestBuilder(future.getRequest());
                    Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

                    // store the original headers so we can re-send all them to
                    // the handler in case of trailing headers
                    future.setHttpResponse(response);
                    future.setIgnoreNextContents(false);

                    future.setKeepAlive(!HttpHeaders.Values.CLOSE.equalsIgnoreCase(response.headers().get(HttpHeaders.Names.CONNECTION)));

                    if (!config.getResponseFilters().isEmpty() && applyResponseFiltersAndReplayRequest(ctx, future, status, responseHeaders)) {
                        return;
                    }

                    // FIXME handle without returns
                    if (statusCode == UNAUTHORIZED.code() && realm != null) {
                        List<String> wwwAuth = getAuthorizationToken(response.headers(), HttpHeaders.Names.WWW_AUTHENTICATE);
                        if (!wwwAuth.isEmpty() && !future.getAndSetAuth(true)) {
                            future.setState(NettyResponseFuture.STATE.NEW);
                            Realm newRealm = null;
                            // NTLM
                            boolean negociate = wwwAuth.contains("Negotiate");
                            if (!wwwAuth.contains("Kerberos") && (isNTLM(wwwAuth) || negociate)) {
                                newRealm = ntlmChallenge(wwwAuth, request, proxyServer, headers, realm, future);
                                // SPNEGO KERBEROS
                            } else if (negociate) {
                                newRealm = kerberosChallenge(wwwAuth, request, proxyServer, headers, realm, future);
                                if (newRealm == null) {
                                    future.setIgnoreNextContents(true);
                                    return;
                                }
                            } else {
                                newRealm = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme()).setUri(request.getURI().getPath())
                                        .setMethodName(request.getMethod()).setUsePreemptiveAuth(true).parseWWWAuthenticateHeader(wwwAuth.get(0)).build();
                            }

                            final Realm nr = new Realm.RealmBuilder().clone(newRealm).setUri(URI.create(request.getUrl()).getPath()).build();

                            LOGGER.debug("Sending authentication to {}", request.getUrl());
                            Callback callback = new Callback(future) {
                                public void call() throws Exception {
                                    channels.drainChannel(ctx, future);
                                    requestSender.execute(builder.setHeaders(headers).setRealm(nr).build(), future);
                                }
                            };

                            if (future.isKeepAlive() && HttpHeaders.isTransferEncodingChunked(response)) {
                                // We must make sure there is no bytes left
                                // before executing the next request.
                                Channels.setDefaultAttribute(ctx, callback);
                            } else {
                                callback.call();
                            }

                            future.setIgnoreNextContents(true);
                            return;
                        }

                    } else if (statusCode == CONTINUE.code()) {
                        future.getAndSetWriteHeaders(false);
                        future.getAndSetWriteBody(true);
                        future.setIgnoreNextContents(true);
                        requestSender.writeRequest(ctx.channel(), config, future);
                        return;

                    } else if (statusCode == PROXY_AUTHENTICATION_REQUIRED.code()) {
                        List<String> proxyAuth = getAuthorizationToken(response.headers(), HttpHeaders.Names.PROXY_AUTHENTICATE);
                        if (realm != null && !proxyAuth.isEmpty() && !future.getAndSetAuth(true)) {
                            LOGGER.debug("Sending proxy authentication to {}", request.getUrl());

                            future.setState(NettyResponseFuture.STATE.NEW);
                            Realm newRealm = null;

                            boolean negociate = proxyAuth.contains("Negotiate");
                            if (!proxyAuth.contains("Kerberos") && (isNTLM(proxyAuth) || negociate)) {
                                newRealm = ntlmProxyChallenge(proxyAuth, request, proxyServer, headers, realm, future);
                                // SPNEGO KERBEROS
                            } else if (negociate) {
                                newRealm = kerberosChallenge(proxyAuth, request, proxyServer, headers, realm, future);
                                if (newRealm == null) {
                                    future.setIgnoreNextContents(true);
                                    return;
                                }
                            } else {
                                newRealm = future.getRequest().getRealm();
                            }

                            future.setReuseChannel(true);
                            future.setConnectAllowed(true);
                            future.setIgnoreNextContents(true);
                            requestSender.execute(builder.setHeaders(headers).setRealm(newRealm).build(), future);
                            return;
                        }

                    } else if (statusCode == OK.code() && nettyRequest.getMethod().equals(HttpMethod.CONNECT)) {

                        LOGGER.debug("Connected to {}:{}", proxyServer.getHost(), proxyServer.getPort());

                        if (future.isKeepAlive()) {
                            future.attachChannel(ctx.channel(), true);
                        }

                        try {
                            LOGGER.debug("Connecting to proxy {} for scheme {}", proxyServer, request.getUrl());
                            channels.upgradeProtocol(ctx.channel().pipeline(), request.getURI().getScheme());
                        } catch (Throwable ex) {
                            channels.abort(future, ex);
                        }
                        future.setReuseChannel(true);
                        future.setConnectAllowed(false);
                        future.setIgnoreNextContents(true);
                        requestSender.execute(builder.build(), future);
                        return;

                    }

                    if (redirect(request, future, response, ctx)) {
                        future.setIgnoreNextContents(true);
                        return;

                    }

                    if (!future.getAndSetStatusReceived(true)
                            && (handler.onStatusReceived(status) != STATE.CONTINUE || handler.onHeadersReceived(responseHeaders) != STATE.CONTINUE)) {
                        finishUpdate(future, ctx, HttpHeaders.isTransferEncodingChunked(response));
                        return;
                    }
                }

                if (handler != null && e instanceof HttpContent) {
                    HttpContent chunk = (HttpContent) e;

                    boolean interrupt = false;
                    boolean last = chunk instanceof LastHttpContent;

                    // FIXME
                    // Netty 3 provider is broken: in case of trailing headers,
                    // onHeadersReceived should be called before
                    // updateBodyAndInterrupt
                    if (last) {
                        LastHttpContent lastChunk = (LastHttpContent) chunk;
                        HttpHeaders trailingHeaders = lastChunk.trailingHeaders();
                        if (!trailingHeaders.isEmpty()) {
                            interrupt = handler.onHeadersReceived(new ResponseHeaders(future.getURI(), future.getHttpResponse().headers(), trailingHeaders)) != STATE.CONTINUE;
                        }
                    }

                    if (!interrupt && chunk.content().readableBytes() > 0) {
                        // FIXME why
                        interrupt = updateBodyAndInterrupt(future, handler, new ResponseBodyPart(future.getURI(), chunk));
                    }

                    if (interrupt || last) {
                        finishUpdate(future, ctx, !last);
                    }
                }
            } catch (Exception t) {
                if (t instanceof IOException && !config.getIOExceptionFilters().isEmpty() && applyIoExceptionFiltersAndReplayRequest(ctx, future, IOException.class.cast(t))) {
                    return;
                }

                try {
                    channels.abort(future, t);
                } finally {
                    finishUpdate(future, ctx, false);
                    throw t;
                }
            }
        }

        @Override
        public void onError(ChannelHandlerContext ctx, Throwable error) {
        }

        @Override
        public void onClose(ChannelHandlerContext ctx) {
        }
    }

    private final class WebSocketProtocol implements Protocol {

        private static final byte OPCODE_TEXT = 0x1;
        private static final byte OPCODE_BINARY = 0x2;
        private static final byte OPCODE_UNKNOWN = -1;
        protected byte pendingOpcode = OPCODE_UNKNOWN;

        // We don't need to synchronize as replacing the "ws-decoder" will
        // process using the same thread.
        private void invokeOnSucces(ChannelHandlerContext ctx, WebSocketUpgradeHandler h) {
            if (!h.touchSuccess()) {
                try {
                    h.onSuccess(new NettyWebSocket(ctx.channel()));
                } catch (Exception ex) {
                    LOGGER.warn("onSuccess unexpected exception", ex);
                }
            }
        }

        @Override
        public void handle(ChannelHandlerContext ctx, NettyResponseFuture future, Object e) throws Exception {
            WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(future.getAsyncHandler());
            Request request = future.getRequest();

            if (e instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e;

                HttpResponseStatus s = new ResponseStatus(future.getURI(), response);
                HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response.headers());

                // FIXME there's a method for that IIRC
                FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(h).request(request).responseStatus(s).responseHeaders(responseHeaders).build();
                for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                    try {
                        fc = asyncFilter.filter(fc);
                        if (fc == null) {
                            throw new NullPointerException("FilterContext is null");
                        }
                    } catch (FilterException efe) {
                        channels.abort(future, efe);
                    }
                }

                // The handler may have been wrapped.
                future.setAsyncHandler(fc.getAsyncHandler());

                // The request has changed
                if (fc.replayRequest()) {
                    requestSender.replayRequest(future, fc, ctx);
                    return;
                }

                future.setHttpResponse(response);
                if (redirect(request, future, response, ctx))
                    return;

                io.netty.handler.codec.http.HttpResponseStatus status = io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;

                boolean validStatus = response.getStatus().equals(status);
                boolean validUpgrade = response.headers().get(HttpHeaders.Names.UPGRADE) != null;
                String c = response.headers().get(HttpHeaders.Names.CONNECTION);
                if (c == null) {
                    c = response.headers().get(HttpHeaders.Names.CONNECTION.toLowerCase());
                }

                boolean validConnection = c == null ? false : c.equalsIgnoreCase(HttpHeaders.Values.UPGRADE);

                s = new ResponseStatus(future.getURI(), response);
                final boolean statusReceived = h.onStatusReceived(s) == STATE.UPGRADE;

                final boolean headerOK = h.onHeadersReceived(responseHeaders) == STATE.CONTINUE;
                if (!headerOK || !validStatus || !validUpgrade || !validConnection || !statusReceived) {
                    channels.abort(future, new IOException("Invalid handshake response"));
                    return;
                }

                String accept = response.headers().get(HttpHeaders.Names.SEC_WEBSOCKET_ACCEPT);
                String key = WebSocketUtil.getAcceptKey(future.getNettyRequest().headers().get(HttpHeaders.Names.SEC_WEBSOCKET_KEY));
                if (accept == null || !accept.equals(key)) {
                    throw new IOException(String.format("Invalid challenge. Actual: %s. Expected: %s", accept, key));
                }

                Channels.upgradePipelineForWebSockets(ctx);

                invokeOnSucces(ctx, h);
                future.done();

            } else if (e instanceof WebSocketFrame) {

                invokeOnSucces(ctx, h);

                final WebSocketFrame frame = (WebSocketFrame) e;

                if (frame instanceof TextWebSocketFrame) {
                    pendingOpcode = OPCODE_TEXT;
                } else if (frame instanceof BinaryWebSocketFrame) {
                    pendingOpcode = OPCODE_BINARY;
                }

                if (frame.content() != null && frame.content().readableBytes() > 0) {
                    HttpContent webSocketChunk = new DefaultHttpContent(Unpooled.wrappedBuffer(frame.content()));
                    ResponseBodyPart rp = new ResponseBodyPart(future.getURI(), webSocketChunk);
                    h.onBodyPartReceived(rp);

                    NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());

                    if (webSocket != null) {
                        if (pendingOpcode == OPCODE_BINARY) {
                            webSocket.onBinaryFragment(rp.getBodyPartBytes(), frame.isFinalFragment());
                        } else {
                            webSocket.onTextFragment(frame.content().toString(Constants.UTF8), frame.isFinalFragment());
                        }

                        if (frame instanceof CloseWebSocketFrame) {
                            try {
                                Channels.setDefaultAttribute(ctx, DiscardEvent.INSTANCE);
                                webSocket.onClose(CloseWebSocketFrame.class.cast(frame).statusCode(), CloseWebSocketFrame.class.cast(frame).reasonText());
                            } catch (Throwable t) {
                                // Swallow any exception that may comes from a
                                // Netty version released before 3.4.0
                                LOGGER.trace("", t);
                            }
                        }
                    } else {
                        LOGGER.debug("UpgradeHandler returned a null NettyWebSocket ");
                    }
                }
            } else if (e instanceof LastHttpContent) {
                // FIXME what to do with this kind of messages?
            } else {
                LOGGER.error("Invalid message {}", e);
            }
        }

        @Override
        public void onError(ChannelHandlerContext ctx, Throwable e) {
            try {
                Object attribute = Channels.getDefaultAttribute(ctx);
                LOGGER.warn("onError {}", e);
                if (!(attribute instanceof NettyResponseFuture)) {
                    return;
                }

                NettyResponseFuture<?> nettyResponse = (NettyResponseFuture) attribute;
                WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());

                NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());
                if (webSocket != null) {
                    webSocket.onError(e.getCause());
                    webSocket.close();
                }
            } catch (Throwable t) {
                LOGGER.error("onError", t);
            }
        }

        @Override
        public void onClose(ChannelHandlerContext ctx) {
            LOGGER.trace("onClose {}");
            Object attribute = Channels.getDefaultAttribute(ctx);
            if (!(attribute instanceof NettyResponseFuture)) {
                return;
            }

            try {
                NettyResponseFuture<?> nettyResponse = NettyResponseFuture.class.cast(attribute);
                WebSocketUpgradeHandler h = WebSocketUpgradeHandler.class.cast(nettyResponse.getAsyncHandler());
                NettyWebSocket webSocket = NettyWebSocket.class.cast(h.onCompleted());

                // FIXME How could this test not succeed, attachment is a
                // NettyResponseFuture????
                if (attribute != DiscardEvent.INSTANCE)
                    webSocket.close(1006, "Connection was closed abnormally (that is, with no close frame being sent).");
            } catch (Throwable t) {
                LOGGER.error("onError", t);
            }
        }
    }
}
