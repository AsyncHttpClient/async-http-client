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

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.asynchttpclient.providers.netty.util.HttpUtil.isNTLM;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

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
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.ntlm.NTLMEngineException;
import org.asynchttpclient.providers.netty.Callback;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.NettyRequest;
import org.asynchttpclient.providers.netty.request.NettyRequestSender;
import org.asynchttpclient.providers.netty.response.NettyResponseBodyPart;
import org.asynchttpclient.providers.netty.response.ResponseHeaders;
import org.asynchttpclient.providers.netty.response.ResponseStatus;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.uri.UriComponents;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.util.List;

final class HttpProtocol extends Protocol {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpProtocol.class);

    public HttpProtocol(Channels channels, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig,
            NettyRequestSender requestSender) {
        super(channels, config, nettyConfig, requestSender);
    }

    private Realm.RealmBuilder newRealmBuilder(Realm realm) {
        return realm != null ? new Realm.RealmBuilder().clone(realm) : new Realm.RealmBuilder();
    }

    private Realm kerberosChallenge(List<String> proxyAuth, Request request, ProxyServer proxyServer,
            FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future, boolean proxyInd) throws NTLMEngineException {

        UriComponents uri = request.getURI();
        String host = request.getVirtualHost() == null ? uri.getHost() : request.getVirtualHost();
        String server = proxyServer == null ? host : proxyServer.getHost();
        try {
            String challengeHeader = SpnegoEngine.instance().generateToken(server);
            headers.remove(HttpHeaders.Names.AUTHORIZATION);
            headers.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);

            return newRealmBuilder(realm)//
                    .setUri(uri)//
                    .setMethodName(request.getMethod())//
                    .setScheme(Realm.AuthScheme.KERBEROS)//
                    .build();

        } catch (Throwable throwable) {
            if (isNTLM(proxyAuth)) {
                return ntlmChallenge(proxyAuth, request, proxyServer, headers, realm, future, proxyInd);
            }
            channels.abort(future, throwable);
            return null;
        }
    }

    private String authorizationHeaderName(boolean proxyInd) {
        return proxyInd? HttpHeaders.Names.PROXY_AUTHORIZATION: HttpHeaders.Names.AUTHORIZATION;
    }
    
    private void addNTLMAuthorizationHeader(FluentCaseInsensitiveStringsMap headers, String challengeHeader, boolean proxyInd) {
        headers.add(authorizationHeaderName(proxyInd), "NTLM " + challengeHeader);
    }

    private Realm ntlmChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers,
            Realm realm, NettyResponseFuture<?> future, boolean proxyInd) throws NTLMEngineException {

        boolean useRealm = proxyServer == null && realm != null;

        String ntlmDomain = useRealm ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
        String ntlmHost = useRealm ? realm.getNtlmHost() : proxyServer.getHost();
        String principal = useRealm ? realm.getPrincipal() : proxyServer.getPrincipal();
        String password = useRealm ? realm.getPassword() : proxyServer.getPassword();
        UriComponents uri = request.getURI();

        if (realm != null && !realm.isNtlmMessageType2Received()) {
            String challengeHeader = NTLMEngine.INSTANCE.generateType1Msg(ntlmDomain, ntlmHost);

            addNTLMAuthorizationHeader(headers, challengeHeader, proxyInd);
            future.getAndSetAuth(false);
            return newRealmBuilder(realm)//
                    .setScheme(realm.getAuthScheme())//
                    .setUri(uri)//
                    .setMethodName(request.getMethod())//
                    .setNtlmMessageType2Received(true)//
                    .build();

        } else {
            addType3NTLMAuthorizationHeader(wwwAuth, headers, principal, password, ntlmDomain, ntlmHost, proxyInd);
            Realm.AuthScheme authScheme = realm != null ? realm.getAuthScheme() : Realm.AuthScheme.NTLM;
            return newRealmBuilder(realm)//
                    .setScheme(authScheme)//
                    .setUri(uri)//
                    .setMethodName(request.getMethod())//
                    .build();
        }
    }

    private Realm ntlmProxyChallenge(List<String> wwwAuth, Request request, ProxyServer proxyServer,
            FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future, boolean proxyInd) throws NTLMEngineException {
        future.getAndSetAuth(false);
        headers.remove(HttpHeaders.Names.PROXY_AUTHORIZATION);

        addType3NTLMAuthorizationHeader(wwwAuth, headers, proxyServer.getPrincipal(), proxyServer.getPassword(),
                proxyServer.getNtlmDomain(), proxyServer.getHost(), proxyInd);

        return newRealmBuilder(realm)//
                // .setScheme(realm.getAuthScheme())
                .setUri(request.getURI())//
                .setMethodName(request.getMethod()).build();
    }

    private void addType3NTLMAuthorizationHeader(List<String> auth, FluentCaseInsensitiveStringsMap headers, String username,
            String password, String domain, String workstation, boolean proxyInd) throws NTLMEngineException {
        headers.remove(authorizationHeaderName(proxyInd));

        if (isNonEmpty(auth) && auth.get(0).startsWith("NTLM ")) {
            String serverChallenge = auth.get(0).trim().substring("NTLM ".length());
            String challengeHeader = NTLMEngine.INSTANCE.generateType3Msg(username, password, domain, workstation, serverChallenge);
            addNTLMAuthorizationHeader(headers, challengeHeader, proxyInd);
        }
    }

    private void finishUpdate(final NettyResponseFuture<?> future, Channel channel, boolean lastValidChunk) throws IOException {
        if (lastValidChunk && future.isKeepAlive()) {
            channels.drainChannel(channel, future);
        } else {
            if (future.isKeepAlive() && channel.isActive() && channels.offerToPool(channels.getPoolKey(future), channel)) {
                markAsDone(future, channel);
                return;
            }
            channels.finishChannel(channel);
        }
        markAsDone(future, channel);
    }

    private final boolean updateBodyAndInterrupt(NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponseBodyPart bodyPart)
            throws Exception {
        boolean state = handler.onBodyPartReceived(bodyPart) != STATE.CONTINUE;
        if (bodyPart.isUnderlyingConnectionToBeClosed()) {
            future.setKeepAlive(false);
        }
        return state;
    }

    private void markAsDone(NettyResponseFuture<?> future, final Channel channel) {
        // We need to make sure everything is OK before adding the
        // connection back to the pool.
        try {
            future.done();
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            LOGGER.debug(t.getMessage(), t);
        }

        if (!future.isKeepAlive() || !channel.isActive()) {
            channels.closeChannel(channel);
        }
    }

    private boolean handleUnauthorizedAndExit(int statusCode, Realm realm, final Request request, HttpResponse response,
            final NettyResponseFuture<?> future, ProxyServer proxyServer, final Channel channel) throws Exception {
        if (statusCode == UNAUTHORIZED.code() && realm != null) {

            List<String> authenticateHeaders = response.headers().getAll(HttpHeaders.Names.WWW_AUTHENTICATE);

            if (!authenticateHeaders.isEmpty() && !future.getAndSetAuth(true)) {
                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;
                // NTLM
                boolean negociate = authenticateHeaders.contains("Negotiate");
                if (!authenticateHeaders.contains("Kerberos") && (isNTLM(authenticateHeaders) || negociate)) {
                    newRealm = ntlmChallenge(authenticateHeaders, request, proxyServer, request.getHeaders(), realm, future, false);
                    // SPNEGO KERBEROS
                } else if (negociate) {
                    newRealm = kerberosChallenge(authenticateHeaders, request, proxyServer, request.getHeaders(), realm, future, false);
                    if (newRealm == null) {
                        return true;
                    }
                } else {
                    newRealm = new Realm.RealmBuilder()//
                            .clone(realm)//
                            .setScheme(realm.getAuthScheme())//
                            .setUri(request.getURI())//
                            .setMethodName(request.getMethod())//
                            .setUsePreemptiveAuth(true)//
                            .parseWWWAuthenticateHeader(authenticateHeaders.get(0))//
                            .build();
                }

                Realm nr = newRealm;
                final Request nextRequest = new RequestBuilder(future.getRequest()).setHeaders(request.getHeaders()).setRealm(nr).build();

                LOGGER.debug("Sending authentication to {}", request.getURI());
                Callback callback = new Callback(future) {
                    public void call() throws Exception {
                        channels.drainChannel(channel, future);
                        requestSender.sendNextRequest(nextRequest, future);
                    }
                };

                if (future.isKeepAlive() && HttpHeaders.isTransferEncodingChunked(response)) {
                    // We must make sure there is no bytes left
                    // before executing the next request.
                    Channels.setDefaultAttribute(channel, callback);
                } else {
                    callback.call();
                }

                return true;
            }
        }

        return false;
    }

    private boolean handleContinueAndExit(final Channel channel, final NettyResponseFuture<?> future, int statusCode) {
        if (statusCode == CONTINUE.code()) {
            future.setHeadersAlreadyWrittenOnContinue(true);
            future.setDontWriteBodyBecauseExpectContinue(false);
            // FIXME why not reuse the channel?
            requestSender.writeRequest(future, channel);
            return true;

        }
        return false;
    }

    private boolean handleProxyAuthenticationRequiredAndExit(int statusCode,//
            Realm realm,//
            final Request request,//
            HttpResponse response,//
            final NettyResponseFuture<?> future,//
            ProxyServer proxyServer) throws Exception {

        if (statusCode == PROXY_AUTHENTICATION_REQUIRED.code() && realm != null) {
            List<String> proxyAuthenticateHeaders = response.headers().getAll(HttpHeaders.Names.PROXY_AUTHENTICATE);
            if (!proxyAuthenticateHeaders.isEmpty() && !future.getAndSetAuth(true)) {
                LOGGER.debug("Sending proxy authentication to {}", request.getURI());

                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;

                boolean negociate = proxyAuthenticateHeaders.contains("Negotiate");
                if (!proxyAuthenticateHeaders.contains("Kerberos") && (isNTLM(proxyAuthenticateHeaders) || negociate)) {
                    newRealm = ntlmProxyChallenge(proxyAuthenticateHeaders, request, proxyServer, request.getHeaders(), realm, future, true);
                    // SPNEGO KERBEROS
                } else if (negociate) {
                    newRealm = kerberosChallenge(proxyAuthenticateHeaders, request, proxyServer, request.getHeaders(), realm, future, true);
                    if (newRealm == null)
                        return true;
                } else {
                    newRealm = new Realm.RealmBuilder().clone(realm)//
                            .setScheme(realm.getAuthScheme())//
                            .setUri(request.getURI())//
                            .setOmitQuery(true)//
                            .setMethodName(HttpMethod.CONNECT.name())//
                            .setUsePreemptiveAuth(true)//
                            .parseProxyAuthenticateHeader(proxyAuthenticateHeaders.get(0))//
                            .build();
                }

                future.setReuseChannel(true);
                future.setConnectAllowed(true);
                Request nextRequest = new RequestBuilder(future.getRequest()).setHeaders(request.getHeaders()).setRealm(newRealm).build();
                requestSender.sendNextRequest(nextRequest, future);
                return true;
            }
        }
        return false;
    }

    private boolean handleConnectOKAndExit(int statusCode, Realm realm, final Request request, HttpRequest httpRequest,
            HttpResponse response, final NettyResponseFuture<?> future, ProxyServer proxyServer, final Channel channel) throws IOException {
        if (statusCode == OK.code() && httpRequest.getMethod() == HttpMethod.CONNECT) {

            LOGGER.debug("Connected to {}:{}", proxyServer.getHost(), proxyServer.getPort());

            if (future.isKeepAlive()) {
                future.attachChannel(channel, true);
            }

            try {
                UriComponents requestURI = request.getURI();
                String scheme = requestURI.getScheme();
                LOGGER.debug("Connecting to proxy {} for scheme {}", proxyServer, scheme);
                String host = requestURI.getHost();
                int port = AsyncHttpProviderUtils.getDefaultPort(requestURI);
                
                channels.upgradeProtocol(channel.pipeline(), scheme, host, port);
            } catch (Throwable ex) {
                channels.abort(future, ex);
            }
            future.setReuseChannel(true);
            future.setConnectAllowed(false);
            requestSender.sendNextRequest(new RequestBuilder(future.getRequest()).build(), future);
            return true;
        }

        return false;
    }

    private boolean handleHanderAndExit(Channel channel, NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponseStatus status,
            HttpResponseHeaders responseHeaders, HttpResponse response) throws Exception {
        if (!future.getAndSetStatusReceived(true)
                && (handler.onStatusReceived(status) != STATE.CONTINUE || handler.onHeadersReceived(responseHeaders) != STATE.CONTINUE)) {
            finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
        return false;
    }

    private boolean handleResponseAndExit(final Channel channel, final NettyResponseFuture<?> future, AsyncHandler<?> handler,
            HttpRequest httpRequest, ProxyServer proxyServer, HttpResponse response) throws Exception {

        // store the original headers so we can re-send all them to
        // the handler in case of trailing headers
        future.setHttpHeaders(response.headers());

        future.setKeepAlive(!HttpHeaders.Values.CLOSE.equalsIgnoreCase(response.headers().get(HttpHeaders.Names.CONNECTION)));

        HttpResponseStatus status = new ResponseStatus(future.getURI(), response, config);
        int statusCode = response.getStatus().code();
        Request request = future.getRequest();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        HttpResponseHeaders responseHeaders = new ResponseHeaders(response.headers());

        return handleResponseFiltersReplayRequestAndExit(channel, future, status, responseHeaders)//
                || handleUnauthorizedAndExit(statusCode, realm, request, response, future, proxyServer, channel)//
                || handleContinueAndExit(channel, future, statusCode)//
                || handleProxyAuthenticationRequiredAndExit(statusCode, realm, request, response, future, proxyServer)
                || handleConnectOKAndExit(statusCode, realm, request, httpRequest, response, future, proxyServer, channel)//
                || handleRedirectAndExit(request, future, response, channel)//
                || handleHanderAndExit(channel, future, handler, status, responseHeaders, response);
    }

    @Override
    public void handle(final Channel channel, final NettyResponseFuture<?> future, final Object e) throws Exception {
        future.touch();

        // The connect timeout occurred.
        if (future.isCancelled() || future.isDone()) {
            channels.finishChannel(channel);
            return;
        }

        NettyRequest nettyRequest = future.getNettyRequest();
        AsyncHandler<?> handler = future.getAsyncHandler();
        ProxyServer proxyServer = future.getProxyServer();
        try {
            if (e instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) e;
                LOGGER.debug("\n\nRequest {}\n\nResponse {}\n", nettyRequest.getHttpRequest(), response);
                future.setPendingResponse(response);
                return;
            }

            if (e instanceof HttpContent) {
                HttpResponse response = future.getPendingResponse();
                future.setPendingResponse(null);
                if (handler != null) {
                    if (response != null
                            && handleResponseAndExit(channel, future, handler, nettyRequest.getHttpRequest(), proxyServer, response)) {
                        return;
                    }

                    HttpContent chunk = (HttpContent) e;

                    boolean interrupt = false;
                    boolean last = chunk instanceof LastHttpContent;

                    if (last) {
                        LastHttpContent lastChunk = (LastHttpContent) chunk;
                        HttpHeaders trailingHeaders = lastChunk.trailingHeaders();
                        if (!trailingHeaders.isEmpty()) {
                            ResponseHeaders responseHeaders = new ResponseHeaders(future.getHttpHeaders(), trailingHeaders);
                            interrupt = handler.onHeadersReceived(responseHeaders) != STATE.CONTINUE;
                        }
                    }

                    ByteBuf buf = chunk.content();
                    try {
                        if (!interrupt && (buf.readableBytes() > 0 || last)) {
                            NettyResponseBodyPart part = nettyConfig.getBodyPartFactory().newResponseBodyPart(buf, last);
                            interrupt = updateBodyAndInterrupt(future, handler, part);
                        }
                    } finally {
                        // FIXME we shouldn't need this, should we? But a leak was reported there without it?!
                        buf.release();
                    }

                    if (interrupt || last) {
                        finishUpdate(future, channel, !last);
                    }
                }
            }
        } catch (Exception t) {
            if (hasIOExceptionFilters//
                    && t instanceof IOException//
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(future, IOException.class.cast(t), channel)) {
                return;
            }

            try {
                channels.abort(future, t);
            } catch (Exception abortException) {
                LOGGER.debug("Abort failed", abortException);
            } finally {
                finishUpdate(future, channel, false);
            }
            throw t;
        }
    }

    @Override
    public void onError(Channel channel, Throwable error) {
    }

    @Override
    public void onClose(Channel channel) {
    }
}