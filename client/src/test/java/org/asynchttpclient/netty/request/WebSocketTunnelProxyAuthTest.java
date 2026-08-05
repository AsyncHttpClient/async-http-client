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
import org.testng.annotations.Test;

import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * A ws:// request through an HTTP proxy is tunnelled with CONNECT (NettyRequestSender's needConnect check
 * treats ws:// like https://), so the upgrade request that follows travels through the tunnel to the origin,
 * not to the proxy. It must therefore be treated like wss://: the proxy credentials belong only on the
 * CONNECT (which the proxy actually receives), and the request target must be origin-form.
 */
public class WebSocketTunnelProxyAuthTest {

  private static NettyRequestFactory factory() {
    return new NettyRequestFactory(config().build());
  }

  private static Realm preemptiveBasicProxyRealm() {
    return basicAuthRealm("proxy-user", "proxy-secret").setUsePreemptiveAuth(true).build();
  }

  @Test
  public void connectRequestForWebSocketCarriesProxyAuthorization() {
    Request request = get("ws://origin.example.com/socket").build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest connect = factory().newNettyRequest(request, true, proxy, null, preemptiveBasicProxyRealm());

    assertTrue(connect.getHttpRequest().headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION),
            "the CONNECT request must still authenticate to the proxy");
  }

  @Test
  public void tunneledWebSocketUpgradeDoesNotCarryProxyAuthorization() {
    Request request = get("ws://origin.example.com/socket").build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest tunneled = factory().newNettyRequest(request, false, proxy, null, preemptiveBasicProxyRealm());

    assertFalse(tunneled.getHttpRequest().headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION),
            "the tunnelled ws:// upgrade reaches the origin, not the proxy, and must not expose the proxy credentials");
    assertEquals(tunneled.getHttpRequest().uri(), "/socket",
            "the tunnelled ws:// upgrade must use an origin-form request target, not the proxy's absolute-form");
  }

  @Test
  public void tunneledSecureWebSocketUpgradeDoesNotCarryProxyAuthorization() {
    Request request = get("wss://origin.example.com/socket").build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest tunneled = factory().newNettyRequest(request, false, proxy, null, preemptiveBasicProxyRealm());

    assertFalse(tunneled.getHttpRequest().headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION),
            "wss:// was already tunnel-safe; this guards against a regression");
  }

  @Test
  public void plainHttpRequestToProxyKeepsProxyAuthorization() {
    Request request = get("http://origin.example.com/resource").build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest direct = factory().newNettyRequest(request, false, proxy, null, preemptiveBasicProxyRealm());

    assertTrue(direct.getHttpRequest().headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION),
            "a plaintext http:// request sent directly to the proxy still authenticates to it");
    assertEquals(direct.getHttpRequest().uri(), "http://origin.example.com/resource",
            "a plaintext http:// request to the proxy still uses an absolute-form request target");
  }
}
