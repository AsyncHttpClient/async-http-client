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

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.asynchttpclient.Dsl.realm;
import static org.asynchttpclient.util.AuthenticatorUtils.getHeaderWithPrefix;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.util.List;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHandler.State;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.asynchttpclient.netty.Callback;
import org.asynchttpclient.netty.NettyResponseBodyPart;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.NettyResponseHeaders;
import org.asynchttpclient.netty.NettyResponseStatus;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.spnego.SpnegoEngineException;
import org.asynchttpclient.uri.Uri;

public final class HttpProtocol extends Protocol {

    public HttpProtocol(ChannelManager channelManager, AsyncHttpClientConfig config, NettyRequestSender requestSender) {
        super(channelManager, config, requestSender);
    }

    private void kerberosChallenge(Channel channel,//
            List<String> authHeaders,//
            Request request,//
            HttpHeaders headers,//
            Realm realm,//
            NettyResponseFuture<?> future) throws SpnegoEngineException {

        Uri uri = request.getUri();
        String host = request.getVirtualHost() == null ? uri.getHost() : request.getVirtualHost();
        String challengeHeader = SpnegoEngine.instance().generateToken(host);
        headers.set(HttpHeaders.Names.AUTHORIZATION, "Negotiate " + challengeHeader);
    }

    private void kerberosProxyChallenge(Channel channel,//
            List<String> proxyAuth,//
            Request request,//
            ProxyServer proxyServer,//
            Realm proxyRealm,//
            HttpHeaders headers,//
            NettyResponseFuture<?> future) throws SpnegoEngineException {

        String challengeHeader = SpnegoEngine.instance().generateToken(proxyServer.getHost());
        headers.set(HttpHeaders.Names.PROXY_AUTHORIZATION, "Negotiate " + challengeHeader);
    }

    private void ntlmChallenge(String authenticateHeader,//
            Request request,//
            HttpHeaders headers,//
            Realm realm,//
            NettyResponseFuture<?> future) {

        if (authenticateHeader.equals("NTLM")) {
            // server replied bare NTLM => we didn't preemptively sent Type1Msg
            String challengeHeader = NtlmEngine.INSTANCE.generateType1Msg();
            // FIXME we might want to filter current NTLM and add (leave other
            // Authorization headers untouched)
            headers.set(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);
            future.getInAuth().set(false);

        } else {
            String serverChallenge = authenticateHeader.substring("NTLM ".length()).trim();
            String challengeHeader = NtlmEngine.INSTANCE.generateType3Msg(realm.getPrincipal(), realm.getPassword(), realm.getNtlmDomain(), realm.getNtlmHost(), serverChallenge);
            // FIXME we might want to filter current NTLM and add (leave other
            // Authorization headers untouched)
            headers.set(HttpHeaders.Names.AUTHORIZATION, "NTLM " + challengeHeader);
        }
    }

