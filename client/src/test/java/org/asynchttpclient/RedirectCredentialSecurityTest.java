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
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for credential stripping on cross-domain redirects and HTTPS-to-HTTP downgrades.
 * Verifies that Authorization headers, Cookie headers, and Realm credentials are not leaked
 * to different origins.
 */
public class RedirectCredentialSecurityTest {

    private static HttpServer serverA;
    private static HttpServer serverB;
    private static HttpServer serverC;
    private static HttpsServer httpsServer;
    private static int portA;
    private static int portB;
    private static int portC;
    private static int portHttps;
    private static final AtomicReference<String> lastAuthHeaderOnA = new AtomicReference<>();
    private static final AtomicReference<String> lastAuthHeaderOnB = new AtomicReference<>();
    private static final AtomicReference<String> authAtChainStep2 = new AtomicReference<>();
    private static final AtomicReference<String> authOnBounceBack = new AtomicReference<>();
    private static final AtomicReference<String> authOn307Target = new AtomicReference<>();
    private static final AtomicReference<String> bodyOn307Target = new AtomicReference<>();
    private static final AtomicReference<String> authOn308Target = new AtomicReference<>();
    private static final AtomicReference<String> bodyOn308Target = new AtomicReference<>();
    private static final AtomicReference<String> lastCookieHeaderOnA = new AtomicReference<>();
    private static final AtomicReference<String> lastCookieHeaderOnB = new AtomicReference<>();
    private static final AtomicReference<String> cookieAtChainStep2 = new AtomicReference<>();
    private static final AtomicReference<String> cookieOnBounceBack = new AtomicReference<>();
    private static final AtomicReference<String> authAfterHttpsDowngrade = new AtomicReference<>();
    private static final AtomicReference<String> cookieAfterHttpsDowngrade = new AtomicReference<>();

