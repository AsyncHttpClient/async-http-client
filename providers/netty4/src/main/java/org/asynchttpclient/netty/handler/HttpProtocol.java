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
package org.asynchttpclient.netty.handler;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.asynchttpclient.ntlm.NtlmUtils.getNTLM;
import static org.asynchttpclient.util.AsyncHttpProviderUtils.getExplicitPort;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.channel.pool.ConnectionStrategy;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.asynchttpclient.netty.Callback;
import org.asynchttpclient.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.netty.NettyResponseBodyPart;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.NettyResponseHeaders;
import org.asynchttpclient.netty.NettyResponseStatus;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.spnego.SpnegoEngineException;
import org.asynchttpclient.uri.Uri;

public final class HttpProtocol extends Protocol {

    private final ConnectionStrategy<HttpRequest, HttpResponse> connectionStrategy;

    public HttpProtocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyAsyncHttpProviderConfig nettyConfig, NettyRequestSender requestSender) {
        super(channelManager, config, nettyConfig, requestSender);

        connectionStrategy = nettyConfig.getConnectionStrategy();
    }

    private Realm kerberosChallenge(Channel channel,//
            List<String> authHeaders,//
            Request request,//
            FluentCaseInsensitiveStringsMap headers,//
            Realm realm,//
            NettyResponseFuture<?> future) {

        Uri uri = request.getUri();
        String host = request.getVirtualHost() == null ? uri.getHost() : request.getVirtualHost();
        try {
            String challengeHeader = SpnegoEngine.instance().generateToken(host);
            headers.remove(HttpHeaders.Names.AUTHORIZATION);
            headers.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);

            return new Realm.RealmBuilder().clone(realm)//
                    .setUri(uri)//
                    .setMethodName(request.getMethod())//
                    .setScheme(Realm.AuthScheme.KERBEROS)//
                    .build();


        } catch (SpnegoEngineException throwable) {
            String ntlmAuthenticate = getNTLM(authHeaders);
            if (ntlmAuthenticate != null) {
                return ntlmChallenge(ntlmAuthenticate, request, headers, realm, future);
            }
            requestSender.abort(channel, future, throwable);
            return null;
        }
    }

    private Realm kerberosProxyChallenge(Channel channel,//
            List<String> proxyAuth,//
            Request request,//
            ProxyServer proxyServer,//
            FluentCaseInsensitiveStringsMap headers,//
            NettyResponseFuture<?> future) {

        try {
            String challengeHeader = SpnegoEngine.instance().generateToken(proxyServer.getHost());
            headers.remove(HttpHeaders.Names.AUTHORIZATION);
            headers.add(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);

            return proxyServer.realmBuilder()//
                    .setUri(request.getUri())//
                    .setMethodName(request.getMethod())//
                    .setScheme(Realm.AuthScheme.KERBEROS)//
                    .build();

        } catch (SpnegoEngineException throwable) {
            String ntlmAuthenticate = getNTLM(proxyAuth);
            if (ntlmAuthenticate != null) {
                return ntlmProxyChallenge(ntlmAuthenticate, request, proxyServer, headers, future);
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

    private Realm ntlmChallenge(String authenticateHeader,//
            Request request,//
            FluentCaseInsensitiveStringsMap headers,//
            Realm realm,//
            NettyResponseFuture<?> future) {

        if (authenticateHeader.equals("NTLM")) {
            // server replied bare NTLM => we didn't preemptively sent Type1Msg
            String challengeHeader = NtlmEngine.INSTANCE.generateType1Msg();

            addNTLMAuthorizationHeader(headers, challengeHeader, false);
            future.getAndSetAuth(false);

        } else {
            addType3NTLMAuthorizationHeader(authenticateHeader, headers, realm, false);
        }
 
        return new Realm.RealmBuilder().clone(realm)//
                .setUri(request.getUri())//
                .setMethodName(request.getMethod())//
                .build();
    }

    private Realm ntlmProxyChallenge(String authenticateHeader,//
            Request request,//
            ProxyServer proxyServer,//
            FluentCaseInsensitiveStringsMap headers,//
            NettyResponseFuture<?> future) {

        future.getAndSetAuth(false);
        headers.remove(HttpHeaders.Names.PROXY_AUTHORIZATION);

        Realm realm = proxyServer.realmBuilder()//
                .setScheme(AuthScheme.NTLM)//
                .setUri(request.getUri())//
                .setMethodName(request.getMethod()).build();
        
        addType3NTLMAuthorizationHeader(authenticateHeader, headers, realm, true);

        return realm;
    }

    private void addType3NTLMAuthorizationHeader(String authenticateHeader, FluentCaseInsensitiveStringsMap headers, Realm realm,
            boolean proxyInd) {
        headers.remove(authorizationHeaderName(proxyInd));

        if (authenticateHeader.startsWith("NTLM ")) {
            String serverChallenge = authenticateHeader.substring("NTLM ".length()).trim();
            String challengeHeader = NtlmEngine.INSTANCE.generateType3Msg(realm.getPrincipal(), realm.getPassword(), realm.getNtlmDomain(), realm.getNtlmHost(), serverChallenge);
            addNTLMAuthorizationHeader(headers, challengeHeader, proxyInd);
        }
    }

    private void finishUpdate(final NettyResponseFuture<?> future, Channel channel, boolean expectOtherChunks) throws IOException {

        future.cancelTimeouts();

        boolean keepAlive = future.isKeepAlive();
        if (expectOtherChunks && keepAlive)
            channelManager.drainChannelAndOffer(channel, future);
        else
            channelManager.tryToOfferChannelToPool(channel, future.getAsyncHandler(), keepAlive, future.getPartitionKey());

        try {
            future.done();
        } catch (Exception t) {
            // Never propagate exception once we know we are done.
            logger.debug(t.getMessage(), t);
        }
    }

    private boolean updateBodyAndInterrupt(NettyResponseFuture<?> future, AsyncHandler<?> handler, NettyResponseBodyPart bodyPart) throws Exception {
        boolean interrupt = handler.onBodyPartReceived(bodyPart) != State.CONTINUE;
        if (bodyPart.isUnderlyingConnectionToBeClosed())
            future.setKeepAlive(false);
        return interrupt;
    }

    private boolean exitAfterHandling401(//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            HttpResponse response,//
            final Request request,//
            int statusCode,//
            Realm realm,//
            ProxyServer proxyServer) throws Exception {

        if (statusCode == UNAUTHORIZED.code() && realm != null && !future.getAndSetAuth(true)) {

            List<String> wwwAuthHeaders = response.headers().getAll(HttpHeaders.Names.WWW_AUTHENTICATE);

            if (!wwwAuthHeaders.isEmpty()) {
                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;
                boolean negociate = wwwAuthHeaders.contains("Negotiate");
                String ntlmAuthenticate = getNTLM(wwwAuthHeaders);
                if (!wwwAuthHeaders.contains("Kerberos") && ntlmAuthenticate != null) {
                    // NTLM
                    newRealm = ntlmChallenge(ntlmAuthenticate, request, request.getHeaders(), realm, future);

                } else if (negociate) {
                    newRealm = kerberosChallenge(channel, wwwAuthHeaders, request, request.getHeaders(), realm, future);
                    // SPNEGO KERBEROS
                    if (newRealm == null)
                        return true;

                } else {
                    // BASIC or DIGEST
                    newRealm = new Realm.RealmBuilder()//
                            .clone(realm)//
                            .setUri(request.getUri())//
                            .setMethodName(request.getMethod())//
                            .setUsePreemptiveAuth(true)//
                            .parseWWWAuthenticateHeader(wwwAuthHeaders.get(0))//
                            .build();
                }

                final Request nextRequest = new RequestBuilder(future.getRequest()).setHeaders(request.getHeaders()).setRealm(newRealm).build();

                logger.debug("Sending authentication to {}", request.getUri());
                if (future.isKeepAlive() && !HttpHeaders.isTransferEncodingChunked(response)) {
                    future.setReuseChannel(true);
                    requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
                } else {
                    channelManager.closeChannel(channel);
                    requestSender.sendNextRequest(nextRequest, future);
                }

                return true;
            }
        }

        return false;
    }

    private boolean exitAfterHandling100(final Channel channel, final NettyResponseFuture<?> future, int statusCode) {
        if (statusCode == CONTINUE.code()) {
            future.setHeadersAlreadyWrittenOnContinue(true);
            future.setDontWriteBodyBecauseExpectContinue(false);
            // directly send the body
            Channels.setAttribute(channel, new Callback(future) {
                @Override
                public void call() throws IOException {
                    Channels.setAttribute(channel, future);
                    requestSender.writeRequest(future, channel);
                }
            });
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

        if (statusCode == PROXY_AUTHENTICATION_REQUIRED.code() && realm != null && !future.getAndSetAuth(true)) {

            List<String> proxyAuthHeaders = response.headers().getAll(HttpHeaders.Names.PROXY_AUTHENTICATE);

            if (!proxyAuthHeaders.isEmpty()) {
                logger.debug("Sending proxy authentication to {}", request.getUri());

                future.setState(NettyResponseFuture.STATE.NEW);
                Realm newRealm = null;
                FluentCaseInsensitiveStringsMap requestHeaders = request.getHeaders();

                boolean negociate = proxyAuthHeaders.contains("Negotiate");
                String ntlmAuthenticate = getNTLM(proxyAuthHeaders);
                if (!proxyAuthHeaders.contains("Kerberos") && ntlmAuthenticate != null) {
                    // NTLM
                    newRealm = ntlmProxyChallenge(ntlmAuthenticate, request, proxyServer, requestHeaders, future);

                } else if (negociate) {
                    // SPNEGO KERBEROS
                    newRealm = kerberosProxyChallenge(channel, proxyAuthHeaders, request, proxyServer, requestHeaders, future);
                    if (newRealm == null)
                        return true;

                } else {
                    // BASIC or DIGEST
                    newRealm = new Realm.RealmBuilder().clone(realm)//
                            .setUri(request.getUri())//
                            .setOmitQuery(true)//
                            .setMethodName(request.getMethod())//
                            .setUsePreemptiveAuth(true)//
                            .parseProxyAuthenticateHeader(proxyAuthHeaders.get(0))//
                            .build();
                }

                final Request nextRequest = new RequestBuilder(future.getRequest()).setHeaders(request.getHeaders()).setRealm(newRealm).build();

                logger.debug("Sending proxy authentication to {}", request.getUri());
                if (future.isKeepAlive() && !HttpHeaders.isTransferEncodingChunked(response)) {
                    future.setConnectAllowed(true);
                    future.setReuseChannel(true);
                    requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
                } else {
                    channelManager.closeChannel(channel);
                    requestSender.sendNextRequest(nextRequest, future);
                }

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

        if (statusCode == OK.code() && httpRequest.getMethod() == HttpMethod.CONNECT) {

            if (future.isKeepAlive())
                future.attachChannel(channel, true);
            
            Uri requestUri = request.getUri();
            String scheme = requestUri.getScheme();
            String host = requestUri.getHost();
            int port = getExplicitPort(requestUri);

            logger.debug("Connecting to proxy {} for scheme {}", proxyServer, scheme);
                
            try {
                channelManager.upgradeProtocol(channel.pipeline(), scheme, host, port);
                future.setReuseChannel(true);
                future.setConnectAllowed(false);
                requestSender.drainChannelAndExecuteNextRequest(channel, future, new RequestBuilder(future.getRequest()).build());

            } catch (GeneralSecurityException ex) {
                requestSender.abort(channel, future, ex);
            }

            return true;
        }

        return false;
    }

    private boolean exitAfterHandlingStatus(Channel channel, NettyResponseFuture<?> future, HttpResponse response, AsyncHandler<?> handler, NettyResponseStatus status)
            throws IOException, Exception {
        if (!future.getAndSetStatusReceived(true) && handler.onStatusReceived(status) != State.CONTINUE) {
            finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
        return false;
    }

    private boolean exitAfterHandlingHeaders(Channel channel, NettyResponseFuture<?> future, HttpResponse response, AsyncHandler<?> handler, NettyResponseHeaders responseHeaders)
            throws IOException, Exception {
        if (!response.headers().isEmpty() && handler.onHeadersReceived(responseHeaders) != State.CONTINUE) {
            finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
        return false;
    }

    private boolean exitAfterHandlingReactiveStreams(Channel channel, NettyResponseFuture<?> future, HttpResponse response, AsyncHandler<?> handler) throws IOException {
        if (handler instanceof StreamedAsyncHandler) {
            StreamedAsyncHandler<?> streamedAsyncHandler = (StreamedAsyncHandler<?>) handler;
            StreamedResponsePublisher publisher = new StreamedResponsePublisher(channel.eventLoop(), channelManager, future, channel);
            channel.pipeline().addLast(channel.eventLoop(), "streamedAsyncHandler", publisher);
            Channels.setAttribute(channel, publisher);
            if (streamedAsyncHandler.onStream(publisher) != State.CONTINUE) {
                finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(response));
                return true;
            }
        }
        return false;
    }

    private boolean handleHttpResponse(final HttpResponse response, final Channel channel, final NettyResponseFuture<?> future, AsyncHandler<?> handler) throws Exception {

        HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
        ProxyServer proxyServer = future.getProxyServer();
        logger.debug("\n\nRequest {}\n\nResponse {}\n", httpRequest, response);

        // store the original headers so we can re-send all them to
        // the handler in case of trailing headers
        future.setHttpHeaders(response.headers());

        future.setKeepAlive(connectionStrategy.keepAlive(httpRequest, response));

        NettyResponseStatus status = new NettyResponseStatus(future.getUri(), config, response, channel);
        int statusCode = response.getStatus().code();
        Request request = future.getRequest();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        NettyResponseHeaders responseHeaders = new NettyResponseHeaders(response.headers());

        return exitAfterProcessingFilters(channel, future, handler, status, responseHeaders)
                || exitAfterHandling401(channel, future, response, request, statusCode, realm, proxyServer) || //
                exitAfterHandling407(channel, future, response, request, statusCode, realm, proxyServer) || //
                exitAfterHandling100(channel, future, statusCode) || //
                exitAfterHandlingRedirect(channel, future, response, request, statusCode, realm) || //
                exitAfterHandlingConnect(channel, future, request, proxyServer, statusCode, httpRequest) || //
                exitAfterHandlingStatus(channel, future, response, handler, status) || //
                exitAfterHandlingHeaders(channel, future, response, handler, responseHeaders) ||
                exitAfterHandlingReactiveStreams(channel, future, response, handler);
    }

    private void handleChunk(HttpContent chunk,//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            AsyncHandler<?> handler) throws IOException, Exception {

        boolean interrupt = false;
        boolean last = chunk instanceof LastHttpContent;
        
        // Netty 4: the last chunk is not empty
        if (last) {
            LastHttpContent lastChunk = (LastHttpContent) chunk;
            HttpHeaders trailingHeaders = lastChunk.trailingHeaders();
            if (!trailingHeaders.isEmpty()) {
                NettyResponseHeaders responseHeaders = new NettyResponseHeaders(future.getHttpHeaders(), trailingHeaders);
                interrupt = handler.onHeadersReceived(responseHeaders) != State.CONTINUE;
            }
        }

        ByteBuf buf = chunk.content();
        try {
            if (!interrupt && !(handler instanceof StreamedAsyncHandler) && (buf.readableBytes() > 0 || last)) {
                NettyResponseBodyPart part = nettyConfig.getBodyPartFactory().newResponseBodyPart(buf, last);
                interrupt = updateBodyAndInterrupt(future, handler, part);
            }
        } finally {
            buf.release();
        }

        if (interrupt || last)
            finishUpdate(future, channel, !last);
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

            } else if (e instanceof HttpContent) {
                handleChunk((HttpContent) e, channel, future, handler);
            }
        } catch (Exception t) {
            // e.g. an IOException when trying to open a connection and send the next request
            if (hasIOExceptionFilters//
                    && t instanceof IOException//
                    && requestSender.applyIoExceptionFiltersAndReplayRequest(future, IOException.class.cast(t), channel)) {
                return;
            }

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

    @Override
    public void onError(NettyResponseFuture<?> future, Throwable error) {
    }

    @Override
    public void onClose(NettyResponseFuture<?> future) {
    }
}
