/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.request.NettyRequestSender;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.spnego.SpnegoEngineException;
import org.asynchttpclient.util.AuthenticatorUtils;
import org.asynchttpclient.util.NonceCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static org.asynchttpclient.Dsl.realm;
import static org.asynchttpclient.util.AuthenticatorUtils.NEGOTIATE;
import static org.asynchttpclient.util.AuthenticatorUtils.getHeaderWithPrefix;
import static org.asynchttpclient.util.AuthenticatorUtils.selectBestDigestChallenge;
import static org.asynchttpclient.util.HttpConstants.Methods.CONNECT;

public class ProxyUnauthorized407Interceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyUnauthorized407Interceptor.class);

    private final ChannelManager channelManager;
    private final NettyRequestSender requestSender;
    private final NonceCounter nonceCounter;

    ProxyUnauthorized407Interceptor(ChannelManager channelManager, NettyRequestSender requestSender, NonceCounter nonceCounter) {
        this.channelManager = channelManager;
        this.requestSender = requestSender;
        this.nonceCounter = nonceCounter;
    }

    public boolean exitAfterHandling407(Channel channel, NettyResponseFuture<?> future, HttpResponse response, Request request,
                                        ProxyServer proxyServer, HttpRequest httpRequest) {

        Realm proxyRealm = future.getProxyRealm();

        if (proxyRealm == null) {
            LOGGER.debug("Can't handle 407 as there's no proxyRealm");
            return false;
        }

        List<String> proxyAuthHeaders = response.headers().getAll(PROXY_AUTHENTICATE);

        if (proxyAuthHeaders.isEmpty()) {
            LOGGER.info("Can't handle 407 as response doesn't contain Proxy-Authenticate headers");
            return false;
        }

        // For DIGEST, check stale before blocking on isAndSetInProxyAuth
        if (proxyRealm.getScheme() == AuthScheme.DIGEST) {
            String digestHeader = selectBestDigestChallenge(proxyAuthHeaders);
            if (digestHeader == null) {
                LOGGER.info("Can't handle 407 with Digest realm as Proxy-Authenticate headers don't match");
                return false;
            }
            Realm.Builder realmBuilder = realm(proxyRealm)
                    .setUri(request.getUri())
                    .setMethodName(request.getMethod())
                    .setUsePreemptiveAuth(true)
                    .parseProxyAuthenticateHeader(digestHeader);

            boolean isStale = realmBuilder.isStale();
            Realm previousRealm = future.getProxyRealm();
            boolean alreadyRetriedStale = previousRealm != null && previousRealm.isStale();

            if (isStale && !alreadyRetriedStale) {
                // First stale response: allow retry by resetting inProxyAuth
                LOGGER.debug("Proxy indicated stale nonce, retrying with new nonce");
                future.setInProxyAuth(false);
                if (proxyRealm.getNonce() != null) {
                    nonceCounter.reset(proxyRealm.getNonce());
                }
            } else if (future.isAndSetInProxyAuth(true)) {
                LOGGER.info("Can't handle 407 as auth was already performed");
                return false;
            }

            // Set nc from counter
            String nonce = realmBuilder.getNonceValue();
            if (nonce != null) {
                realmBuilder.setNc(nonceCounter.nextNc(nonce));
            }

            // Handle auth-int
            if ("auth-int".equals(realmBuilder.getQopValue())) {
                String bodyHash = AuthenticatorUtils.computeBodyHash(request, proxyRealm);
                realmBuilder.setEntityBodyHash(bodyHash);
            }

            Realm newDigestRealm = realmBuilder.build();
            future.setProxyRealm(newDigestRealm);

            future.setChannelState(ChannelState.NEW);
            HttpHeaders requestHeaders = new DefaultHttpHeaders().add(request.getHeaders());

            RequestBuilder nextRequestBuilder = future.getCurrentRequest().toBuilder().setHeaders(requestHeaders);
            if (future.getCurrentRequest().getUri().isSecured()) {
                nextRequestBuilder.setMethod(CONNECT);
            }
            final Request nextRequest = nextRequestBuilder.build();

            LOGGER.debug("Sending proxy authentication to {}", request.getUri());
            if (channel instanceof Http2StreamChannel) {
                channel.close();
                requestSender.sendNextRequest(nextRequest, future);
            } else if (future.isKeepAlive()
                    && !HttpUtil.isTransferEncodingChunked(httpRequest)
                    && !HttpUtil.isTransferEncodingChunked(response)) {
                future.setConnectAllowed(true);
                future.setReuseChannel(true);
                requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
            } else {
                channelManager.closeChannel(channel);
                requestSender.sendNextRequest(nextRequest, future);
            }
            return true;
        }

        if (future.isAndSetInProxyAuth(true)) {
            LOGGER.info("Can't handle 407 as auth was already performed");
            return false;
        }

        // FIXME what's this???
        future.setChannelState(ChannelState.NEW);
        HttpHeaders requestHeaders = new DefaultHttpHeaders().add(request.getHeaders());

        switch (proxyRealm.getScheme()) {
            case BASIC:
                if (getHeaderWithPrefix(proxyAuthHeaders, "Basic") == null) {
                    LOGGER.info("Can't handle 407 with Basic realm as Proxy-Authenticate headers don't match");
                    return false;
                }

                if (proxyRealm.isUsePreemptiveAuth()) {
                    LOGGER.info("Can't handle 407 with Basic realm as auth was preemptive and already performed");
                    return false;
                }

                Realm newBasicRealm = realm(proxyRealm)
                        .setUsePreemptiveAuth(true)
                        .build();
                future.setProxyRealm(newBasicRealm);
                break;

            case NTLM:
                String ntlmHeader = getHeaderWithPrefix(proxyAuthHeaders, "NTLM");
                if (ntlmHeader == null) {
                    LOGGER.info("Can't handle 407 with NTLM realm as Proxy-Authenticate headers don't match");
                    return false;
                }
                ntlmProxyChallenge(ntlmHeader, requestHeaders, proxyRealm, future);
                Realm newNtlmRealm = realm(proxyRealm)
                        .setUsePreemptiveAuth(true)
                        .build();
                future.setProxyRealm(newNtlmRealm);
                break;

            case KERBEROS:
            case SPNEGO:
                if (getHeaderWithPrefix(proxyAuthHeaders, NEGOTIATE) == null) {
                    LOGGER.info("Can't handle 407 with Kerberos or Spnego realm as Proxy-Authenticate headers don't match");
                    return false;
                }
                try {
                    kerberosProxyChallenge(proxyRealm, proxyServer, requestHeaders);
                } catch (SpnegoEngineException e) {
                    String ntlmHeader2 = getHeaderWithPrefix(proxyAuthHeaders, "NTLM");
                    if (ntlmHeader2 != null) {
                        LOGGER.warn("Kerberos/Spnego proxy auth failed, proceeding with NTLM");
                        ntlmProxyChallenge(ntlmHeader2, requestHeaders, proxyRealm, future);
                        Realm newNtlmRealm2 = realm(proxyRealm)
                                .setScheme(AuthScheme.NTLM)
                                .setUsePreemptiveAuth(true)
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

        RequestBuilder nextRequestBuilder = future.getCurrentRequest().toBuilder().setHeaders(requestHeaders);
        if (future.getCurrentRequest().getUri().isSecured()) {
            nextRequestBuilder.setMethod(CONNECT);
        }
        final Request nextRequest = nextRequestBuilder.build();

        LOGGER.debug("Sending proxy authentication to {}", request.getUri());
        if (channel instanceof Http2StreamChannel) {
            // HTTP/2 stream channels are single-use — close the stream and send the auth retry.
            channel.close();
            requestSender.sendNextRequest(nextRequest, future);
        } else if (future.isKeepAlive()
                && !HttpUtil.isTransferEncodingChunked(httpRequest)
                && !HttpUtil.isTransferEncodingChunked(response)) {
            future.setConnectAllowed(true);
            future.setReuseChannel(true);
            requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
        } else {
            channelManager.closeChannel(channel);
            requestSender.sendNextRequest(nextRequest, future);
        }

        return true;
    }

    private static void kerberosProxyChallenge(Realm proxyRealm, ProxyServer proxyServer, HttpHeaders headers) throws SpnegoEngineException {
        String challengeHeader = SpnegoEngine.instance(proxyRealm.getPrincipal(),
                proxyRealm.getPassword(),
                proxyRealm.getServicePrincipalName(),
                proxyRealm.getRealmName(),
                proxyRealm.isUseCanonicalHostname(),
                proxyRealm.getCustomLoginConfig(),
                proxyRealm.getLoginContextName()).generateToken(proxyServer.getHost());
        headers.set(PROXY_AUTHORIZATION, NEGOTIATE + ' ' + challengeHeader);
    }

    private static void ntlmProxyChallenge(String authenticateHeader, HttpHeaders requestHeaders, Realm proxyRealm, NettyResponseFuture<?> future) {
        if ("NTLM".equals(authenticateHeader)) {
            // server replied bare NTLM => we didn't preemptively send Type1Msg
            String challengeHeader = NtlmEngine.INSTANCE.generateType1Msg();
            // FIXME we might want to filter current NTLM and add (leave other
            // Authorization headers untouched)
            requestHeaders.set(PROXY_AUTHORIZATION, "NTLM " + challengeHeader);
            future.setInProxyAuth(false);
        } else {
            String serverChallenge = authenticateHeader.substring("NTLM ".length()).trim();
            String challengeHeader = NtlmEngine.generateType3Msg(proxyRealm.getPrincipal(), proxyRealm.getPassword(), proxyRealm.getNtlmDomain(),
                    proxyRealm.getNtlmHost(), serverChallenge);
            // FIXME we might want to filter current NTLM and add (leave other
            // Authorization headers untouched)
            requestHeaders.set(PROXY_AUTHORIZATION, "NTLM " + challengeHeader);
        }
    }
}
