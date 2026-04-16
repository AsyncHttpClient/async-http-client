/*
 *    Copyright (c) 2015-2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * Tests for credential stripping on cross-domain redirects and HTTPS-to-HTTP downgrades.
 * Verifies that Authorization headers and Realm credentials are not leaked to different origins.
 */
public class RedirectCredentialSecurityTest {

  private static HttpServer serverA;
  private static HttpServer serverB;
  private static HttpServer serverC;
  private static int portA;
  private static int portB;
  private static int portC;
  private static final AtomicReference<String> lastAuthHeaderOnA = new AtomicReference<>();
  private static final AtomicReference<String> lastAuthHeaderOnB = new AtomicReference<>();
  private static final AtomicReference<String> authAtChainStep2 = new AtomicReference<>();
  private static final AtomicReference<String> authOnBounceBack = new AtomicReference<>();
  private static final AtomicReference<String> authOn307Target = new AtomicReference<>();
  private static final AtomicReference<String> bodyOn307Target = new AtomicReference<>();
  private static final AtomicReference<String> authOn308Target = new AtomicReference<>();
  private static final AtomicReference<String> bodyOn308Target = new AtomicReference<>();
  private static final AtomicReference<String> proxyAuthOnB = new AtomicReference<>();
  private static final AtomicReference<String> authOnHttpsDowngradeTarget = new AtomicReference<>();

  @BeforeClass
  public static void startServers() throws Exception {
    // Server A: the "origin" server that issues redirects
    serverA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    portA = serverA.getAddress().getPort();

    // Server B: the "target" server that receives redirected requests (different port = different origin)
    serverB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    portB = serverB.getAddress().getPort();

    // Server C: a third server for multi-hop redirect chains
    serverC = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    portC = serverC.getAddress().getPort();

    // Server A endpoints
    serverA.createContext("/redirect-to-b", exchange -> {
      lastAuthHeaderOnA.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });

    serverA.createContext("/redirect-same-origin", exchange -> {
      lastAuthHeaderOnA.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portA + "/final");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });

    serverA.createContext("/final", exchange -> {
      lastAuthHeaderOnA.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().close();
      exchange.close();
    });

    // Server B endpoints
    serverB.createContext("/target", exchange -> {
      lastAuthHeaderOnB.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().close();
      exchange.close();
    });

