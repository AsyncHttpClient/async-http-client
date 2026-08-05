/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.testng.annotations.Test;

import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * A SOCKS proxy tunnels at the transport layer, so the HTTP request it carries reaches the ORIGIN, not the
 * proxy. Attaching a {@code Proxy-Authorization} header for a SOCKS proxy therefore leaks the proxy
 * credentials to the origin server. The origin request must carry the header only for an HTTP proxy.
 */
public class SocksProxyCredentialLeakTest {

  private static NettyRequestFactory factory() {
    return new NettyRequestFactory(config().build());
  }

  private static Realm preemptiveBasicProxyRealm() {
    return basicAuthRealm("proxy-user", "proxy-secret").setUsePreemptiveAuth(true).build();
  }

  private static ProxyServer proxy(ProxyType type, Realm proxyRealm) {
    return proxyServer("proxy.example.com", 1080).setProxyType(type).setRealm(proxyRealm).build();
  }

  private static boolean hasProxyAuthorization(NettyRequest nettyRequest) {
    return nettyRequest.getHttpRequest().headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION);
  }

  @Test
  public void socks5ProxyDoesNotAttachProxyAuthorizationToPlaintextOrigin() {
    Request request = get("http://origin.example.com/resource").build();
    Realm proxyRealm = preemptiveBasicProxyRealm();
    ProxyServer socksProxy = proxy(ProxyType.SOCKS_V5, proxyRealm);

    NettyRequest nettyRequest = factory().newNettyRequest(request, false, socksProxy, null, proxyRealm);

    assertFalse(hasProxyAuthorization(nettyRequest),
            "SOCKS proxy tunnels to the origin; Proxy-Authorization must not be sent to the origin");
  }

  @Test
  public void socks4ProxyDoesNotAttachProxyAuthorizationToPlaintextOrigin() {
    Request request = get("http://origin.example.com/resource").build();
    Realm proxyRealm = preemptiveBasicProxyRealm();
    ProxyServer socksProxy = proxy(ProxyType.SOCKS_V4, proxyRealm);

    NettyRequest nettyRequest = factory().newNettyRequest(request, false, socksProxy, null, proxyRealm);

    assertFalse(hasProxyAuthorization(nettyRequest),
            "SOCKS proxy tunnels to the origin; Proxy-Authorization must not be sent to the origin");
  }

  @Test
  public void socks5ProxyDoesNotAttachProxyAuthorizationToSecuredOrigin() {
    // A https:// origin behind a SOCKS proxy never gets a CONNECT (needConnect requires an HTTP proxy),
    // so the request itself is tunnelled straight to the origin.
    Request request = get("https://origin.example.com/resource").build();
    Realm proxyRealm = preemptiveBasicProxyRealm();
    ProxyServer socksProxy = proxy(ProxyType.SOCKS_V5, proxyRealm);

    NettyRequest nettyRequest = factory().newNettyRequest(request, false, socksProxy, null, proxyRealm);

    assertFalse(hasProxyAuthorization(nettyRequest),
            "SOCKS proxy tunnels to the origin; Proxy-Authorization must not be sent to the origin");
  }

  @Test
  public void httpProxyStillAttachesProxyAuthorizationToPlaintextOrigin() {
    Request request = get("http://origin.example.com/resource").build();
    Realm proxyRealm = preemptiveBasicProxyRealm();
    // Default proxy type is HTTP.
    ProxyServer httpProxy = proxyServer("proxy.example.com", 8080).setRealm(proxyRealm).build();

    NettyRequest nettyRequest = factory().newNettyRequest(request, false, httpProxy, null, proxyRealm);

    assertTrue(hasProxyAuthorization(nettyRequest),
            "HTTP proxy receives the origin request directly; Proxy-Authorization must be sent");
    assertTrue(nettyRequest.getHttpRequest().headers().get(HttpHeaderNames.PROXY_AUTHORIZATION).startsWith("Basic "),
            "expected a preemptive Basic Proxy-Authorization header");
  }

  @Test
  public void connectRequestThroughHttpProxyStillCarriesProxyAuthorization() {
    // A CONNECT to open a tunnel for an HTTPS origin is sent to the proxy in the clear, so it legitimately
    // carries the proxy credentials.
    Request request = get("https://origin.example.com/resource").build();
    Realm proxyRealm = preemptiveBasicProxyRealm();
    ProxyServer httpProxy = proxyServer("proxy.example.com", 8080).setRealm(proxyRealm).build();

    NettyRequest connect = factory().newNettyRequest(request, true, httpProxy, null, proxyRealm);

    assertTrue(hasProxyAuthorization(connect),
            "CONNECT is sent to the proxy to open the tunnel; Proxy-Authorization must be sent");
  }

  @Test
  public void directRequestWithoutAProxyCarriesNoProxyAuthorization() {
    Request request = get("http://origin.example.com/resource").build();
    Realm proxyRealm = preemptiveBasicProxyRealm();

    NettyRequest nettyRequest = factory().newNettyRequest(request, false, null, null, proxyRealm);

    assertFalse(hasProxyAuthorization(nettyRequest),
            "there is no proxy to authenticate to");
  }
}
