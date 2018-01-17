/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.spnego.SpnegoEngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static org.asynchttpclient.Dsl.realm;
import static org.asynchttpclient.util.AuthenticatorUtils.NEGOTIATE;
import static org.asynchttpclient.util.AuthenticatorUtils.getHeaderWithPrefix;
import static org.asynchttpclient.util.HttpConstants.Methods.CONNECT;

public class ProxyUnauthorized407Interceptor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProxyUnauthorized407Interceptor.class);

  private final ChannelManager channelManager;
  private final NettyRequestSender requestSender;

  ProxyUnauthorized407Interceptor(ChannelManager channelManager, NettyRequestSender requestSender) {
    this.channelManager = channelManager;
    this.requestSender = requestSender;
  }

  public boolean exitAfterHandling407(Channel channel,
                                      NettyResponseFuture<?> future,
                                      HttpResponse response,
                                      Request request,
                                      ProxyServer proxyServer,
                                      HttpRequest httpRequest) {

    if (future.isAndSetInProxyAuth(true)) {
      LOGGER.info("Can't handle 407 as auth was already performed");
      return false;
    }

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

    // FIXME what's this???
    future.setChannelState(ChannelState.NEW);
    HttpHeaders requestHeaders = new DefaultHttpHeaders(false).add(request.getHeaders());

    switch (proxyRealm.getScheme()) {
      case BASIC:
        if (getHeaderWithPrefix(proxyAuthHeaders, "Basic") == null) {
          LOGGER.info("Can't handle 407 with Basic realm as Proxy-Authenticate headers don't match");
          return false;
        }

        if (proxyRealm.isUsePreemptiveAuth()) {
          // FIXME do we need this, as future.getAndSetAuth
          // was tested above?
          // auth was already performed, most likely auth
          // failed
          LOGGER.info("Can't handle 407 with Basic realm as auth was preemptive and already performed");
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
          LOGGER.info("Can't handle 407 with Digest realm as Proxy-Authenticate headers don't match");
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
          LOGGER.info("Can't handle 407 with NTLM realm as Proxy-Authenticate headers don't match");
          return false;
        }
        ntlmProxyChallenge(ntlmHeader, requestHeaders, proxyRealm, future);
        Realm newNtlmRealm = realm(proxyRealm)//
                .setUsePreemptiveAuth(true)//
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
          kerberosProxyChallenge(proxyServer, requestHeaders);

        } catch (SpnegoEngineException e) {
          // FIXME
          String ntlmHeader2 = getHeaderWithPrefix(proxyAuthHeaders, "NTLM");
          if (ntlmHeader2 != null) {
            LOGGER.warn("Kerberos/Spnego proxy auth failed, proceeding with NTLM");
            ntlmProxyChallenge(ntlmHeader2, requestHeaders, proxyRealm, future);
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
      nextRequestBuilder.setMethod(CONNECT);
    }
    final Request nextRequest = nextRequestBuilder.build();

    LOGGER.debug("Sending proxy authentication to {}", request.getUri());
    if (future.isKeepAlive()//
            && !HttpUtil.isTransferEncodingChunked(httpRequest)//
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

  private void kerberosProxyChallenge(ProxyServer proxyServer,
                                      HttpHeaders headers) throws SpnegoEngineException {

    String challengeHeader = SpnegoEngine.instance().generateToken(proxyServer.getHost());
    headers.set(PROXY_AUTHORIZATION, NEGOTIATE + " " + challengeHeader);
  }

  private void ntlmProxyChallenge(String authenticateHeader,
                                  HttpHeaders requestHeaders,
                                  Realm proxyRealm,
                                  NettyResponseFuture<?> future) {

    if (authenticateHeader.equals("NTLM")) {
      // server replied bare NTLM => we didn't preemptively sent Type1Msg
      String challengeHeader = NtlmEngine.INSTANCE.generateType1Msg();
      // FIXME we might want to filter current NTLM and add (leave other
      // Authorization headers untouched)
      requestHeaders.set(PROXY_AUTHORIZATION, "NTLM " + challengeHeader);
      future.setInProxyAuth(false);

    } else {
      String serverChallenge = authenticateHeader.substring("NTLM ".length()).trim();
      String challengeHeader = NtlmEngine.INSTANCE.generateType3Msg(proxyRealm.getPrincipal(), proxyRealm.getPassword(), proxyRealm.getNtlmDomain(),
              proxyRealm.getNtlmHost(), serverChallenge);
      // FIXME we might want to filter current NTLM and add (leave other
      // Authorization headers untouched)
      requestHeaders.set(PROXY_AUTHORIZATION, "NTLM " + challengeHeader);
    }
  }
}
