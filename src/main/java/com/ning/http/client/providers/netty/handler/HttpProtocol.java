/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.handler;

import static com.ning.http.client.providers.netty.util.HttpUtils.getNTLM;
import static com.ning.http.util.AsyncHttpProviderUtils.getDefaultPort;
import static com.ning.http.util.MiscUtils.isNonEmpty;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandler.STATE;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.ntlm.NTLMEngineException;
import com.ning.http.client.providers.netty.Callback;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.channel.Channels;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.NettyRequestSender;
import com.ning.http.client.providers.netty.response.NettyResponseBodyPart;
import com.ning.http.client.providers.netty.response.NettyResponseHeaders;
import com.ning.http.client.providers.netty.response.NettyResponseStatus;
import com.ning.http.client.providers.netty.spnego.SpnegoEngine;
import com.ning.http.client.uri.Uri;

import java.io.IOException;
import java.util.List;

public final class HttpProtocol extends Protocol {

    private final ConnectionStrategy connectionStrategy;

    public HttpProtocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig,
            NettyRequestSender requestSender) {
        super(channelManager, config, nettyConfig, requestSender);

        connectionStrategy = nettyConfig.getConnectionStrategy();
    }

    private Realm.RealmBuilder newRealmBuilder(Realm realm) {
        return realm != null ? new Realm.RealmBuilder().clone(realm) : new Realm.RealmBuilder();
    }

    private Realm kerberosChallenge(Channel channel,//
            List<String> proxyAuth,//
            Request request,//
            ProxyServer proxyServer,//
            FluentCaseInsensitiveStringsMap headers,//
            Realm realm,//
            NettyResponseFuture<?> future,//
            boolean proxyInd)
            throws NTLMEngineException {

        Uri uri = request.getUri();
        String host = request.getVirtualHost() == null ? uri.getHost() : request.getVirtualHost();
        String server = proxyServer == null ? host : proxyServer.getHost();
        try {
            String challengeHeader = SpnegoEngine.INSTANCE.generateToken(server);
            headers.remove(HttpHeaders.Names.AUTHORIZATION);
            headers.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);

            return newRealmBuilder(realm)//
                    .setUri(uri)//
                    .setMethodName(request.getMethod())//
                    .setScheme(Realm.AuthScheme.KERBEROS)//
                    .build();

        } catch (Throwable throwable) {
            String ntlmAuthenticate = getNTLM(proxyAuth);
            if (ntlmAuthenticate != null) {
                return ntlmChallenge(ntlmAuthenticate, request, proxyServer, headers, realm, future, proxyInd);
            }
            requestSender.abort(channel, future, throwable);
            return null;
        }
    }

    private String authorizationHeaderName(boolean proxyInd) {
        return proxyInd ? HttpHeaders.Names.PROXY_AUTHORIZATION : HttpHeaders.Names.AUTHORIZATION;
    }

    private void addNTLMAuthorizationHeader(FluentCaseInsensitiveStringsMap headers, String challengeHeader, boolean proxyInd) {
        headers.add(authorizationHeaderName(proxyInd), "NTLM " + challengeHeader);
    }

    private Realm ntlmChallenge(String wwwAuth, Request request, ProxyServer proxyServer, FluentCaseInsensitiveStringsMap headers,
            Realm realm, NettyResponseFuture<?> future, boolean proxyInd) throws NTLMEngineException {

        boolean useRealm = proxyServer == null && realm != null;

        String ntlmDomain = useRealm ? realm.getNtlmDomain() : proxyServer.getNtlmDomain();
        String ntlmHost = useRealm ? realm.getNtlmHost() : proxyServer.getHost();
        String principal = useRealm ? realm.getPrincipal() : proxyServer.getPrincipal();
        String password = useRealm ? realm.getPassword() : proxyServer.getPassword();
        Uri uri = request.getUri();

        if (wwwAuth.equals("NTLM")) {
            // server replied bare NTLM => we didn't preemptively sent Type1Msg
            String challengeHeader = NTLMEngine.INSTANCE.generateType1Msg();

            addNTLMAuthorizationHeader(headers, challengeHeader, proxyInd);
            future.getAndSetAuth(false);
            return newRealmBuilder(realm)//
                    .setScheme(realm.getAuthScheme())//
                    .setUri(uri)//
                    .setMethodName(request.getMethod())//
                    .build();

        } else {
            // probably receiving Type2Msg, so we issue Type3Msg
            addType3NTLMAuthorizationHeader(wwwAuth, headers, principal, password, ntlmDomain, ntlmHost, proxyInd);
            Realm.AuthScheme authScheme = realm != null ? realm.getAuthScheme() : Realm.AuthScheme.NTLM;
            return newRealmBuilder(realm)//
                    .setScheme(authScheme)//
                    .setUri(uri)//
                    .setMethodName(request.getMethod())//
                    .build();
        }
    }

    private Realm ntlmProxyChallenge(String wwwAuth, Request request, ProxyServer proxyServer,
            FluentCaseInsensitiveStringsMap headers, Realm realm, NettyResponseFuture<?> future, boolean proxyInd)
            throws NTLMEngineException {
        future.getAndSetAuth(false);
        headers.remove(HttpHeaders.Names.PROXY_AUTHORIZATION);

        addType3NTLMAuthorizationHeader(wwwAuth, headers, proxyServer.getPrincipal(), proxyServer.getPassword(),
                proxyServer.getNtlmDomain(), proxyServer.getHost(), proxyInd);

        return newRealmBuilder(realm)//
                // .setScheme(realm.getAuthScheme())
                .setUri(request.getUri())//
                .setMethodName(request.getMethod()).build();
    }

    private void addType3NTLMAuthorizationHeader(String auth, FluentCaseInsensitiveStringsMap headers, String username,
            String password, String domain, String workstation, boolean proxyInd) throws NTLMEngineException {
        headers.remove(authorizationHeaderName(proxyInd));

        if (isNonEmpty(auth) && auth.startsWith("NTLM ")) {
            String serverChallenge = auth.substring("NTLM ".length()).trim();
            String challengeHeader = NTLMEngine.INSTANCE.generateType3Msg(username, password, domain, workstation, serverChallenge);
            addNTLMAuthorizationHeader(headers, challengeHeader, proxyInd);
        }
    }

    private void finishUpdate(final NettyResponseFuture<?> future, Channel channel, boolean expectOtherChunks) throws IOException {

        boolean keepAlive = future.isKeepAlive();
        if (expectOtherChunks && keepAlive)
            channelManager.drainChannel(channel, future);
        else
            channelManager.tryToOfferChannelToPool(channel, keepAlive, channelManager.getPartitionId(future));
        markAsDone(future, channel);
    }

    private boolean updateBodyAndInterrupt(NettyResponseFuture<?> future, AsyncHandler<?> handler, NettyResponseBodyPart bodyPart)
            throws Exception {
        boolean interrupt = handler.onBodyPartReceived(bodyPart) != STATE.CONTINUE;
        if (bodyPart.isUnderlyingConnectionToBeClosed())
            future.setKeepAlive(false);
        return interrupt;
    }

    private void markAsDone(NettyResponseFuture<?> future, final Channel channel) {
        // We need to make sure everything is OK before adding the
        // connection back to the pool.
        try {
            future.done();
        } catch (Throwable t) {
            // Never propagate exception once we know we are done.
            logger.debug(t.getMessage(), t);
        }

        if (!future.isKeepAlive() || !channel.isConnected()) {
            channelManager.closeChannel(channel);
        }
    }

    private boolean exitAfterHandling401(//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            HttpResponse response,//
            final Request request,//
            int statusCode,//
            Realm realm,//
            ProxyServer proxyServer) throws Exception {

        if (statusCode == UNAUTHORIZED.getCode() && realm != null && !future.getAndSetAuth(true)) {

            List<String> wwwAuthHeaders = response.headers().getAll(HttpHeaders.Names.WWW_AUTHENTICATE);

            if (!wwwAuthHeaders.isEmpty()) {
                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;

                boolean negociate = wwwAuthHeaders.contains("Negotiate");
                String ntlmAuthenticate = getNTLM(wwwAuthHeaders);
                if (!wwwAuthHeaders.contains("Kerberos") && ntlmAuthenticate != null) {
                    // NTLM
                    newRealm = ntlmChallenge(ntlmAuthenticate, request, proxyServer, request.getHeaders(), realm, future, false);

                    // don't forget to reuse channel: NTLM authenticates a connection
                    future.setReuseChannel(true);

                } else if (negociate) {
                    // SPNEGO KERBEROS
                    newRealm = kerberosChallenge(channel, wwwAuthHeaders, request, proxyServer, request.getHeaders(), realm, future, false);
                    if (newRealm == null)
                        return true;
                    else
                        // don't forget to reuse channel: KERBEROS authenticates a connection
                        future.setReuseChannel(true);

                } else {
                    newRealm = new Realm.RealmBuilder()//
                            .clone(realm)//
                            .setScheme(realm.getAuthScheme())//
                            .setUri(request.getUri())//
                            .setMethodName(request.getMethod())//
                            .setUsePreemptiveAuth(true)//
                            .parseWWWAuthenticateHeader(wwwAuthHeaders.get(0))//
                            .build();
                }

                Realm nr = newRealm;
                final Request nextRequest = new RequestBuilder(future.getRequest()).setHeaders(request.getHeaders()).setRealm(nr).build();

                logger.debug("Sending authentication to {}", request.getUri());
                Callback callback = new Callback(future) {
                    public void call() throws IOException {
                        channelManager.drainChannel(channel, future);
                        requestSender.sendNextRequest(nextRequest, future);
                    }
                };

                if (future.isKeepAlive() && HttpHeaders.isTransferEncodingChunked(response))
                    // We must make sure there is no bytes left
                    // before executing the next request.
                    Channels.setAttribute(channel, callback);
                else
                    // call might crash with an IOException
                    callback.call();

                return true;
            }
        }

        return false;
    }

    private boolean exitAfterHandling100(final Channel channel, final NettyResponseFuture<?> future, int statusCode) {
        if (statusCode == CONTINUE.getCode()) {
            future.setHeadersAlreadyWrittenOnContinue(true);
            future.setDontWriteBodyBecauseExpectContinue(false);
            // FIXME why not reuse the channel?
            requestSender.writeRequest(future, channel);
            return true;

        }
        return false;
    }

    private boolean exitAfterHandling407(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode,//
            Realm realm,//
            ProxyServer proxyServer) throws Exception {

        if (statusCode == PROXY_AUTHENTICATION_REQUIRED.getCode() && realm != null && !future.getAndSetAuth(true)) {

            List<String> proxyAuthHeaders = response.headers().getAll(HttpHeaders.Names.PROXY_AUTHENTICATE);

            if (!proxyAuthHeaders.isEmpty()) {
                logger.debug("Sending proxy authentication to {}", request.getUri());

                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;
                FluentCaseInsensitiveStringsMap requestHeaders = request.getHeaders();

                boolean negociate = proxyAuthHeaders.contains("Negotiate");
                String ntlmAuthenticate = getNTLM(proxyAuthHeaders);
                if (!proxyAuthHeaders.contains("Kerberos") && ntlmAuthenticate != null) {
                    newRealm = ntlmProxyChallenge(ntlmAuthenticate, request, proxyServer, requestHeaders, realm, future, true);
                    // SPNEGO KERBEROS
                } else if (negociate) {
                    newRealm = kerberosChallenge(channel, proxyAuthHeaders, request, proxyServer, requestHeaders, realm, future, true);
                    if (newRealm == null)
                        return true;
                } else {
                    newRealm = new Realm.RealmBuilder().clone(realm)//
                            .setScheme(realm.getAuthScheme())//
                            .setUri(request.getUri())//
                            .setOmitQuery(true)//
                            .setMethodName(HttpMethod.CONNECT.getName())//
                            .setUsePreemptiveAuth(true)//
                            .parseProxyAuthenticateHeader(proxyAuthHeaders.get(0))//
                            .build();
                }

                future.setReuseChannel(true);
                future.setConnectAllowed(true);
                Request nextRequest = new RequestBuilder(future.getRequest()).setHeaders(requestHeaders).setRealm(newRealm).build();
                requestSender.sendNextRequest(nextRequest, future);
                return true;
            }
        }
        return false;
    }

    private boolean exitAfterHandlingConnect(//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            final Request request,//
            ProxyServer proxyServer,//
            int statusCode,//
            HttpRequest httpRequest) throws IOException {

        if (statusCode == OK.getCode() && httpRequest.getMethod() == HttpMethod.CONNECT) {

            if (future.isKeepAlive())
                future.attachChannel(channel, true);

            try {
                Uri requestUri = request.getUri();
                String scheme = requestUri.getScheme();
                String host = requestUri.getHost();
                int port = getDefaultPort(requestUri);

                logger.debug("Connecting to proxy {} for scheme {}", proxyServer, scheme);
                channelManager.upgradeProtocol(channel.getPipeline(), scheme, host, port);

            } catch (Throwable ex) {
                requestSender.abort(channel, future, ex);
            }

            future.setReuseChannel(true);
            future.setConnectAllowed(false);
            requestSender.sendNextRequest(new RequestBuilder(future.getRequest()).build(), future);
            return true;
        }

        return false;
    }

    private boolean exitAfterHandlingStatus(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler, NettyResponseStatus status) throws IOException, Exception {
        if (!future.getAndSetStatusReceived(true) && handler.onStatusReceived(status) != STATE.CONTINUE) {
            finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
        return false;
    }

    private boolean exitAfterHandlingHeaders(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler, NettyResponseHeaders responseHeaders) throws IOException, Exception {
        if (!response.headers().isEmpty() && handler.onHeadersReceived(responseHeaders) != STATE.CONTINUE) {
            finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
        return false;
    }

    // Netty 3: if the response is not chunked, the full body comes with the response
    private boolean exitAfterHandlingBody(Channel channel, NettyResponseFuture<?> future, HttpResponse response,
            AsyncHandler<?> handler) throws Exception {
        if (!response.isChunked()) {
            // no chunks expected, exiting
            if (response.getContent().readableBytes() > 0)
                // FIXME no need to notify an empty bodypart?
                updateBodyAndInterrupt(future, handler, new NettyResponseBodyPart(response, null, true));
            finishUpdate(future, channel, false);
            return true;
        }
        return false;
    }

    private boolean handleHttpResponse(final HttpResponse response,//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            AsyncHandler<?> handler) throws Exception {

        HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
        ProxyServer proxyServer = future.getProxyServer();
        logger.debug("\n\nRequest {}\n\nResponse {}\n", httpRequest, response);

        // store the original headers so we can re-send all them to
        // the handler in case of trailing headers
        future.setHttpHeaders(response.headers());

        future.setKeepAlive(connectionStrategy.keepAlive(httpRequest, response));

        NettyResponseStatus status = new NettyResponseStatus(future.getUri(), config, response);
        int statusCode = response.getStatus().getCode();
        Request request = future.getRequest();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        NettyResponseHeaders responseHeaders = new NettyResponseHeaders(response.headers());

        return exitAfterProcessingFilters(channel, future, handler, status, responseHeaders)
                || exitAfterHandling401(channel, future, response, request, statusCode, realm, proxyServer) || //
                exitAfterHandling407(channel, future, response, request, statusCode, realm, proxyServer) || //
                exitAfterHandling100(channel, future, statusCode) || //
                exitAfterHandlingRedirect(channel, future, response, request, statusCode) || //
                exitAfterHandlingConnect(channel, future, request, proxyServer, statusCode, httpRequest) || //
                exitAfterHandlingStatus(channel, future, response, handler, status) || //
                exitAfterHandlingHeaders(channel, future, response, handler, responseHeaders) || //
                exitAfterHandlingBody(channel, future, response, handler);
    }

    private void handleChunk(HttpChunk chunk,//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            AsyncHandler<?> handler) throws IOException, Exception {

        boolean last = chunk.isLast();
        // we don't notify updateBodyAndInterrupt with the last chunk as it's empty
        if (last || updateBodyAndInterrupt(future, handler, new NettyResponseBodyPart(null, chunk, last))) {

            // only possible if last is true
            if (chunk instanceof HttpChunkTrailer) {
                HttpChunkTrailer chunkTrailer = (HttpChunkTrailer) chunk;
                if (!chunkTrailer.trailingHeaders().isEmpty()) {
                    NettyResponseHeaders responseHeaders = new NettyResponseHeaders(future.getHttpHeaders(), chunkTrailer.trailingHeaders());
                    handler.onHeadersReceived(responseHeaders);
                }
            }
            finishUpdate(future, channel, !chunk.isLast());
        }
    }

    @Override
    public void handle(final Channel channel, final NettyResponseFuture<?> future, final Object e) throws Exception {

        future.touch();

        // future is already done because of an exception or a timeout
        if (future.isDone()) {
            // FIXME isn't the channel already properly closed?
            channelManager.closeChannel(channel);
            return;
        }

        AsyncHandler<?> handler = future.getAsyncHandler();
        try {
            if (e instanceof HttpResponse) {
                if (handleHttpResponse((HttpResponse) e, channel, future, handler))
                    return;

            } else if (e instanceof HttpChunk)
                handleChunk((HttpChunk) e, channel, future, handler);

        } catch (Exception t) {
            // e.g. an IOException when trying to open a connection and send the next request
            if (hasIOExceptionFilters//
                    && t instanceof IOException//
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(future, IOException.class.cast(t), channel)) {
                return;
            }

            // FIXME Weird: close channel in abort, then close again
            try {
                requestSender.abort(channel, future, t);
            } catch (Exception abortException) {
                logger.debug("Abort failed", abortException);
            } finally {
                finishUpdate(future, channel, false);
            }
            throw t;
        }
    }

    public void onError(NettyResponseFuture<?> future, Throwable e) {
    }

    public void onClose(NettyResponseFuture<?> future) {
    }
}
