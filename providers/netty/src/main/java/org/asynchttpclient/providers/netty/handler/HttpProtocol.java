/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.handler;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.asynchttpclient.providers.netty.util.HttpUtil.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.STATE;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.ntlm.NTLMEngineException;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.providers.netty.response.ResponseHeaders;
import org.asynchttpclient.providers.netty.response.ResponseStatus;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.util.AsyncHttpProviderUtils;

final class HttpProtocol extends Protocol {

    public HttpProtocol(Channels channels, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, NettyRequestSender requestSender) {
        super(channels, config, nettyConfig, requestSender);
    }

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

    private void finishUpdate(final NettyResponseFuture<?> future, ChannelHandlerContext ctx, boolean lastValidChunk) throws IOException {
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

    private final boolean updateBodyAndInterrupt(NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponseBodyPart bodyPart) throws Exception {
        boolean state = handler.onBodyPartReceived(bodyPart) != STATE.CONTINUE;
        if (bodyPart.isUnderlyingConnectionToBeClosed()) {
            future.setKeepAlive(false);
        }
        return state;
    }

    private void markAsDone(NettyResponseFuture<?> future, final ChannelHandlerContext ctx) throws MalformedURLException {
        // We need to make sure everything is OK before adding the
        // connection back to the pool.
        try {
            future.done();
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            NettyChannelHandler.LOGGER.debug(t.getMessage(), t);
        }

        if (!future.isKeepAlive() || !ctx.channel().isActive()) {
            channels.closeChannel(ctx);
        }
    }

    private boolean applyResponseFiltersAndReplayRequest(ChannelHandlerContext ctx, NettyResponseFuture<?> future, HttpResponseStatus status,
            HttpResponseHeaders responseHeaders) throws IOException {

        AsyncHandler handler = future.getAsyncHandler();
        FilterContext fc = new FilterContext.FilterContextBuilder().asyncHandler(handler).request(future.getRequest()).responseStatus(status).responseHeaders(responseHeaders)
                .build();

        for (ResponseFilter asyncFilter : config.getResponseFilters()) {
            try {
                fc = asyncFilter.filter(fc);
                // FIXME Is it worth protecting against this?
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
            return true;
        }
        return false;
    }

    private boolean handleResponseAndExit(final ChannelHandlerContext ctx, final NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpRequest nettyRequest,
            ProxyServer proxyServer, HttpResponse response) throws Exception {
        Request request = future.getRequest();
        int statusCode = response.getStatus().code();
        HttpResponseStatus status = new ResponseStatus(future.getURI(), response, config);
        HttpResponseHeaders responseHeaders = new ResponseHeaders(future.getURI(), response.headers());
        final FluentCaseInsensitiveStringsMap headers = request.getHeaders();
        final RequestBuilder builder = new RequestBuilder(future.getRequest());
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();

        // store the original headers so we can re-send all them to
        // the handler in case of trailing headers
        future.setHttpResponse(response);

        future.setKeepAlive(!HttpHeaders.Values.CLOSE.equalsIgnoreCase(response.headers().get(HttpHeaders.Names.CONNECTION)));

        if (!config.getResponseFilters().isEmpty() && applyResponseFiltersAndReplayRequest(ctx, future, status, responseHeaders)) {
            return true;
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
                        return true;
                    }
                } else {
                    newRealm = new Realm.RealmBuilder().clone(realm).setScheme(realm.getAuthScheme()).setUri(request.getURI().getPath()).setMethodName(request.getMethod())
                            .setUsePreemptiveAuth(true).parseWWWAuthenticateHeader(wwwAuth.get(0)).build();
                }

                final Realm nr = new Realm.RealmBuilder().clone(newRealm).setUri(URI.create(request.getUrl()).getPath()).build();

                NettyChannelHandler.LOGGER.debug("Sending authentication to {}", request.getUrl());
                Callback callback = new Callback(future) {
                    public void call() throws Exception {
                        channels.drainChannel(ctx, future);
                        requestSender.sendNextRequest(builder.setHeaders(headers).setRealm(nr).build(), future);
                    }
                };

                if (future.isKeepAlive() && HttpHeaders.isTransferEncodingChunked(response)) {
                    // We must make sure there is no bytes left
                    // before executing the next request.
                    Channels.setDefaultAttribute(ctx, callback);
                } else {
                    callback.call();
                }

                return true;
            }

        } else if (statusCode == CONTINUE.code()) {
            future.getAndSetWriteHeaders(false);
            future.getAndSetWriteBody(true);
            // FIXME why not reuse the channel?
            requestSender.writeRequest(ctx.channel(), config, future);
            return true;

        } else if (statusCode == PROXY_AUTHENTICATION_REQUIRED.code()) {
            List<String> proxyAuth = getAuthorizationToken(response.headers(), HttpHeaders.Names.PROXY_AUTHENTICATE);
            if (realm != null && !proxyAuth.isEmpty() && !future.getAndSetAuth(true)) {
                NettyChannelHandler.LOGGER.debug("Sending proxy authentication to {}", request.getUrl());

                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;

                boolean negociate = proxyAuth.contains("Negotiate");
                if (!proxyAuth.contains("Kerberos") && (isNTLM(proxyAuth) || negociate)) {
                    newRealm = ntlmProxyChallenge(proxyAuth, request, proxyServer, headers, realm, future);
                    // SPNEGO KERBEROS
                } else if (negociate) {
                    newRealm = kerberosChallenge(proxyAuth, request, proxyServer, headers, realm, future);
                    if (newRealm == null) {
                        return true;
                    }
                } else {
                    newRealm = future.getRequest().getRealm();
                }

                future.setReuseChannel(true);
                future.setConnectAllowed(true);
                requestSender.sendNextRequest(builder.setHeaders(headers).setRealm(newRealm).build(), future);
                return true;
            }

        } else if (statusCode == OK.code() && nettyRequest.getMethod() == HttpMethod.CONNECT) {

            NettyChannelHandler.LOGGER.debug("Connected to {}:{}", proxyServer.getHost(), proxyServer.getPort());

            if (future.isKeepAlive()) {
                future.attachChannel(ctx.channel(), true);
            }

            try {
                NettyChannelHandler.LOGGER.debug("Connecting to proxy {} for scheme {}", proxyServer, request.getUrl());
                channels.upgradeProtocol(ctx.channel().pipeline(), request.getURI().getScheme());
            } catch (Throwable ex) {
                channels.abort(future, ex);
            }
            future.setReuseChannel(true);
            future.setConnectAllowed(false);
            requestSender.sendNextRequest(builder.build(), future);
            return true;

        }

