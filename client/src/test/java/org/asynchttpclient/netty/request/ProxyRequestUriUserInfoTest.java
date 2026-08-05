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

import org.asynchttpclient.Request;
import org.asynchttpclient.proxy.ProxyServer;
import org.testng.annotations.Test;

import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

/**
 * A plaintext request routed through an HTTP proxy carries an absolute-form request target, which the proxy
 * sees and logs in the clear. RFC 9110 section 4.2.4 forbids the userinfo subcomponent in a generated
 * request target, so the URL credentials must not appear on that request line.
 */
public class ProxyRequestUriUserInfoTest {

  private static NettyRequestFactory factory() {
    return new NettyRequestFactory(config().build());
  }

  @Test
  public void proxiedRequestTargetDropsUserInfo() {
    Request request = get("http://user:secret@origin.example.com/resource?a=b").build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest proxied = factory().newNettyRequest(request, false, proxy, null, null);
    String requestTarget = proxied.getHttpRequest().uri();

    assertFalse(requestTarget.contains("secret"),
            "the absolute-form request target must not expose the URL credentials to the proxy");
    assertEquals(requestTarget, "http://origin.example.com/resource?a=b");
  }

  @Test
  public void proxiedRequestTargetDropsUserInfoOnAnExplicitPort() {
    Request request = get("http://user:secret@origin.example.com:8081/resource").build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest proxied = factory().newNettyRequest(request, false, proxy, null, null);

    assertEquals(proxied.getHttpRequest().uri(), "http://origin.example.com:8081/resource");
  }

  @Test
  public void proxiedRequestTargetWithoutUserInfoIsUnchanged() {
    Request request = get("http://origin.example.com/resource?a=b").build();
    ProxyServer proxy = proxyServer("proxy.example.com", 8080).build();

    NettyRequest proxied = factory().newNettyRequest(request, false, proxy, null, null);

    assertEquals(proxied.getHttpRequest().uri(), "http://origin.example.com/resource?a=b");
  }

  @Test
  public void directRequestTargetStaysRelative() {
    Request request = get("http://user:secret@origin.example.com/resource?a=b").build();

    NettyRequest direct = factory().newNettyRequest(request, false, null, null, null);

    assertEquals(direct.getHttpRequest().uri(), "/resource?a=b");
  }
}
