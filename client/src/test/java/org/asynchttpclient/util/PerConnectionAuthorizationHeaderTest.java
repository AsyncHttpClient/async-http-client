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
package org.asynchttpclient.util;

import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

/**
 * The origin realm's Negotiate token must be minted for the ORIGIN service, never the proxy's. Building it
 * against the proxy host produced a service ticket for the proxy's SPN: a confused deputy where the origin's
 * credential is delivered to, and only usable by, the proxy, while origin authentication fails.
 * <p>
 * This asserts the service name that is chosen, rather than driving SpnegoEngine, deliberately. Minting a
 * token initialises the JVM's Kerberos configuration, and that initialisation is cached process-wide, so a
 * test that triggers it poisons {@code SpnegoEngineTest} - which starts its own KDC and installs a krb5.conf
 * in {@code @BeforeClass} - whenever it happens to run first.
 */
public class PerConnectionAuthorizationHeaderTest {

  @Test
  public void negotiateTokenTargetsTheOriginEvenBehindAProxy() {
    Request request = new RequestBuilder("GET").setUrl("http://origin.example.com/resource").build();

    String host = AuthenticatorUtils.negotiateHost(request);

    assertEquals(host, "origin.example.com",
            "the origin realm's Negotiate token must target the origin service");
    assertFalse(host.contains("proxy"),
            "the origin credential must not be minted for the proxy's SPN: " + host);
  }

  @Test
  public void negotiateTokenPrefersTheVirtualHost() {
    Request request = new RequestBuilder("GET")
            .setUrl("http://origin.example.com/resource")
            .setVirtualHost("virtual.example.com")
            .build();

    assertEquals(AuthenticatorUtils.negotiateHost(request), "virtual.example.com",
            "a configured virtual host names the service the request is really for");
  }

  @Test
  public void negotiateHostIgnoresThePort() {
    Request request = new RequestBuilder("GET").setUrl("http://origin.example.com:8443/resource").build();

    assertEquals(AuthenticatorUtils.negotiateHost(request), "origin.example.com",
            "the service principal name is built from the host alone");
  }
}