        if (redirect(request, future, response, ctx)) {
            return true;
        }

        if (!future.getAndSetStatusReceived(true) && (handler.onStatusReceived(status) != STATE.CONTINUE || handler.onHeadersReceived(responseHeaders) != STATE.CONTINUE)) {
            finishUpdate(future, ctx, HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }

        return false;
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
        ProxyServer proxyServer = future.getProxyServer();
        try {
            if (e instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e;
                NettyChannelHandler.LOGGER.debug("\n\nRequest {}\n\nResponse {}\n", nettyRequest, response);
                future.setPendingResponse(response);
                return;
            }

            if (e instanceof HttpContent) {

                HttpResponse response = future.getPendingResponse();
                future.setPendingResponse(null);
                if (handler != null) {
                    if (response != null && handleResponseAndExit(ctx, future, handler, nettyRequest, proxyServer, response)) {
                        return;
                    }

                    HttpContent chunk = (HttpContent) e;

                    boolean interrupt = false;
                    boolean last = chunk instanceof LastHttpContent;

                    if (last) {
                        LastHttpContent lastChunk = (LastHttpContent) chunk;
                        HttpHeaders trailingHeaders = lastChunk.trailingHeaders();
                        if (!trailingHeaders.isEmpty()) {
                            interrupt = handler.onHeadersReceived(new ResponseHeaders(future.getURI(), future.getHttpResponse().headers(), trailingHeaders)) != STATE.CONTINUE;
                        }
                    }

                    ByteBuf buf = chunk.content();
                    if (!interrupt && buf.readableBytes() > 0) {
                        interrupt = updateBodyAndInterrupt(future, handler, nettyConfig.getBodyPartFactory().newResponseBodyPart(buf, last));
                    }

                    if (interrupt || last) {
                        finishUpdate(future, ctx, !last);
                    }
                }
            }
        } catch (Exception t) {
            if (t instanceof IOException && !config.getIOExceptionFilters().isEmpty()
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(ctx, future, IOException.class.cast(t))) {
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