    // Multi-hop: A -> A (same-origin) -> B (cross-domain)
    serverA.createContext("/chain-same-then-cross", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portA + "/chain-step2");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });

    serverA.createContext("/chain-step2", exchange -> {
      authAtChainStep2.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });

    // Multi-hop: A -> B (cross-domain, credentials stripped) -> C (credentials stay stripped)
    serverA.createContext("/chain-cross-and-back", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/bounce-to-c");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });

    serverB.createContext("/bounce-to-c", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portC + "/chain-final");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });

    serverC.createContext("/chain-final", exchange -> {
      authOnBounceBack.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().close();
      exchange.close();
    });

    // 307 Temporary Redirect: A -> B (body preserved, auth stripped)
    serverA.createContext("/redirect-307-to-b", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target-307");
      exchange.sendResponseHeaders(307, -1);
      exchange.close();
    });

    serverB.createContext("/target-307", exchange -> {
      authOn307Target.set(exchange.getRequestHeaders().getFirst("Authorization"));
      bodyOn307Target.set(readAll(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().close();
      exchange.close();
    });

    // 308 Permanent Redirect: A -> B (body preserved, auth stripped)
    serverA.createContext("/redirect-308-to-b", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target-308");
      exchange.sendResponseHeaders(308, -1);
      exchange.close();
    });

    serverB.createContext("/target-308", exchange -> {
      authOn308Target.set(exchange.getRequestHeaders().getFirst("Authorization"));
      bodyOn308Target.set(readAll(exchange.getRequestBody()));
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().close();
      exchange.close();
    });

    // Proxy-Authorization cross-domain redirect: A -> B
    serverA.createContext("/redirect-to-b-proxy", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target-proxy");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });

    serverB.createContext("/target-proxy", exchange -> {
      proxyAuthOnB.set(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().close();
      exchange.close();
    });

    serverA.start();
    serverB.start();
    serverC.start();
  }

  @AfterClass
  public static void stopServers() {
    serverA.stop(0);
    serverB.stop(0);
    serverC.stop(0);
  }

  private static String readAll(InputStream in) throws java.io.IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  /**
   * Cross-domain redirect (different port) must strip Authorization header.
   */
  @Test
  public void crossDomainRedirectStripsAuthHeader() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      lastAuthHeaderOnA.set(null);
      lastAuthHeaderOnB.set(null);

      client.prepareGet("http://127.0.0.1:" + portA + "/redirect-to-b")
              .setHeader("Authorization", "Bearer secret-token")
              .execute()
              .get(5, TimeUnit.SECONDS);

      // Auth header should be present on the original request to server A
      assertEquals(lastAuthHeaderOnA.get(), "Bearer secret-token",
              "Authorization header should be present on original request");
      // Auth header must NOT be forwarded to the cross-domain target (server B)
      assertNull(lastAuthHeaderOnB.get(),
              "Authorization header must be stripped on cross-domain redirect");
    }
  }

  /**
   * Same-origin redirect (same host and port) should preserve Authorization header.
   */
  @Test
  public void sameOriginRedirectPreservesAuthHeader() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      lastAuthHeaderOnA.set(null);

      client.prepareGet("http://127.0.0.1:" + portA + "/redirect-same-origin")
              .setHeader("Authorization", "Bearer secret-token")
              .execute()
              .get(5, TimeUnit.SECONDS);

      // Auth header should still be present after same-origin redirect
      assertEquals(lastAuthHeaderOnA.get(), "Bearer secret-token",
              "Authorization header should be preserved on same-origin redirect");
    }
  }

  /**
   * Cross-domain redirect must strip Proxy-Authorization header too.
   */
  @Test
  public void crossDomainRedirectStripsProxyAuthHeader() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      proxyAuthOnB.set(null);

      client.prepareGet("http://127.0.0.1:" + portA + "/redirect-to-b-proxy")
              .setHeader("Proxy-Authorization", "Basic cHJveHk6cGFzcw==")
              .execute()
              .get(5, TimeUnit.SECONDS);

      assertNull(proxyAuthOnB.get(),
              "Proxy-Authorization header must be stripped on cross-domain redirect");
    }
  }

  /**
   * Realm-based BASIC auth credentials must NOT be propagated on cross-domain redirects.
   * This tests that the Realm object is cleared, preventing credential regeneration
   * via NettyRequestFactory.
   */
  @Test
  public void crossDomainRedirectDoesNotPropagateRealm() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      lastAuthHeaderOnB.set(null);

      client.prepareGet("http://127.0.0.1:" + portA + "/redirect-to-b")
              .setRealm(basicAuthRealm("user", "password").setUsePreemptiveAuth(true).build())
              .execute()
              .get(5, TimeUnit.SECONDS);

      // The Realm-generated Authorization header must NOT appear on the cross-domain target
      assertNull(lastAuthHeaderOnB.get(),
              "Realm-based credentials must not be propagated on cross-domain redirect");
    }
  }

  /**
   * Realm bypass is closed: even with stripAuthorizationOnRedirect=true on a same-origin redirect,
   * the Realm credential regeneration must be prevented (no Authorization header on target).
   */
  @Test
  public void stripAuthorizationOnRedirectAlsoStripsRealm() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .setStripAuthorizationOnRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      lastAuthHeaderOnA.set(null);

      client.prepareGet("http://127.0.0.1:" + portA + "/redirect-same-origin")
              .setRealm(basicAuthRealm("user", "password").setUsePreemptiveAuth(true).build())
              .execute()
              .get(5, TimeUnit.SECONDS);

      // Even on same-origin, with stripAuthorizationOnRedirect=true, the Realm-regenerated
      // Authorization header must NOT appear (this was the bypass bug)
      assertNull(lastAuthHeaderOnA.get(),
              "stripAuthorizationOnRedirect=true must also prevent Realm-based credential regeneration");
    }
  }

  /**
   * Same-origin redirect should preserve Realm-based credentials when stripping is not enabled.
   */
  @Test
  public void sameOriginRedirectPreservesRealmCredentials() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      lastAuthHeaderOnA.set(null);

      client.prepareGet("http://127.0.0.1:" + portA + "/redirect-same-origin")
              .setRealm(basicAuthRealm("user", "password").setUsePreemptiveAuth(true).build())
              .execute()
              .get(5, TimeUnit.SECONDS);

      // On same-origin, Realm credentials should be preserved
      assertEquals(lastAuthHeaderOnA.get(), "Basic dXNlcjpwYXNzd29yZA==",
              "Realm-based credentials should be preserved on same-origin redirect");
    }
  }

  /**
   * Multi-hop redirect: A -> A (same-origin) -> B (cross-domain).
   * Credentials should survive the same-origin hop but be stripped on the cross-domain hop.
   */
  @Test
  public void multiHopSameOriginThenCrossDomainStripsCredentials() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      authAtChainStep2.set(null);
      lastAuthHeaderOnB.set(null);

      client.prepareGet("http://127.0.0.1:" + portA + "/chain-same-then-cross")
              .setHeader("Authorization", "Bearer secret-token")
              .execute()
              .get(5, TimeUnit.SECONDS);

      // Credentials should survive the same-origin intermediate hop (A -> A)
      assertEquals(authAtChainStep2.get(), "Bearer secret-token",
              "Authorization header should be preserved on same-origin intermediate redirect");
      // Credentials must be stripped on the final cross-domain hop (A -> B)
      assertNull(lastAuthHeaderOnB.get(),
              "Authorization header must be stripped on cross-domain hop in redirect chain");
    }
  }

  /**
   * Multi-hop redirect: A -> B (cross-domain) -> C (another domain).
   * Once credentials are stripped at the first cross-domain hop, they must not reappear
   * on subsequent hops even if the Realm was originally set.
   */
  @Test
  public void multiHopCredentialsStayStrippedAfterCrossDomain() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      authOnBounceBack.set(null);

      client.prepareGet("http://127.0.0.1:" + portA + "/chain-cross-and-back")
              .setRealm(basicAuthRealm("user", "password").setUsePreemptiveAuth(true).build())
              .execute()
              .get(5, TimeUnit.SECONDS);

      // Credentials were stripped at A -> B; they must not reappear at B -> C
      assertNull(authOnBounceBack.get(),
              "Credentials must not reappear after being stripped at a cross-domain hop");
    }
  }

  /**
   * 307 Temporary Redirect cross-domain: body is preserved but Authorization is stripped.
   */
  @Test
  public void redirect307CrossDomainStripsAuthButPreservesBody() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      authOn307Target.set(null);
      bodyOn307Target.set(null);

      client.preparePost("http://127.0.0.1:" + portA + "/redirect-307-to-b")
              .setHeader("Authorization", "Bearer secret-token")
              .setBody("request-body-content")
              .execute()
              .get(5, TimeUnit.SECONDS);

      assertNull(authOn307Target.get(),
              "Authorization header must be stripped on cross-domain 307 redirect");
      assertEquals(bodyOn307Target.get(), "request-body-content",
              "Request body must be preserved on 307 redirect");
    }
  }

  /**
   * 308 Permanent Redirect cross-domain: body is preserved but Authorization is stripped.
   */
  @Test
  public void redirect308CrossDomainStripsAuthButPreservesBody() throws Exception {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setFollowRedirect(true)
            .build();
    try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
      authOn308Target.set(null);
      bodyOn308Target.set(null);

      client.preparePost("http://127.0.0.1:" + portA + "/redirect-308-to-b")
              .setHeader("Authorization", "Bearer secret-token")
              .setBody("request-body-content")
              .execute()
              .get(5, TimeUnit.SECONDS);

      assertNull(authOn308Target.get(),
              "Authorization header must be stripped on cross-domain 308 redirect");
      assertEquals(bodyOn308Target.get(), "request-body-content",
              "Request body must be preserved on 308 redirect");
    }
  }
}