    @BeforeAll
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
            lastCookieHeaderOnA.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        serverA.createContext("/redirect-same-origin", exchange -> {
            lastAuthHeaderOnA.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastCookieHeaderOnA.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portA + "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        serverA.createContext("/final", exchange -> {
            lastAuthHeaderOnA.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastCookieHeaderOnA.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        // Server B endpoints
        serverB.createContext("/target", exchange -> {
            lastAuthHeaderOnB.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastCookieHeaderOnB.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        // Multi-hop: A → A (same-origin) → B (cross-domain)
        serverA.createContext("/chain-same-then-cross", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portA + "/chain-step2");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        serverA.createContext("/chain-step2", exchange -> {
            authAtChainStep2.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookieAtChainStep2.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        // Multi-hop: A → B (cross-domain, credentials stripped) → C (credentials stay stripped)
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
            cookieOnBounceBack.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        // 307 Temporary Redirect: A → B (body preserved, auth stripped)
        serverA.createContext("/redirect-307-to-b", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target-307");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });

        serverB.createContext("/target-307", exchange -> {
            authOn307Target.set(exchange.getRequestHeaders().getFirst("Authorization"));
            bodyOn307Target.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        // 308 Permanent Redirect: A → B (body preserved, auth stripped)
        serverA.createContext("/redirect-308-to-b", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target-308");
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
        });

        serverB.createContext("/target-308", exchange -> {
            authOn308Target.set(exchange.getRequestHeaders().getFirst("Authorization"));
            bodyOn308Target.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        // Endpoint reused by the HTTPS-to-HTTP downgrade test (target on server B over plain HTTP)
        serverB.createContext("/target-after-downgrade", exchange -> {
            authAfterHttpsDowngrade.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookieAfterHttpsDowngrade.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            exchange.close();
        });

        // HTTPS server on 127.0.0.1: issues a redirect downgrading to plain HTTP on server B
        httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(buildSslContext()));
        portHttps = httpsServer.getAddress().getPort();
        httpsServer.createContext("/redirect-downgrade-to-http", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target-after-downgrade");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        serverA.start();
        serverB.start();
        serverC.start();
        httpsServer.start();
    }

    @AfterAll
    public static void stopServers() {
        serverA.stop(0);
        serverB.stop(0);
        serverC.stop(0);
        httpsServer.stop(0);
    }

    private static SSLContext buildSslContext() throws Exception {
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream in = RedirectCredentialSecurityTest.class.getClassLoader()
                .getResourceAsStream("ssltest-keystore.jks")) {
            keyStore.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * Cross-domain redirect (different port) must strip Authorization header.
     */
    @Test
    void crossDomainRedirectStripsAuthHeader() throws Exception {
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
            assertEquals("Bearer secret-token", lastAuthHeaderOnA.get(),
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
    void sameOriginRedirectPreservesAuthHeader() throws Exception {
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
            assertEquals("Bearer secret-token", lastAuthHeaderOnA.get(),
                    "Authorization header should be preserved on same-origin redirect");
        }
    }

    /**
     * Cross-domain redirect must strip Proxy-Authorization header too.
     */
    @Test
    void crossDomainRedirectStripsProxyAuthHeader() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastAuthHeaderOnB.set(null);

            // We capture Proxy-Authorization on server B using a custom handler
            final AtomicReference<String> proxyAuthOnB = new AtomicReference<>();
            serverB.createContext("/target-proxy", exchange -> {
                proxyAuthOnB.set(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
                exchange.close();
            });
            serverA.createContext("/redirect-to-b-proxy", exchange -> {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target-proxy");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });

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
    void crossDomainRedirectDoesNotPropagateRealm() throws Exception {
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
    void stripAuthorizationOnRedirectAlsoStripsRealm() throws Exception {
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
    void sameOriginRedirectPreservesRealmCredentials() throws Exception {
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
            assertEquals("Basic dXNlcjpwYXNzd29yZA==", lastAuthHeaderOnA.get(),
                    "Realm-based credentials should be preserved on same-origin redirect");
        }
    }

    /**
     * Multi-hop redirect: A → A (same-origin) → B (cross-domain).
     * Credentials should survive the same-origin hop but be stripped on the cross-domain hop.
     */
    @Test
    void multiHopSameOriginThenCrossDomainStripsCredentials() throws Exception {
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

            // Credentials should survive the same-origin intermediate hop (A → A)
            assertEquals("Bearer secret-token", authAtChainStep2.get(),
                    "Authorization header should be preserved on same-origin intermediate redirect");
            // Credentials must be stripped on the final cross-domain hop (A → B)
            assertNull(lastAuthHeaderOnB.get(),
                    "Authorization header must be stripped on cross-domain hop in redirect chain");
        }
    }

    /**
     * Multi-hop redirect: A → B (cross-domain) → C (another domain).
     * Once credentials are stripped at the first cross-domain hop, they must not reappear
     * on subsequent hops even if the Realm was originally set.
     */
    @Test
    void multiHopCredentialsStayStrippedAfterCrossDomain() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            authOnBounceBack.set(null);

            client.prepareGet("http://127.0.0.1:" + portA + "/chain-cross-and-back")
                    .setRealm(basicAuthRealm("user", "password").setUsePreemptiveAuth(true).build())
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            // Credentials were stripped at A → B; they must not reappear at B → C
            assertNull(authOnBounceBack.get(),
                    "Credentials must not reappear after being stripped at a cross-domain hop");
        }
    }

    /**
     * 307 Temporary Redirect cross-domain: body is preserved but Authorization is stripped.
     */
    @Test
    void redirect307CrossDomainStripsAuthButPreservesBody() throws Exception {
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
            assertEquals("request-body-content", bodyOn307Target.get(),
                    "Request body must be preserved on 307 redirect");
        }
    }

    /**
     * 308 Permanent Redirect cross-domain: body is preserved but Authorization is stripped.
     */
    @Test
    void redirect308CrossDomainStripsAuthButPreservesBody() throws Exception {
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
            assertEquals("request-body-content", bodyOn308Target.get(),
                    "Request body must be preserved on 308 redirect");
        }
    }

    /**
     * Cross-domain redirect (different port) must strip a user-supplied Cookie header.
     * Regression test for GHSA-fmxf-pm6p-7xgm.
     */
    @Test
    void crossDomainRedirectStripsCookieHeader() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastCookieHeaderOnA.set(null);
            lastCookieHeaderOnB.set(null);

            client.prepareGet("http://127.0.0.1:" + portA + "/redirect-to-b")
                    .setHeader("Cookie", "session=abc123; csrf=xyz789")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            // Cookie should be present on the original request to server A
            assertEquals("session=abc123; csrf=xyz789", lastCookieHeaderOnA.get(),
                    "Cookie header should be present on original request");
            // Cookie must NOT be forwarded to the cross-domain target (server B)
            assertNull(lastCookieHeaderOnB.get(),
                    "Cookie header must be stripped on cross-domain redirect");
        }
    }

    /**
     * Same-origin redirect (same host and port) should preserve the Cookie header.
     */
    @Test
    void sameOriginRedirectPreservesCookieHeader() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastCookieHeaderOnA.set(null);

            client.prepareGet("http://127.0.0.1:" + portA + "/redirect-same-origin")
                    .setHeader("Cookie", "session=abc123")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("session=abc123", lastCookieHeaderOnA.get(),
                    "Cookie header should be preserved on same-origin redirect");
        }
    }

    /**
     * Cross-domain redirect must strip both Authorization and Cookie when both are set.
     * Combined regression that mirrors the original PoC.
     */
    @Test
    void crossDomainRedirectStripsBothCookieAndAuthorization() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastAuthHeaderOnA.set(null);
            lastAuthHeaderOnB.set(null);
            lastCookieHeaderOnA.set(null);
            lastCookieHeaderOnB.set(null);

            client.prepareGet("http://127.0.0.1:" + portA + "/redirect-to-b")
                    .setHeader("Authorization", "Bearer token123")
                    .setHeader("Cookie", "session=abc123; api_key=secret")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            assertEquals("Bearer token123", lastAuthHeaderOnA.get(),
                    "Authorization header should be present on original request");
            assertEquals("session=abc123; api_key=secret", lastCookieHeaderOnA.get(),
                    "Cookie header should be present on original request");
            assertNull(lastAuthHeaderOnB.get(),
                    "Authorization header must be stripped on cross-domain redirect");
            assertNull(lastCookieHeaderOnB.get(),
                    "Cookie header must be stripped on cross-domain redirect");
        }
    }

    /**
     * Multi-hop: A → A (same-origin, Cookie preserved) → B (cross-domain, Cookie stripped).
     */
    @Test
    void multiHopChainStripsCookieAtFirstCrossOriginHop() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            cookieAtChainStep2.set(null);
            lastCookieHeaderOnB.set(null);

            client.prepareGet("http://127.0.0.1:" + portA + "/chain-same-then-cross")
                    .setHeader("Cookie", "session=abc123")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            // Cookie should survive the same-origin intermediate hop (A → A)
            assertEquals("session=abc123", cookieAtChainStep2.get(),
                    "Cookie header should be preserved on same-origin intermediate redirect");
            // Cookie must be stripped on the cross-domain hop (A → B)
            assertNull(lastCookieHeaderOnB.get(),
                    "Cookie header must be stripped on cross-domain hop in redirect chain");
        }
    }

    /**
     * Once Cookie is stripped at a cross-domain hop, it must not reappear on subsequent hops.
     */
    @Test
    void multiHopCookieStaysStrippedAfterCrossDomain() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            cookieOnBounceBack.set(null);

            client.prepareGet("http://127.0.0.1:" + portA + "/chain-cross-and-back")
                    .setHeader("Cookie", "session=abc123")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            assertNull(cookieOnBounceBack.get(),
                    "Cookie must not reappear after being stripped at a cross-domain hop");
        }
    }

