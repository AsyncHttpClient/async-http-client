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

/**
 * The contract of the host that names the service a Negotiate token is minted for.
 * <p>
 * That the origin is chosen rather than the proxy is asserted by
 * {@code SpnegoEngineTest.negotiateTokenTargetsTheOriginEvenBehindAProxy}, which drives the real
 * {@code perConnectionAuthorizationHeader} against a KDC and so fails if the product code regresses. These
 * are the cheap unit-level companions to it, covering the two inputs that test does not vary. They mint no
 * token and touch no Kerberos configuration.
 */
public class PerConnectionAuthorizationHeaderTest {

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
