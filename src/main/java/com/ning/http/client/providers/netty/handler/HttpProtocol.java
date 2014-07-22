package com.ning.http.client.providers.netty.handler;

import static com.ning.http.util.MiscUtils.isNonEmpty;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.FilterException;
import com.ning.http.client.filter.IOExceptionFilter;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.ntlm.NTLMEngineException;
import com.ning.http.client.providers.netty.Callback;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.client.providers.netty.response.ResponseBodyPart;
import com.ning.http.client.providers.netty.response.ResponseHeaders;
import com.ning.http.client.providers.netty.response.ResponseStatus;
import com.ning.http.client.providers.netty.spnego.SpnegoEngine;
import com.ning.http.client.providers.netty.util.HttpUtil;
import com.ning.http.client.uri.UriComponents;
import com.ning.http.util.AsyncHttpProviderUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;

public class HttpProtocol extends Protocol {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpProtocol.class);

    private final Processor webSocketProcessor;

    public HttpProtocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyRequestSender nettyRequestSender, Processor webSocketProcessor) {
        super(channelManager, config, nettyRequestSender);
        this.webSocketProcessor = webSocketProcessor;
    }

    private void markAsDone(final NettyResponseFuture<?> future, final Channel channel) throws MalformedURLException {
        // We need to make sure everything is OK before adding the connection back to the pool.
        try {
            future.done();
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            LOGGER.debug(t.getMessage(), t);
        }

        if (!future.isKeepAlive() || !channel.isReadable()) {
            channelManager.closeChannel(channel);
        }
    }

    private final void configureKeepAlive(NettyResponseFuture<?> future, HttpResponse response) {
        String connectionHeader = response.headers().get(HttpHeaders.Names.CONNECTION);
        future.setKeepAlive(connectionHeader == null || connectionHeader.equalsIgnoreCase(HttpHeaders.Values.KEEP_ALIVE));
    }

    private Realm kerberosChallenge(List<String> proxyAuth, Request request, ProxyServer proxyServer,
            FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future, boolean proxyInd)
            throws NTLMEngineException {

        UriComponents uri = request.getURI();
        String host = request.getVirtualHost() != null ? request.getVirtualHost() : uri.getHost();
        String server = proxyServer == null ? host : proxyServer.getHost();
        try {
            String challengeHeader = SpnegoEngine.INSTANCE.generateToken(server);
            headers.remove(HttpHeaders.Names.AUTHORIZATION);
            headers.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);

            Realm.RealmBuilder realmBuilder;
            if (realm != null) {
                realmBuilder = new Realm.RealmBuilder().clone(realm);
            } else {
                realmBuilder = new Realm.RealmBuilder();
            }
            return realmBuilder.setUri(uri).setMethodName(request.getMethod()).setScheme(Realm.AuthScheme.KERBEROS).build();
        } catch (Throwable throwable) {
            if (HttpUtil.isNTLM(proxyAuth))
                return ntlmChallenge(proxyAuth, request, proxyServer, headers, realm, future, proxyInd);
            nettyRequestSender.abort(future, throwable);
            return null;
        }
    }

    private String authorizationHeaderName(boolean proxyInd) {
        return proxyInd ? HttpHeaders.Names.PROXY_AUTHORIZATION : HttpHeaders.Names.AUTHORIZATION;
    }

    private void addNTLMAuthorization(FluentCaseInsensitiveStringsMap headers, String challengeHeader, boolean proxyInd) {
        headers.add(authorizationHeaderName(proxyInd), "NTLM " + challengeHeader);
    }

    private void addType3NTLMAuthorizationHeader(List<String> auth, FluentCaseInsensitiveStringsMap headers, String username,
            String password, String domain, String workstation, boolean proxyInd) throws NTLMEngineException {
        headers.remove(authorizationHeaderName(proxyInd));

        // Beware of space!, see #462
        if (isNonEmpty(auth) && auth.get(0).startsWith("NTLM ")) {
            String serverChallenge = auth.get(0).trim().substring("NTLM ".length());
            String challengeHeader = NTLMEngine.INSTANCE.generateType3Msg(username, password, domain, workstation, serverChallenge);
            addNTLMAuthorization(headers, challengeHeader, proxyInd);
        }
    }

    private Realm ntlmChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers,
            Realm realm, NettyResponseFuture<?> future, boolean proxyInd) throws NTLMEngineException {

        boolean useRealm = (proxyServer == null && realm != null);

        String ntlmDomain = useRealm ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
        String ntlmHost = useRealm ? realm.getNtlmHost() : proxyServer.getHost();
        String principal = useRealm ? realm.getPrincipal() : proxyServer.getPrincipal();
        String password = useRealm ? realm.getPassword() : proxyServer.getPassword();
        UriComponents uri = request.getURI();

        Realm.RealmBuilder realmBuilder;
        if (realm != null && !realm.isNtlmMessageType2Received()) {
            String challengeHeader = NTLMEngine.INSTANCE.generateType1Msg(ntlmDomain, ntlmHost);
            addNTLMAuthorization(headers, challengeHeader, proxyInd);
            realmBuilder = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme()).setNtlmMessageType2Received(true);
            future.getAndSetAuth(false);
        } else {
            addType3NTLMAuthorizationHeader(wwwAuth, headers, principal, password, ntlmDomain, ntlmHost, proxyInd);

            if (realm != null) {
                realmBuilder = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme());
            } else {
                realmBuilder = new Realm.RealmBuilder().setScheme(Realm.AuthScheme.NTLM);
            }
        }

        return realmBuilder.setUri(uri).setMethodName(request.getMethod()).build();
    }

    private Realm ntlmProxyChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer,
            FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future) throws NTLMEngineException {
        future.getAndSetAuth(false);

        addType3NTLMAuthorizationHeader(wwwAuth, headers, proxyServer.getPrincipal(), proxyServer.getPassword(),
                proxyServer.getNtlmDomain(), proxyServer.getHost(), true);

        Realm.RealmBuilder realmBuilder = new Realm.RealmBuilder();
        if (realm != null) {
            realmBuilder = realmBuilder.clone(realm);
        }
        return realmBuilder.setUri(request.getURI()).setMethodName(request.getMethod()).build();
    }

    private final boolean exitAfterProcessingFilters(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler handler, Request request, HttpResponseStatus status, HttpResponseHeaders responseHeaders) throws IOException {
        if (!config.getResponseFilters().isEmpty()) {
            FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(request).responseStatus(status)
                    .responseHeaders(responseHeaders).build();

            for (ResponseFilter asyncFilter : config.getResponseFilters()) {
                try {
                    fc = asyncFilter.filter(fc);
                    if (fc == null) {
                        throw new NullPointerException("FilterContext is null");
                    }
                } catch (FilterException efe) {
                    nettyRequestSender.abort(future, efe);
                }
            }

            // The handler may have been wrapped.
            future.setAsyncHandler(fc.getAsyncHandler());

            // The request has changed
            if (fc.replayRequest()) {
                replayRequest(future, fc, channel);
                return true;
            }
        }
        return false;
    }

    private final boolean exitAfterHandling401(//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode,//
            Realm realm,//
            ProxyServer proxyServer,//
            final RequestBuilder requestBuilder) throws Exception {

        if (statusCode == 401 && realm != null && !future.getAndSetAuth(true)) {

            List<String> wwwAuthHeaders = HttpUtil.getNettyHeaderValuesByCaseInsensitiveName(response.headers(),
                    HttpHeaders.Names.WWW_AUTHENTICATE);

            if (!wwwAuthHeaders.isEmpty()) {
                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;

                FluentCaseInsensitiveStringsMap requestHeaders = request.getHeaders();

                if (!wwwAuthHeaders.contains("Kerberos") && (HttpUtil.isNTLM(wwwAuthHeaders) || (wwwAuthHeaders.contains("Negotiate")))) {
                    // NTLM
                    newRealm = ntlmChallenge(wwwAuthHeaders, request, proxyServer, requestHeaders, realm, future, false);
                } else if (wwwAuthHeaders.contains("Negotiate")) {
                    // SPNEGO KERBEROS
                    newRealm = kerberosChallenge(wwwAuthHeaders, request, proxyServer, requestHeaders, realm, future, false);
                    if (newRealm == null)
                        return true;
                } else {
                    newRealm = new Realm.RealmBuilder().clone(realm) //
                            .setScheme(realm.getAuthScheme()) //
                            .setUri(request.getURI()) //
                            .setMethodName(request.getMethod()) //
                            .setUsePreemptiveAuth(true) //
                            .parseWWWAuthenticateHeader(wwwAuthHeaders.get(0))//
                            .build();
                }

                final Realm nr = newRealm;

                LOGGER.debug("Sending authentication to {}", request.getURI());
                final Request nextRequest = requestBuilder.setHeaders(requestHeaders).setRealm(nr).build();
                Callback ac = new Callback(future) {
                    public void call() throws Exception {
                        // not waiting for the channel to be drained, so we might ended up pooling the initial channel and creating a new one
                        nettyRequestSender.drainChannel(channel, future);
                        nettyRequestSender.nextRequest(nextRequest, future);
                    }
                };

                if (future.isKeepAlive() && response.isChunked())
                    // we must make sure there is no chunk left before executing the next request
                    Channels.setAttachment(channel, ac);
                else
                    // FIXME couldn't we reuse the channel right now?
                    ac.call();
                return true;
            }
        }
        return false;
    }

    private final boolean exitAfterHandling407(//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode,//
            Realm realm,//
            ProxyServer proxyServer,//
            final RequestBuilder requestBuilder) throws Exception {

        if (statusCode == 407 && realm != null && !future.getAndSetAuth(true)) {
            List<String> proxyAuth = HttpUtil.getNettyHeaderValuesByCaseInsensitiveName(response.headers(),
                    HttpHeaders.Names.PROXY_AUTHENTICATE);
            if (!proxyAuth.isEmpty()) {
                LOGGER.debug("Sending proxy authentication to {}", request.getURI());

                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;
                FluentCaseInsensitiveStringsMap requestHeaders = request.getHeaders();

                if (!proxyAuth.contains("Kerberos") && (HttpUtil.isNTLM(proxyAuth) || (proxyAuth.contains("Negotiate")))) {
                    newRealm = ntlmProxyChallenge(proxyAuth, request, proxyServer, requestHeaders, realm, future);
                    // SPNEGO KERBEROS
                } else if (proxyAuth.contains("Negotiate")) {
                    newRealm = kerberosChallenge(proxyAuth, request, proxyServer, requestHeaders, realm, future, true);
                    if (newRealm == null)
                        return true;
                } else {
                    newRealm = new Realm.RealmBuilder().clone(realm)//
                            .setScheme(realm.getAuthScheme())//
                            .setUri(request.getURI())//
                            .setMethodName("CONNECT")//
                            .setTargetProxy(true)//
                            .setUsePreemptiveAuth(true)//
                            .parseProxyAuthenticateHeader(proxyAuth.get(0))//
                            .build();
                }

                Request req = requestBuilder.setHeaders(requestHeaders).setRealm(newRealm).build();
                future.setReuseChannel(true);
                future.setConnectAllowed(true);
                nettyRequestSender.nextRequest(req, future);
                return true;
            }
        }
        return false;
    }

    private boolean exitAfterHandling100(Channel channel, NettyResponseFuture<?> future, int statusCode) {
        if (statusCode == 100) {
            future.getAndSetWriteHeaders(false);
            future.getAndSetWriteBody(true);
            nettyRequestSender.writeRequest(channel, config, future);
            return true;
        }
        return false;
    }

    private boolean exitAfterHandlingConnect(Channel channel,//
            NettyResponseFuture<?> future,//
            Request request,//
            ProxyServer proxyServer,//
            int statusCode,//
            RequestBuilder requestBuilder,//
            HttpRequest nettyRequest) throws IOException {

        if (nettyRequest.getMethod().equals(HttpMethod.CONNECT) && statusCode == 200) {

            LOGGER.debug("Connected to {}:{}", proxyServer.getHost(), proxyServer.getPort());

            if (future.isKeepAlive()) {
                future.attachChannel(channel, true);
            }

            try {
                UriComponents requestURI = request.getURI();
                String scheme = requestURI.getScheme();
                String host = requestURI.getHost();
                int port = AsyncHttpProviderUtils.getDefaultPort(requestURI);

                LOGGER.debug("Connecting to proxy {} for scheme {}", proxyServer, scheme);
                channelManager.upgradeProtocol(channel.getPipeline(), scheme, host, port, webSocketProcessor);

            } catch (Throwable ex) {
                nettyRequestSender.abort(future, ex);
            }
            Request req = requestBuilder.build();
            future.setReuseChannel(true);
            future.setConnectAllowed(false);
            nettyRequestSender.nextRequest(req, future);
            return true;
        }
        return false;
    }

    private final boolean updateStatusAndInterrupt(AsyncHandler<?> handler, HttpResponseStatus c) throws Exception {
        return handler.onStatusReceived(c) != STATE.CONTINUE;
    }

    private final boolean updateHeadersAndInterrupt(AsyncHandler<?> handler, HttpResponseHeaders c) throws Exception {
        return handler.onHeadersReceived(c) != STATE.CONTINUE;
    }

    private final boolean updateBodyAndInterrupt(final NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponseBodyPart c)
            throws Exception {
        boolean state = handler.onBodyPartReceived(c) != STATE.CONTINUE;
        if (c.isUnderlyingConnectionToBeClosed())
            future.setKeepAlive(false);
        return state;
    }

    private final boolean exitAfterHandlingStatus(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler, HttpResponseStatus status) throws IOException, Exception {
        if (!future.getAndSetStatusReceived(true) && updateStatusAndInterrupt(handler, status)) {
            finishUpdate(future, channel, response.isChunked());
            return true;
        }
        return false;
    }

    private final boolean exitAfterHandlingHeaders(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler, HttpResponseHeaders responseHeaders) throws IOException, Exception {
        if (!response.headers().isEmpty() && updateHeadersAndInterrupt(handler, responseHeaders)) {
            finishUpdate(future, channel, response.isChunked());
            return true;
        }
        return false;
    }

    private final boolean exitAfterHandlingBody(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler) throws Exception {
        if (!response.isChunked()) {
            updateBodyAndInterrupt(future, handler, new ResponseBodyPart(response, null, true));
            finishUpdate(future, channel, false);
            return true;
        }
        return false;
    }

    private final boolean exitAfterHandlingHead(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler, HttpRequest nettyRequest) throws Exception {
        if (nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
            updateBodyAndInterrupt(future, handler, new ResponseBodyPart(response, null, true));
            markAsDone(future, channel);
            nettyRequestSender.drainChannel(channel, future);
        }
        return false;
    }

    private final void handleHttpResponse(final HttpResponse response, final Channel channel, final NettyResponseFuture<?> future,
            AsyncHandler<?> handler) throws Exception {

        HttpRequest nettyRequest = future.getNettyRequest();
        Request request = future.getRequest();
        ProxyServer proxyServer = future.getProxyServer();
        LOGGER.debug("\n\nRequest {}\n\nResponse {}\n", nettyRequest, response);

        // Required if there is some trailing headers.
        future.setHttpResponse(response);

        configureKeepAlive(future, response);

        HttpResponseStatus status = new ResponseStatus(future.getURI(), config, response);
        HttpResponseHeaders responseHeaders = new ResponseHeaders(response);

        if (exitAfterProcessingFilters(channel, future, response, handler, request, status, responseHeaders))
            return;

        final RequestBuilder requestBuilder = new RequestBuilder(future.getRequest());

        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        int statusCode = response.getStatus().getCode();

        // FIXME
        if (exitAfterHandling401(channel, future, response, request, statusCode, realm, proxyServer, requestBuilder) || //
                exitAfterHandling407(future, response, request, statusCode, realm, proxyServer, requestBuilder) || //
                exitAfterHandling100(channel, future, statusCode) || //
                exitAfterHandlingRedirect(channel, future, request, response, statusCode) || //
                exitAfterHandlingConnect(channel, future, request, proxyServer, statusCode, requestBuilder, nettyRequest) || //
                exitAfterHandlingStatus(channel, future, response, handler, status) || //
                exitAfterHandlingHeaders(channel, future, response, handler, responseHeaders) || //
                exitAfterHandlingBody(channel, future, response, handler) || //
                exitAfterHandlingHead(channel, future, response, handler, nettyRequest)) {
            return;
        }
    }

    private final void handleChunk(final HttpChunk chunk, final Channel channel, final NettyResponseFuture<?> future,
            final AsyncHandler<?> handler) throws Exception {
        boolean last = chunk.isLast();
        // we don't notify updateBodyAndInterrupt with the last chunk as it's empty
        if (last || updateBodyAndInterrupt(future, handler, new ResponseBodyPart(null, chunk, last))) {

            if (chunk instanceof HttpChunkTrailer) {
                HttpChunkTrailer chunkTrailer = (HttpChunkTrailer) chunk;
                if (!chunkTrailer.trailingHeaders().isEmpty()) {
                    ResponseHeaders responseHeaders = new ResponseHeaders(future.getHttpResponse(), chunkTrailer);
                    updateHeadersAndInterrupt(handler, responseHeaders);
                }
            }
            finishUpdate(future, channel, !chunk.isLast());
        }
    }

    private FilterContext<?> handleIoException(FilterContext<?> fc, NettyResponseFuture<?> future) {
        for (IOExceptionFilter asyncFilter : config.getIOExceptionFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                if (fc == null) {
                    throw new NullPointerException("FilterContext is null");
                }
            } catch (FilterException efe) {
                nettyRequestSender.abort(future, efe);
            }
        }
        return fc;
    }

    private void finishUpdate(final NettyResponseFuture<?> future, Channel channel, boolean expectOtherChunks) throws IOException {
        boolean keepAlive = future.isKeepAlive();
        if (expectOtherChunks && keepAlive)
            nettyRequestSender.drainChannel(channel, future);
        else
            channelManager.tryToOfferChannelToPool(channel, keepAlive, channelManager.getPoolKey(future));
        markAsDone(future, channel);
    }

    public void handle(final Channel channel, final MessageEvent e, final NettyResponseFuture<?> future) throws Exception {

        // The connect timeout occurred.
        if (future.isDone()) {
            channelManager.closeChannel(channel);
            return;
        }

        future.touch();

        AsyncHandler<?> handler = future.getAsyncHandler();
        Object message = e.getMessage();
        try {
            if (message instanceof HttpResponse)
                handleHttpResponse((HttpResponse) message, channel, future, handler);

            else if (message instanceof HttpChunk)
                handleChunk((HttpChunk) message, channel, future, handler);

        } catch (Exception t) {
            if (t instanceof IOException && !config.getIOExceptionFilters().isEmpty()) {
                FilterContext<?> fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(future.getRequest())
                        .ioException(IOException.class.cast(t)).build();
                fc = handleIoException(fc, future);

                if (fc.replayRequest()) {
                    replayRequest(future, fc, channel);
                    return;
                }
            }

            try {
                nettyRequestSender.abort(future, t);
            } finally {
                finishUpdate(future, channel, false);
                throw t;
            }
        }
    }

    public void onError(Channel channel, ExceptionEvent e) {
    }

    public void onClose(Channel channel, ChannelStateEvent e) {
    }
}
