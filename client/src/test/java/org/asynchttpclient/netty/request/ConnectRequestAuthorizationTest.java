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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * A CONNECT request opens the proxy tunnel and is sent to the proxy in the clear, so it must not carry the
 * origin {@code Authorization} header (which would expose the origin credentials to the proxy). The header
 * belongs only on the request sent through the established tunnel.
 */
public class ConnectRequestAuthorizationTest {

  private static NettyRequestFactory factory() {
    return new NettyRequestFactory(config().build());
  }

  @Test
  public void connectRequestDoesNotCarryOriginAuthorization() {
    Request request = get("https://origin.example.com/resource").build();
    Realm realm = basicAuthRealm("user", "secret").setUsePreemptiveAuth(true).build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest connect = factory().newNettyRequest(request, true, proxy, realm, null);

    assertFalse(connect.getHttpRequest().headers().contains(HttpHeaderNames.AUTHORIZATION),
            "CONNECT request must not expose the origin Authorization to the proxy");
  }

  @Test
  public void tunneledRequestKeepsOriginAuthorization() {
    Request request = get("https://origin.example.com/resource").build();
    Realm realm = basicAuthRealm("user", "secret").setUsePreemptiveAuth(true).build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest tunneled = factory().newNettyRequest(request, false, proxy, realm, null);

    assertTrue(tunneled.getHttpRequest().headers().contains(HttpHeaderNames.AUTHORIZATION),
            "the request sent through the tunnel must still carry the origin Authorization");
  }

  @Test
  public void connectRequestDoesNotCarryOriginAuthorizationSetAsAPlainHeader() {
    Request request = get("https://origin.example.com/resource")
            .setHeader(HttpHeaderNames.AUTHORIZATION, "Bearer origin-token")
            .build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest connect = factory().newNettyRequest(request, true, proxy, null, null);

    assertFalse(connect.getHttpRequest().headers().contains(HttpHeaderNames.AUTHORIZATION),
            "an explicitly set origin Authorization must not ride the plaintext CONNECT either");
  }
}
