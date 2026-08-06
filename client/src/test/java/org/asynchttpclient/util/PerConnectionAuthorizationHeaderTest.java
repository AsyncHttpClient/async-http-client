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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The origin realm's Kerberos/SPNEGO token must be minted against the ORIGIN service, never against the
 * proxy, even when a proxy is configured. Building it from {@code proxyServer.getHost()} produced a service
 * ticket for the proxy's SPN: a confused deputy in which the origin's credential is delivered to, and only
 * usable by, the proxy, while origin authentication fails.
 *
 * <p>Minting a real token needs a KDC, so the assertion is made on the service principal name the engine
 * derives, which is logged before any GSS or network work happens.
 */
public class PerConnectionAuthorizationHeaderTest {

  private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
    private final List<String> messages = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      messages.add(event.getFormattedMessage());
    }
  }

  /**
   * Drives perConnectionAuthorizationHeader with a SPNEGO origin realm and returns the service principal
   * name SpnegoEngine derived. Token generation itself fails without a KDC, which is irrelevant here: the
   * SPN is chosen, and logged, before the first GSS call.
   */
  private static String servicePrincipalNameFor(Request request, ProxyServer proxyServer) {
    ch.qos.logback.classic.Logger engineLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SpnegoEngine.class);
    Level previousLevel = engineLogger.getLevel();
    CapturingAppender appender = new CapturingAppender();
    appender.start();
    engineLogger.addAppender(appender);
    engineLogger.setLevel(Level.DEBUG);

    try {
      Realm realm = new Realm.Builder("principal", "password")
              .setScheme(Realm.AuthScheme.SPNEGO)
              .setUsePreemptiveAuth(true)
              // Pin off DNS so the derived name is exactly the host we passed in.
              .setUseCanonicalHostname(false)
              .build();
      try {
        AuthenticatorUtils.perConnectionAuthorizationHeader(request, proxyServer, realm);
      } catch (Throwable expected) {
        // No KDC in the build environment, so the handshake cannot complete. The SPN has already been
        // derived and logged by this point.
      }

      for (String message : appender.messages) {
        if (message.startsWith("Service Principal Name is ")) {
          return message.substring("Service Principal Name is ".length());
        }
      }
      throw new AssertionError("SpnegoEngine never derived a service principal name; captured: " + appender.messages);

    } finally {
      engineLogger.detachAppender(appender);
      engineLogger.setLevel(previousLevel);
      appender.stop();
    }
  }

  @Test
  public void spnegoTokenTargetsTheOriginEvenBehindAProxy() {
    Request request = new RequestBuilder("GET").setUrl("http://origin.example.com/resource").build();
    ProxyServer proxyServer = new ProxyServer.Builder("proxy.example.com", 8080).build();

    String spn = servicePrincipalNameFor(request, proxyServer);

    assertEquals(spn, "HTTP@origin.example.com",
            "the origin realm's Negotiate token must target the origin service");
    assertFalse(spn.contains("proxy.example.com"),
            "the origin credential must not be minted for the proxy's SPN: " + spn);
  }

  @Test
  public void spnegoTokenPrefersTheVirtualHostBehindAProxy() {
    Request request = new RequestBuilder("GET")
            .setUrl("http://origin.example.com/resource")
            .setVirtualHost("vhost.example.com")
            .build();
    ProxyServer proxyServer = new ProxyServer.Builder("proxy.example.com", 8080).build();

    assertEquals(servicePrincipalNameFor(request, proxyServer), "HTTP@vhost.example.com");
  }

  @Test
  public void spnegoTokenTargetsTheOriginWithoutAProxy() {
    Request request = new RequestBuilder("GET").setUrl("http://origin.example.com/resource").build();

    assertEquals(servicePrincipalNameFor(request, null), "HTTP@origin.example.com");
  }

  /**
   * The proxy realm keeps its own path and is unaffected: NTLM is minted per connection there, and the
   * Kerberos/SPNEGO proxy token is built against the proxy host by ProxyUnauthorized407Interceptor.
   */
  @Test
  public void proxyRealmStillGetsItsOwnPerConnectionHeader() {
    Request request = new RequestBuilder("GET").setUrl("http://origin.example.com/resource").build();
    Realm proxyRealm = new Realm.Builder("principal", "password")
            .setScheme(Realm.AuthScheme.NTLM)
            .setUsePreemptiveAuth(true)
            .build();

    String header = AuthenticatorUtils.perConnectionProxyAuthorizationHeader(request, proxyRealm);
    assertTrue(header != null && header.startsWith("NTLM "), "expected an NTLM type-1 message but got: " + header);
  }
}