    private void ntlmProxyChallenge(String authenticateHeader,//
            Request request,//
            Realm proxyRealm,//
            HttpHeaders headers,//
            NettyResponseFuture<?> future) {

        if (authenticateHeader.equals("NTLM")) {
            // server replied bare NTLM => we didn't preemptively sent Type1Msg
            String challengeHeader = NtlmEngine.INSTANCE.generateType1Msg();
            // FIXME we might want to filter current NTLM and add (leave other
            // Authorization headers untouched)
            headers.set(HttpHeaders.Names.PROXY_AUTHORIZATION, "NTLM " + challengeHeader);
            future.getInProxyAuth().set(false);

        } else {
            String serverChallenge = authenticateHeader.substring("NTLM ".length()).trim();
            String challengeHeader = NtlmEngine.INSTANCE.generateType3Msg(proxyRealm.getPrincipal(), proxyRealm.getPassword(), proxyRealm.getNtlmDomain(),
                    proxyRealm.getNtlmHost(), serverChallenge);
            // FIXME we might want to filter current NTLM and add (leave other
            // Authorization headers untouched)
            headers.set(HttpHeaders.Names.PROXY_AUTHORIZATION, "NTLM " + challengeHeader);
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
        if (interrupt)
            future.setKeepAlive(false);
        return interrupt;
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

    private boolean exitAfterHandling401(//
            final Channel channel,//
            final NettyResponseFuture<?> future,//
            HttpResponse response,//
            final Request request,//
            int statusCode,//
            Realm realm,//
            ProxyServer proxyServer,//
            HttpRequest httpRequest) {

        if (statusCode != UNAUTHORIZED.code())
            return false;

        if (realm == null) {
            logger.info("Can't handle 401 as there's no realm");
            return false;
        }

        if (future.getInAuth().getAndSet(true)) {
            logger.info("Can't handle 401 as auth was already performed");
            return false;
        }

        List<String> wwwAuthHeaders = response.headers().getAll(HttpHeaders.Names.WWW_AUTHENTICATE);

        if (wwwAuthHeaders.isEmpty()) {
            logger.info("Can't handle 401 as response doesn't contain WWW-Authenticate headers");
            return false;
        }

        // FIXME what's this???
        future.setChannelState(ChannelState.NEW);
        HttpHeaders requestHeaders = new DefaultHttpHeaders().add(request.getHeaders());

        switch (realm.getScheme()) {
        case BASIC:
            if (getHeaderWithPrefix(wwwAuthHeaders, "Basic") == null) {
                logger.info("Can't handle 401 with Basic realm as WWW-Authenticate headers don't match");
                return false;
            }

            if (realm.isUsePreemptiveAuth()) {
                // FIXME do we need this, as future.getAndSetAuth
                // was tested above?
                // auth was already performed, most likely auth
                // failed
                logger.info("Can't handle 401 with Basic realm as auth was preemptive and already performed");
                return false;
            }

            // FIXME do we want to update the realm, or directly
            // set the header?
            Realm newBasicRealm = realm(realm)//
                    .setUsePreemptiveAuth(true)//
                    .build();
            future.setRealm(newBasicRealm);
            break;

        case DIGEST:
            String digestHeader = getHeaderWithPrefix(wwwAuthHeaders, "Digest");
            if (digestHeader == null) {
                logger.info("Can't handle 401 with Digest realm as WWW-Authenticate headers don't match");
                return false;
            }
            Realm newDigestRealm = realm(realm)//
                    .setUri(request.getUri())//
                    .setMethodName(request.getMethod())//
                    .setUsePreemptiveAuth(true)//
                    .parseWWWAuthenticateHeader(digestHeader)//
                    .build();
            future.setRealm(newDigestRealm);
            break;

        case NTLM:
            String ntlmHeader = getHeaderWithPrefix(wwwAuthHeaders, "NTLM");
            if (ntlmHeader == null) {
                logger.info("Can't handle 401 with NTLM realm as WWW-Authenticate headers don't match");
                return false;
            }

            ntlmChallenge(ntlmHeader, request, requestHeaders, realm, future);
            Realm newNtlmRealm = realm(realm)//
                    .setUsePreemptiveAuth(true)//
                    .build();
            future.setRealm(newNtlmRealm);
            break;

        case KERBEROS:
        case SPNEGO:
            if (getHeaderWithPrefix(wwwAuthHeaders, "Negociate") == null) {
                logger.info("Can't handle 401 with Kerberos or Spnego realm as WWW-Authenticate headers don't match");
                return false;
            }
            try {
                kerberosChallenge(channel, wwwAuthHeaders, request, requestHeaders, realm, future);

            } catch (SpnegoEngineException e) {
                // FIXME
                String ntlmHeader2 = getHeaderWithPrefix(wwwAuthHeaders, "NTLM");
                if (ntlmHeader2 != null) {
                    logger.warn("Kerberos/Spnego auth failed, proceeding with NTLM");
                    ntlmChallenge(ntlmHeader2, request, requestHeaders, realm, future);
                    Realm newNtlmRealm2 = realm(realm)//
                            .setScheme(AuthScheme.NTLM)//
                            .setUsePreemptiveAuth(true)//
                            .build();
                    future.setRealm(newNtlmRealm2);
                } else {
                    requestSender.abort(channel, future, e);
                    return false;
                }
            }
            break;
        default:
            throw new IllegalStateException("Invalid Authentication scheme " + realm.getScheme());
        }

        final Request nextRequest = new RequestBuilder(future.getCurrentRequest()).setHeaders(requestHeaders).build();

        logger.debug("Sending authentication to {}", request.getUri());
        if (future.isKeepAlive() && !HttpHeaders.isTransferEncodingChunked(httpRequest) && !HttpHeaders.isTransferEncodingChunked(response)) {
            future.setReuseChannel(true);
            requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
        } else {
            channelManager.closeChannel(channel);
            requestSender.sendNextRequest(nextRequest, future);
        }

        return true;
    }

    private boolean exitAfterHandling407(//
            Channel channel,//
            NettyResponseFuture<?> future,//
            HttpResponse response,//
            Request request,//
            int statusCode,//
            ProxyServer proxyServer,//
            HttpRequest httpRequest) {

        if (statusCode != PROXY_AUTHENTICATION_REQUIRED.code())
            return false;

        if (future.getInProxyAuth().getAndSet(true)) {
            logger.info("Can't handle 407 as auth was already performed");
            return false;
        }

        Realm proxyRealm = future.getProxyRealm();

        if (proxyRealm == null) {
            logger.info("Can't handle 407 as there's no proxyRealm");
            return false;
        }

        List<String> proxyAuthHeaders = response.headers().getAll(HttpHeaders.Names.PROXY_AUTHENTICATE);

        if (proxyAuthHeaders.isEmpty()) {
            logger.info("Can't handle 407 as response doesn't contain Proxy-Authenticate headers");
            return false;
        }

        // FIXME what's this???
        future.setChannelState(ChannelState.NEW);
        HttpHeaders requestHeaders = new DefaultHttpHeaders().add(request.getHeaders());

        switch (proxyRealm.getScheme()) {
        case BASIC:
            if (getHeaderWithPrefix(proxyAuthHeaders, "Basic") == null) {
                logger.info("Can't handle 407 with Basic realm as Proxy-Authenticate headers don't match");
                return false;
            }

            if (proxyRealm.isUsePreemptiveAuth()) {
                // FIXME do we need this, as future.getAndSetAuth
                // was tested above?
                // auth was already performed, most likely auth
                // failed
                logger.info("Can't handle 407 with Basic realm as auth was preemptive and already performed");
                return false;
            }

            // FIXME do we want to update the realm, or directly
            // set the header?
            Realm newBasicRealm = realm(proxyRealm)//
                    .setUsePreemptiveAuth(true)//
                    .build();
            future.setProxyRealm(newBasicRealm);
            break;

        case DIGEST:
            String digestHeader = getHeaderWithPrefix(proxyAuthHeaders, "Digest");
            if (digestHeader == null) {
                logger.info("Can't handle 407 with Digest realm as Proxy-Authenticate headers don't match");
                return false;
            }
            Realm newDigestRealm = realm(proxyRealm)//
                    .setUri(request.getUri())//
                    .setMethodName(request.getMethod())//
                    .setUsePreemptiveAuth(true)//
                    .parseProxyAuthenticateHeader(digestHeader)//
                    .build();
            future.setProxyRealm(newDigestRealm);
            break;

        case NTLM:
            String ntlmHeader = getHeaderWithPrefix(proxyAuthHeaders, "NTLM");
            if (ntlmHeader == null) {
                logger.info("Can't handle 407 with NTLM realm as Proxy-Authenticate headers don't match");
                return false;
            }
            ntlmProxyChallenge(ntlmHeader, request, proxyRealm, requestHeaders, future);
            Realm newNtlmRealm = realm(proxyRealm)//
                    .setUsePreemptiveAuth(true)//
                    .build();
            future.setProxyRealm(newNtlmRealm);
            break;

        case KERBEROS:
        case SPNEGO:
            if (getHeaderWithPrefix(proxyAuthHeaders, "Negociate") == null) {
                logger.info("Can't handle 407 with Kerberos or Spnego realm as Proxy-Authenticate headers don't match");
                return false;
            }
            try {
                kerberosProxyChallenge(channel, proxyAuthHeaders, request, proxyServer, proxyRealm, requestHeaders, future);

            } catch (SpnegoEngineException e) {
                // FIXME
                String ntlmHeader2 = getHeaderWithPrefix(proxyAuthHeaders, "NTLM");
                if (ntlmHeader2 != null) {
                    logger.warn("Kerberos/Spnego proxy auth failed, proceeding with NTLM");
                    ntlmChallenge(ntlmHeader2, request, requestHeaders, proxyRealm, future);
                    Realm newNtlmRealm2 = realm(proxyRealm)//
                            .setScheme(AuthScheme.NTLM)//
                            .setUsePreemptiveAuth(true)//
                            .build();
                    future.setProxyRealm(newNtlmRealm2);
                } else {
                    requestSender.abort(channel, future, e);
                    return false;
                }
            }
            break;
        default:
            throw new IllegalStateException("Invalid Authentication scheme " + proxyRealm.getScheme());
        }

        RequestBuilder nextRequestBuilder = new RequestBuilder(future.getCurrentRequest()).setHeaders(requestHeaders);
        if (future.getCurrentRequest().getUri().isSecured()) {
            nextRequestBuilder.setMethod(HttpMethod.CONNECT.name());
        }
        final Request nextRequest = nextRequestBuilder.build();

        logger.debug("Sending proxy authentication to {}", request.getUri());
        if (future.isKeepAlive() && !HttpHeaders.isTransferEncodingChunked(httpRequest) && !HttpHeaders.isTransferEncodingChunked(response)) {
            future.setConnectAllowed(true);
            future.setReuseChannel(true);
            requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
        } else {
            channelManager.closeChannel(channel);
            requestSender.sendNextRequest(nextRequest, future);
        }

        return true;
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
            logger.debug("Connecting to proxy {} for scheme {}", proxyServer, requestUri.getScheme());

            channelManager.upgradeProtocol(channel.pipeline(), requestUri);
            future.setReuseChannel(true);
            future.setConnectAllowed(false);
            requestSender.drainChannelAndExecuteNextRequest(channel, future, new RequestBuilder(future.getTargetRequest()).build());

            return true;
        }

        return false;
    }

    private boolean exitAfterHandlingStatus(Channel channel, NettyResponseFuture<?> future, HttpResponse response, AsyncHandler<?> handler, NettyResponseStatus status, HttpRequest httpRequest)
            throws IOException, Exception {
        if (!future.getAndSetStatusReceived(true) && handler.onStatusReceived(status) != State.CONTINUE) {
            finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(httpRequest) || HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
        return false;
    }

    private boolean exitAfterHandlingHeaders(Channel channel, NettyResponseFuture<?> future, HttpResponse response, AsyncHandler<?> handler, NettyResponseHeaders responseHeaders, HttpRequest httpRequest)
            throws IOException, Exception {
        if (!response.headers().isEmpty() && handler.onHeadersReceived(responseHeaders) != State.CONTINUE) {
            finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(httpRequest) ||  HttpHeaders.isTransferEncodingChunked(response));
            return true;
        }
        return false;
    }

    private boolean exitAfterHandlingReactiveStreams(Channel channel, NettyResponseFuture<?> future, HttpResponse response, AsyncHandler<?> handler, HttpRequest httpRequest) throws IOException {
        if (handler instanceof StreamedAsyncHandler) {
            StreamedAsyncHandler<?> streamedAsyncHandler = (StreamedAsyncHandler<?>) handler;
            StreamedResponsePublisher publisher = new StreamedResponsePublisher(channel.eventLoop(), channelManager, future, channel);
            channel.pipeline().addLast(channel.eventLoop(), "streamedAsyncHandler", publisher);
            Channels.setAttribute(channel, publisher);
            if (streamedAsyncHandler.onStream(publisher) != State.CONTINUE) {
                finishUpdate(future, channel, HttpHeaders.isTransferEncodingChunked(httpRequest) ||  HttpHeaders.isTransferEncodingChunked(response));
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

        future.setKeepAlive(config.getKeepAliveStrategy().keepAlive(future.getTargetRequest(), httpRequest, response));

        NettyResponseStatus status = new NettyResponseStatus(future.getUri(), config, response, channel);
        int statusCode = response.getStatus().code();
        Request request = future.getCurrentRequest();
        Realm realm = request.getRealm() != null ? request.getRealm() : config.getRealm();
        NettyResponseHeaders responseHeaders = new NettyResponseHeaders(response.headers());

        return exitAfterProcessingFilters(channel, future, handler, status, responseHeaders) || //
                exitAfterHandling401(channel, future, response, request, statusCode, realm, proxyServer, httpRequest) || //
                exitAfterHandling407(channel, future, response, request, statusCode, proxyServer, httpRequest) || //
                exitAfterHandling100(channel, future, statusCode) || //
                exitAfterHandlingRedirect(channel, future, response, request, statusCode, realm) || //
                exitAfterHandlingConnect(channel, future, request, proxyServer, statusCode, httpRequest) || //
                exitAfterHandlingStatus(channel, future, response, handler, status, httpRequest) || //
                exitAfterHandlingHeaders(channel, future, response, handler, responseHeaders, httpRequest) || //
                exitAfterHandlingReactiveStreams(channel, future, response, handler, httpRequest);
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
        if (!interrupt && !(handler instanceof StreamedAsyncHandler) && (buf.readableBytes() > 0 || last)) {
            NettyResponseBodyPart part = config.getResponseBodyPartFactory().newResponseBodyPart(buf, last);
            interrupt = updateBodyAndInterrupt(future, handler, part);
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
            // e.g. an IOException when trying to open a connection and send the
            // next request
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