    /**
     * setStripAuthorizationOnRedirect(true) also strips Cookie on same-origin redirects:
     * the conditions are coupled, so users opting into strict credential stripping get cookie
     * stripping on the same-origin path too.
     */
    @Test
    void stripAuthorizationOnRedirectFlagAlsoStripsCookie() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .setStripAuthorizationOnRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastCookieHeaderOnA.set(null);

            client.prepareGet("http://127.0.0.1:" + portA + "/redirect-same-origin")
                    .setHeader("Cookie", "session=abc123")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            // With stripAuthorizationOnRedirect=true, even same-origin redirects strip the Cookie
            assertNull(lastCookieHeaderOnA.get(),
                    "stripAuthorizationOnRedirect=true must also strip Cookie on same-origin redirect");
        }
    }

    /**
     * Regression: a cookie added to the URI-scoped CookieStore for server B is still delivered
     * to server B after a cross-origin redirect from A → B. Cookie stripping must not break the
     * legitimate cookie-store flow (store cookies are added after the strip step in
     * Redirect30xInterceptor and are URI-matched).
     */
    @Test
    void cookieStoreManagedCookiesUnaffectedByStrip() throws Exception {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://127.0.0.1:" + portB + "/target"),
                new DefaultCookie("store_cookie", "store_value"));

        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .setCookieStore(store)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastCookieHeaderOnB.set(null);

            client.prepareGet("http://127.0.0.1:" + portA + "/redirect-to-b")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            // The store cookie scoped to server B's URI should reach server B
            assertNotNull(lastCookieHeaderOnB.get(),
                    "URI-scoped CookieStore cookies should be delivered after cross-domain redirect");
            assertTrue(lastCookieHeaderOnB.get().contains("store_cookie=store_value"),
                    "Expected store cookie in Cookie header, got: " + lastCookieHeaderOnB.get());
        }
    }

    /**
     * Same host (127.0.0.1) on a different port is treated as cross-origin: both Cookie and
     * Authorization are stripped. Locks in the port-as-part-of-origin behavior so that a future
     * change to {@link Uri#isSameBase} cannot accidentally narrow the origin to host-only.
     */
    @Test
    void portChangeOnSameHostIsTreatedAsCrossOrigin() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastAuthHeaderOnB.set(null);
            lastCookieHeaderOnB.set(null);

            // /redirect-to-b: 127.0.0.1:portA → 127.0.0.1:portB. Same host, different port.
            client.prepareGet("http://127.0.0.1:" + portA + "/redirect-to-b")
                    .setHeader("Authorization", "Bearer same-host-token")
                    .setHeader("Cookie", "session=same-host-cookie")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            assertNull(lastAuthHeaderOnB.get(),
                    "Authorization must be stripped when only the port differs (origin includes port)");
            assertNull(lastCookieHeaderOnB.get(),
                    "Cookie must be stripped when only the port differs (origin includes port)");
        }
    }

    /**
     * HTTPS-to-HTTP same-host downgrade strips both Cookie and Authorization. Exercises the
     * {@code schemeDowngrade} branch in Redirect30xInterceptor.
     */
    @Test
    void httpsToHttpDowngradeStripsCookieAndAuthorization() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .setUseInsecureTrustManager(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            authAfterHttpsDowngrade.set(null);
            cookieAfterHttpsDowngrade.set(null);

            client.prepareGet("https://127.0.0.1:" + portHttps + "/redirect-downgrade-to-http")
                    .setHeader("Authorization", "Bearer secret-token")
                    .setHeader("Cookie", "session=secret-session")
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            assertNull(authAfterHttpsDowngrade.get(),
                    "Authorization must be stripped on HTTPS-to-HTTP downgrade");
            assertNull(cookieAfterHttpsDowngrade.get(),
                    "Cookie must be stripped on HTTPS-to-HTTP downgrade");
        }
    }
}
