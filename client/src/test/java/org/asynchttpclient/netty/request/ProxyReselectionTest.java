/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.request;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.LoadBalance;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.proxy.ProxyType;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.asynchttpclient.ws.WebSocketUtils;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.ntlmAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy is selected again for every attempt of an exchange, while a redirect, an authentication retry, a retry
 * and a filter replay all reuse the future of the attempt before. When an attempt that sends the target lands on a
 * different proxy, whatever that future carries about the previous one - whether the socket gets TLS or a CONNECT,
 * the pool key, the proxy credentials - has to follow. An answer to a challenge, and a request on a channel the
 * future holds, stay where they are.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ProxyReselectionTest {

    private static final String ALICE = basic("alice", "A-secret");
    private static final String BOB = basic("bob", "B-secret");
    private static final String NTLM_TYPE_2 = "NTLM TlRMTVNTUAACAAAAAAAAACgAAAABggAAU3J2Tm9uY2UAAAAAAAAAAA==";
    private static final String TUNNEL = "<tunnel>";
    private static final String DROP = "<drop>";

    @Test
    void aRedirectToAnHttpsHostThatBypassesTheProxyIsSentOverTls() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(s -> ok("over-tls")); Peer proxy = new Peer()) {
            proxy.answer(s -> redirect("https://localhost:" + origin.port() + "/landing", false));

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServer(proxyServer("127.0.0.1", proxy.port()).setNonProxyHost("localhost")))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start").execute());

                assertEquals(1, origin.seen.size(), origin.seen::toString);
                assertTrue(origin.seen.get(0).tls, () -> "an https request went out in the clear: " + origin.seen);
                assertEquals("over-tls", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void anUpgradeToHttpsThatTheJdkSelectorSendsDirectIsSentOverTls() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(s -> ok("over-tls")); Peer proxy = new Peer()) {
            proxy.answer(s -> redirect("https://127.0.0.1:" + origin.port() + "/landing", false));
            ProxySelector previous = ProxySelector.getDefault();
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    // As with only http.proxyHost set.
                    return "http".equals(uri.getScheme())
                            ? List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxy.port())))
                            : List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                }
            });
            try (AsyncHttpClient client = asyncHttpClient(client().setUseProxySelector(true))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start").execute());

                assertEquals(1, origin.seen.size(), origin.seen::toString);
                assertTrue(origin.seen.get(0).tls, () -> "an https request went out in the clear: " + origin.seen);
                assertEquals("over-tls", response(outcome).getResponseBody());
            } finally {
                ProxySelector.setDefault(previous);
            }
        }
    }

    @Test
    void aRedirectToAnHttpsHostBehindTheProxyOpensATunnel() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(s -> ok("over-tls"));
             Peer direct = new Peer();
             Peer proxy = new Peer().answer(s -> s.connect() ? TUNNEL : status(502))) {
            direct.answer(s -> redirect("https://localhost:" + origin.port() + "/landing", false));

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServer(proxyServer("127.0.0.1", proxy.port()).setNonProxyHost("127.0.0.1")))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + direct.port() + "/start").execute());

                assertEquals(1, proxy.seen.size(), proxy.seen::toString);
                assertEquals("CONNECT localhost:" + origin.port() + " HTTP/1.1", proxy.seen.get(0).requestLine,
                        () -> "the proxy was not asked for a tunnel: " + proxy.seen);
                assertTrue(origin.seen.get(0).tls, origin.seen::toString);
                assertEquals("over-tls", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aRedirectRoutedThroughAnotherProxyGetsThatProxysLoginOnly() throws Exception {
        try (Peer proxyA = new Peer(); Peer proxyB = new Peer()) {
            proxyA.answer(s -> ALICE.equals(s.proxyAuthorization()) ? redirect("/next", true)
                    : challenge("Basic realm=\"a\""));
            proxyB.answer(s -> BOB.equals(s.proxyAuthorization()) ? ok("via-b") : challenge("Basic realm=\"b\""));
            ProxyServer a = proxy(proxyA, "alice", "A-secret");
            ProxyServer b = proxy(proxyB, "bob", "B-secret");

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? b : a))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxyA.port() + "/start").execute());

                assertOnlyBobReachedProxyB(proxyB);
                assertEquals("via-b", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aRedirectSelectedAsAnotherLoginOnTheSameProxyGoesAsThatLogin() throws Exception {
        try (Peer proxy = new Peer()) {
            proxy.answer(s -> s.target().endsWith("/start")
                    ? ALICE.equals(s.proxyAuthorization()) ? redirect("/next", true) : challenge("Basic realm=\"p\"")
                    : BOB.equals(s.proxyAuthorization()) ? ok("as-bob") : challenge("Basic realm=\"p\""));
            ProxyServer asAlice = proxy(proxy, "alice", "A-secret");
            ProxyServer asBob = proxy(proxy, "bob", "B-secret");

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? asBob : asAlice))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start").execute());

                assertTrue(proxy.seen.stream()
                                .noneMatch(s -> s.target().endsWith("/next") && ALICE.equals(s.proxyAuthorization())),
                        () -> "a request selected to go as bob went as alice: " + proxy.seen);
                assertEquals("as-bob", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aReplayRoutedThroughAnotherProxyGetsThatProxysLoginOnly() throws Exception {
        try (Peer proxyA = new Peer(); Peer proxyB = new Peer()) {
            proxyA.answer(s -> ALICE.equals(s.proxyAuthorization()) ? status(503) : challenge("Basic realm=\"a\""));
            proxyB.answer(s -> BOB.equals(s.proxyAuthorization()) ? ok("via-b") : challenge("Basic realm=\"b\""));
            ProxyServer a = proxy(proxyA, "alice", "A-secret");
            ProxyServer b = proxy(proxyB, "bob", "B-secret");
            AtomicBoolean failedOver = new AtomicBoolean();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> failedOver.get() ? b : a)
                    .addResponseFilter(new ResponseFilter() {
                        @Override
                        public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                            if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                                failedOver.set(true);
                                return new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true).build();
                            }
                            return ctx;
                        }
                    }))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxyA.port() + "/flaky").execute());

                assertOnlyBobReachedProxyB(proxyB);
                assertEquals("via-b", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aCrossOriginRedirectThroughTheSameProxyStillAuthenticatesToIt() throws Exception {
        try (Peer proxy = new Peer()) {
            proxy.answer(s -> !ALICE.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"p\"")
                    : s.target().endsWith("/start") ? redirect("http://localhost:" + proxy.port() + "/next", false)
                    : ok("next"));

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServer(proxy(proxy, "alice", "A-secret")))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start").execute());

                assertEquals(200, response(outcome).getStatusCode(), proxy.seen::toString);
                assertEquals("next", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aKeepAliveRedirectThroughTheSameProxyStaysOnItsAuthenticatedConnection() throws Exception {
        try (Peer proxy = new Peer()) {
            proxy.answer(s -> !ALICE.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"p\"")
                    : s.target().endsWith("/start") ? redirect("/next", false)
                    : ok("next"));

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServer(proxy(proxy, "alice", "A-secret")))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start").execute());

                assertEquals("next", response(outcome).getResponseBody());
                assertEquals(3, proxy.seen.size(), proxy.seen::toString);
                assertTrue(proxy.seen.stream().allMatch(s -> s.connection == 1), proxy.seen::toString);
                assertEquals(ALICE, proxy.seen.get(2).proxyAuthorization(), proxy.seen::toString);
            }
        }
    }

    @Test
    void aRedirectRoutedThroughAnHttpsProxyAtTheSameAddressReachesItOverTls() throws Exception {
        try (Peer proxy = new Peer(serverTls())) {
            proxy.answer(s -> s.target().endsWith("/start") ? redirect("/next", true) : ok("next"));
            ProxyServer plain = proxyServer("127.0.0.1", proxy.port()).build();
            ProxyServer secured = proxyServer("127.0.0.1", proxy.port()).setProxyType(ProxyType.HTTPS).build();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? secured : plain))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start").execute());

                assertEquals(2, proxy.seen.size(), proxy.seen::toString);
                assertTrue(proxy.seen.get(1).tls, () -> "the HTTPS proxy was sent plaintext: " + proxy.seen);
                assertEquals("next", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aTunnelOpenedThroughAnotherSecuredPortIsPooledUnderThatPort() throws Exception {
        try (Peer origin = new Peer(serverTls())
                .answer(s -> "/start".equals(s.target()) ? redirect("/next", true) : ok(s.target()));
             Peer tunnelsA = new Peer().answer(s -> s.connect() ? TUNNEL : status(502));
             Peer tunnelsB = new Peer().answer(s -> s.connect() ? TUNNEL : status(502))) {
            ProxyServer a = proxyServer("127.0.0.1", tunnelsA.port()).build();
            ProxyServer b = proxyServer("127.0.0.1", tunnelsA.port()).setSecuredPort(tunnelsB.port()).build();
            String base = "https://127.0.0.1:" + origin.port();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? b : a))) {
                Object first = outcome(client.prepareGet(base + "/start").execute());
                assertEquals("/next", response(first).getResponseBody());

                Object outcome = outcome(client.prepareGet(base + "/other").execute());

                assertEquals(2, tunnelsA.seen.size(), () -> "a request routed through A used B's tunnel: a="
                        + tunnelsA.seen + " b=" + tunnelsB.seen);
                assertEquals("/other", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aSelectorThatAlternatesKeepsATunnelOnTheProxyThatOpenedIt() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(s -> ok("over-tls"));
             Peer proxyA = new Peer().answer(s -> s.connect() ? TUNNEL : status(502));
             Peer proxyB = new Peer().answer(s -> s.connect() ? TUNNEL : status(502))) {
            ProxyServer a = proxyServer("127.0.0.1", proxyA.port()).build();
            ProxyServer b = proxyServer("127.0.0.1", proxyB.port()).build();
            AtomicInteger selections = new AtomicInteger();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> selections.getAndIncrement() % 2 == 0 ? a : b))) {
                Object outcome = outcome(client.prepareGet("https://127.0.0.1:" + origin.port() + "/x").execute());

                assertEquals("over-tls", response(outcome).getResponseBody());
                assertEquals(1, proxyA.seen.size() + proxyB.seen.size(),
                        () -> "a tunnel was opened again: a=" + proxyA.seen + " b=" + proxyB.seen);
            }
        }
    }

    @Test
    void anNtlmTokenMadeForTheProxyDoesNotReachAnOriginTheSelectorSendsDirect() throws Exception {
        try (Peer origin = new Peer().answer(s -> ok("direct"));
             Peer proxy = new Peer().answer(ProxyReselectionTest::ntlmThenRedirect)) {
            ProxyServer ntlm = ntlmProxy(proxy);

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? null : ntlm))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + origin.port() + "/start").execute());

                assertEquals(1, origin.seen.size(), origin.seen::toString);
                assertNull(origin.seen.get(0).proxyAuthorization(),
                        () -> "the proxy's NTLM token reached the origin: " + origin.seen);
                assertEquals("direct", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void roundRobinSendsARedirectTheSelectorRoutesThroughAProxyToTheProxy() throws Exception {
        try (Peer origin = new Peer().answer(s -> "/start".equals(s.target()) ? redirect("/next", true) : ok("origin"));
             Peer proxy = new Peer().answer(s -> ok("proxy"))) {
            ProxyServer b = proxyServer("127.0.0.1", proxy.port()).build();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setLoadBalance(LoadBalance.ROUND_ROBIN)
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? b : null))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + origin.port() + "/start").execute());

                assertEquals(1, origin.seen.size(), () -> "the origin was connected to directly: " + origin.seen);
                assertEquals(1, proxy.seen.size(), proxy.seen::toString);
                assertEquals("proxy", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aRetryRoutedThroughAnotherProxyPortFilesItsSocketUnderThatPort() throws Exception {
        AtomicBoolean down = new AtomicBoolean();
        try (Peer proxyA = new Peer(); Peer proxyB = new Peer().answer(s -> ok("via-b"))) {
            proxyA.answer(s -> dropTheFirstConnection(s, down, "via-a"));
            // Only the plain port tells them apart.
            ProxyServer a = proxyServer("127.0.0.1", proxyA.port()).build();
            ProxyServer b = proxyServer("127.0.0.1", proxyB.port()).setSecuredPort(proxyA.port()).build();
            String url = "http://127.0.0.1:" + proxyA.port() + "/x";

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setMaxRequestRetry(1)
                    .setProxyServerSelector(uri -> down.get() ? b : a))) {
                assertEquals("via-b", response(outcome(client.prepareGet(url).execute())).getResponseBody());

                down.set(false);
                Object outcome = outcome(client.prepareGet(url).execute());

                assertEquals(1, proxyB.seen.size(),
                        () -> "a request for proxy A was sent over proxy B's socket: " + proxyB.seen);
                assertEquals("via-a", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aRetryRoutedThroughAnotherProxyHostFilesItsSocketUnderThatHost() throws Exception {
        AtomicBoolean down = new AtomicBoolean();
        try (Peer proxy = new Peer()) {
            proxy.answer(s -> dropTheFirstConnection(s, down, "connection " + s.connection));
            // Two names for one loopback peer stand in for two proxy hosts on one port.
            ProxyServer a = proxyServer("127.0.0.1", proxy.port()).build();
            ProxyServer b = proxyServer("localhost", proxy.port()).build();
            String url = "http://127.0.0.1:" + proxy.port() + "/x";

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setMaxRequestRetry(1)
                    .setProxyServerSelector(uri -> down.get() ? b : a))) {
                assertEquals("connection 2", response(outcome(client.prepareGet(url).execute())).getResponseBody());

                down.set(false);
                Object outcome = outcome(client.prepareGet(url).execute());

                assertEquals("connection 3", response(outcome).getResponseBody(),
                        () -> "a request for proxy host A was sent over the socket opened to B: " + proxy.seen);
            }
        }
    }

    @Test
    void aKeepAliveRedirectSelectedThroughAnotherProxyStaysOnTheConnectionItHolds() throws Exception {
        try (Peer proxyA = new Peer(); Peer proxyB = new Peer().answer(s -> ok("via-b"))) {
            proxyA.answer(s -> !ALICE.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"a\"")
                    : s.target().endsWith("/start") ? redirect("/next", false)
                    : ok("via-a"));
            ProxyServer a = proxy(proxyA, "alice", "A-secret");
            ProxyServer b = proxy(proxyB, "bob", "B-secret");

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? b : a))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxyA.port() + "/start").execute());

                assertTrue(proxyA.seen.stream().noneMatch(s -> BOB.equals(s.proxyAuthorization())),
                        () -> "proxy B's login was sent over the connection to proxy A: " + proxyA.seen);
                assertEquals("via-a", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void anAnswerToAProxyChallengeGoesToTheProxyThatSentIt() throws Exception {
        // Bounds the exchange should it start alternating between the proxies, each challenging for its own login.
        AtomicInteger requests = new AtomicInteger();
        try (Peer proxyA = new Peer(); Peer proxyB = new Peer()) {
            proxyA.answer(s -> requests.incrementAndGet() > 4 ? status(403)
                    : ALICE.equals(s.proxyAuthorization()) ? ok("via-a") : challengeAndClose("Basic realm=\"a\""));
            proxyB.answer(s -> requests.incrementAndGet() > 4 ? status(403)
                    : BOB.equals(s.proxyAuthorization()) ? ok("via-b") : challengeAndClose("Basic realm=\"b\""));
            ProxyServer a = proxy(proxyA, "alice", "A-secret");
            ProxyServer b = proxy(proxyB, "bob", "B-secret");
            AtomicInteger selections = new AtomicInteger();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> selections.getAndIncrement() % 2 == 0 ? a : b))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxyA.port() + "/x").execute());

                assertTrue(proxyB.seen.isEmpty(), () -> "proxy A's challenge was answered to proxy B: " + proxyB.seen);
                assertEquals("via-a", response(outcome).getResponseBody(), proxyA.seen::toString);
            }
        }
    }

    @Test
    void aSelectorThatBuildsAnEqualProxyEachTimeKeepsTheLoginItAlreadySent() throws Exception {
        try (Peer proxy = new Peer()) {
            proxy.answer(s -> !ALICE.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"p\"")
                    : s.target().endsWith("/start") ? redirect("/next", true)
                    : ok("next"));

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> proxy(proxy, "alice", "A-secret")))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start").execute());

                assertEquals("next", response(outcome).getResponseBody());
                assertEquals(1, proxy.seen.stream().filter(s -> s.proxyAuthorization() == null).count(),
                        () -> "the login was challenged for again: " + proxy.seen);
            }
        }
    }

    @Test
    void anNtlmTokenMadeForTheProxyDoesNotReachTheNextProxyTheSelectorPicks() throws Exception {
        try (Peer proxyA = new Peer().answer(ProxyReselectionTest::ntlmThenRedirect);
             Peer proxyB = new Peer().answer(s -> ok("via-b"))) {
            ProxyServer ntlm = ntlmProxy(proxyA);
            ProxyServer b = proxyServer("127.0.0.1", proxyB.port()).build();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? b : ntlm))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxyA.port() + "/start").execute());

                assertEquals(1, proxyB.seen.size(), proxyB.seen::toString);
                assertNull(proxyB.seen.get(0).proxyAuthorization(),
                        () -> "proxy A's NTLM token reached proxy B: " + proxyB.seen);
                assertEquals("via-b", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aProxyLoginSetOnADirectRequestDoesNotFollowItOntoAProxy() throws Exception {
        try (Peer origin = new Peer().answer(s -> redirect("/next", true));
             Peer proxy = new Peer().answer(s -> ok("via-b"))) {
            ProxyServer b = proxyServer("127.0.0.1", proxy.port()).build();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> "/next".equals(uri.getPath()) ? b : null))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + origin.port() + "/start")
                        .setHeader("Proxy-Authorization", ALICE).execute());

                assertEquals(1, proxy.seen.size(), proxy.seen::toString);
                assertNull(proxy.seen.get(0).proxyAuthorization(),
                        () -> "the header followed onto the proxy: " + proxy.seen);
                assertEquals("via-b", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aProxyLoginTheRequestCarriesIsNotTunnelledToTheOriginAfterAMove() throws Exception {
        AtomicBoolean down = new AtomicBoolean();
        try (Peer origin = new Peer(serverTls()).answer(s -> ok("over-tls"));
             Peer proxyA = new Peer();
             Peer proxyB = new Peer().answer(s -> s.connect() ? TUNNEL : status(502))) {
            proxyA.answer(s -> {
                down.set(true);
                return DROP;
            });
            ProxyServer a = proxyServer("127.0.0.1", proxyA.port()).build();
            ProxyServer b = proxyServer("127.0.0.1", proxyB.port()).build();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setMaxRequestRetry(1)
                    .setProxyServerSelector(uri -> down.get() ? b : a))) {
                Object outcome = outcome(client.prepareGet("https://127.0.0.1:" + origin.port() + "/x")
                        .setHeader("Proxy-Authorization", ALICE).execute());

                assertTrue(proxyB.seen.stream().noneMatch(s -> ALICE.equals(s.proxyAuthorization())),
                        proxyB.seen::toString);
                assertEquals(1, origin.seen.size(), origin.seen::toString);
                assertNull(origin.seen.get(0).proxyAuthorization(),
                        () -> "A's login reached the origin: " + origin.seen);
                assertEquals("over-tls", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void anAnswerToAnOriginChallengeGoesBackThroughTheSameProxy() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Function<Seen, String> origin = s -> requests.incrementAndGet() > 4 ? status(403)
                : s.headers.containsKey("authorization") ? ok("via-" + s.connection)
                : "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"o\"\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        try (Peer proxyA = new Peer().answer(origin); Peer proxyB = new Peer().answer(origin)) {
            ProxyServer a = proxyServer("127.0.0.1", proxyA.port()).build();
            ProxyServer b = proxyServer("127.0.0.1", proxyB.port()).build();
            AtomicInteger selections = new AtomicInteger();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> selections.getAndIncrement() % 2 == 0 ? a : b))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxyA.port() + "/x")
                        .setRealm(basicAuthRealm("alice", "A-secret")).execute());

                assertTrue(proxyB.seen.isEmpty(),
                        () -> "the answer to the origin's challenge went through B: " + proxyB.seen);
                assertEquals("via-2", response(outcome).getResponseBody(), proxyA.seen::toString);
            }
        }
    }

    @Test
    void aKeepAliveRedirectThatStripsCredentialsStillAuthenticatesToTheProxy() throws Exception {
        try (Peer proxy = new Peer()) {
            proxy.answer(s -> !ALICE.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"p\"")
                    : s.target().endsWith("/start") ? redirect("/next", false)
                    : ok("next"));

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setStripAuthorizationOnRedirect(true)
                    .setProxyServer(proxy(proxy, "alice", "A-secret")))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start").execute());

                assertEquals(200, response(outcome).getStatusCode(), proxy.seen::toString);
                assertTrue(proxy.seen.stream().allMatch(s -> s.connection == 1), proxy.seen::toString);
            }
        }
    }

    @Test
    void anIoExceptionReplayRoutedThroughAnotherProxyGetsThatProxysLoginOnly() throws Exception {
        try (Peer proxyA = new Peer().answer(s -> DROP); Peer proxyB = new Peer()) {
            proxyB.answer(s -> BOB.equals(s.proxyAuthorization()) ? ok("via-b") : challenge("Basic realm=\"b\""));
            ProxyServer a = proxy(proxyA, "alice", "A-secret");
            ProxyServer b = proxy(proxyB, "bob", "B-secret");
            AtomicBoolean failedOver = new AtomicBoolean();

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setMaxRequestRetry(1)
                    .setProxyServerSelector(uri -> failedOver.get() ? b : a)
                    .addIOExceptionFilter(new IOExceptionFilter() {
                        @Override
                        public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                            failedOver.set(true);
                            return new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true).build();
                        }
                    }))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxyA.port() + "/x").execute());

                assertOnlyBobReachedProxyB(proxyB);
                assertEquals("via-b", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aReplayOntoAProxyConnectionAlreadyPooledCanStillAuthenticate() throws Exception {
        try (Peer proxyA = new Peer(); Peer proxyB = new Peer()) {
            proxyA.answer(s -> ALICE.equals(s.proxyAuthorization()) ? status(503) : challenge("Basic realm=\"a\""));
            proxyB.answer(s -> BOB.equals(s.proxyAuthorization()) ? ok("via-b") : challenge("Basic realm=\"b\""));
            ProxyServer a = proxy(proxyA, "alice", "A-secret");
            ProxyServer b = proxy(proxyB, "bob", "B-secret");
            AtomicBoolean failedOver = new AtomicBoolean(true);

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> failedOver.get() ? b : a)
                    .addResponseFilter(new ResponseFilter() {
                        @Override
                        public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                            if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                                failedOver.set(true);
                                return new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true).build();
                            }
                            return ctx;
                        }
                    }))) {
                String url = "http://127.0.0.1:" + proxyA.port() + "/flaky";
                // Leaves an authenticated connection to B in the pool.
                assertEquals("via-b", response(outcome(client.prepareGet(url).execute())).getResponseBody());
                failedOver.set(false);

                Object outcome = outcome(client.prepareGet(url).execute());

                assertEquals("via-b", response(outcome).getResponseBody(), proxyB.seen::toString);
                assertTrue(proxyB.seen.stream().allMatch(s -> s.connection == 1), proxyB.seen::toString);
            }
        }
    }

    @Test
    void aRedirectFromHttpsToHttpThroughTheSameProxyStillAuthenticatesToIt() throws Exception {
        try (Peer origin = new Peer(serverTls()); Peer proxy = new Peer()) {
            origin.answer(s -> redirect("http://localhost:" + proxy.port() + "/next", false));
            proxy.answer(s -> !ALICE.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"p\"")
                    : s.connect() ? TUNNEL
                    : ok("next"));

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServer(proxy(proxy, "alice", "A-secret")))) {
                Object outcome = outcome(client.prepareGet("https://127.0.0.1:" + origin.port() + "/start").execute());

                assertEquals(200, response(outcome).getStatusCode(), proxy.seen::toString);
                assertEquals("next", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aRedirectKeepsAProxySetOnTheRequestOverTheSelectorAndTunnelsThroughIt() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(s -> ok("over-tls"));
             Peer proxy = new Peer()) {
            proxy.answer(s -> s.connect() ? TUNNEL
                    : redirect("https://localhost:" + origin.port() + "/landing", false));

            try (AsyncHttpClient client = asyncHttpClient(client().setProxyServerSelector(uri -> null))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/start")
                        .setProxyServer(proxyServer("127.0.0.1", proxy.port())).execute());

                assertEquals("CONNECT localhost:" + origin.port() + " HTTP/1.1", proxy.seen.get(1).requestLine,
                        proxy.seen::toString);
                assertTrue(origin.seen.get(0).tls, origin.seen::toString);
                assertEquals("over-tls", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aReplayOntoAnHttpsHostThatBypassesTheProxyIsSentOverTls() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(s -> ok("over-tls"));
             Peer proxy = new Peer().answer(s -> status(503))) {
            ProxyServer a = proxyServer("127.0.0.1", proxy.port()).build();
            String failover = "https://localhost:" + origin.port() + "/landing";

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServerSelector(uri -> uri.isSecured() ? null : a)
                    .addResponseFilter(new ResponseFilter() {
                        @Override
                        public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                            if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                                return new FilterContext.FilterContextBuilder<>(ctx)
                                        .request(new RequestBuilder("GET").setUrl(failover).build())
                                        .replayRequest(true)
                                        .build();
                            }
                            return ctx;
                        }
                    }))) {
                Object outcome = outcome(client.prepareGet("http://127.0.0.1:" + proxy.port() + "/x").execute());

                assertEquals(1, origin.seen.size(), origin.seen::toString);
                assertTrue(origin.seen.get(0).tls, () -> "an https request went out in the clear: " + origin.seen);
                assertEquals("over-tls", response(outcome).getResponseBody());
            }
        }
    }

    @Test
    void aWebSocketRedirectToAWssHostThatBypassesTheProxyUpgradesOverTls() throws Exception {
        try (Peer origin = new Peer(serverTls());
             Peer first = new Peer();
             Peer proxy = new Peer().answer(s -> s.connect() ? TUNNEL : status(502))) {
            origin.answer(s -> "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + WebSocketUtils.getAcceptKey(s.headers.get("sec-websocket-key"))
                    + "\r\n\r\n");
            first.answer(s -> redirect("wss://localhost:" + origin.port() + "/ws", false));

            try (AsyncHttpClient client = asyncHttpClient(client()
                    .setProxyServer(proxyServer("127.0.0.1", proxy.port()).setNonProxyHost("localhost")))) {
                WebSocket webSocket = client.prepareGet("ws://127.0.0.1:" + first.port() + "/start")
                        .execute(new WebSocketUpgradeHandler.Builder().build())
                        .get(30, TimeUnit.SECONDS);
                webSocket.sendCloseFrame();

                assertEquals("CONNECT 127.0.0.1:" + first.port() + " HTTP/1.1", proxy.seen.get(0).requestLine,
                        proxy.seen::toString);
                assertEquals(1, origin.seen.size(), origin.seen::toString);
                assertTrue(origin.seen.get(0).tls, () -> "a wss upgrade went out in the clear: " + origin.seen);
            }
        }
    }

    private static String ntlmThenRedirect(Seen request) {
        String auth = request.proxyAuthorization();
        if (auth == null) {
            return challenge("NTLM");
        }
        if (auth.startsWith("NTLM TlRMTVNTUAAB")) {
            return challenge(NTLM_TYPE_2);
        }
        return auth.startsWith("NTLM TlRMTVNTUAAD") ? redirect("/next", true) : status(403);
    }

    private static ProxyServer ntlmProxy(Peer peer) {
        return proxyServer("127.0.0.1", peer.port())
                .setRealm(ntlmAuthRealm("Zaphod", "Beeblebrox").setNtlmDomain("Ursa-Minor").setNtlmHost("LightCity"))
                .build();
    }

    private static void assertOnlyBobReachedProxyB(Peer proxyB) {
        assertTrue(proxyB.seen.stream().noneMatch(s -> ALICE.equals(s.proxyAuthorization())),
                () -> "proxy A's login reached proxy B: " + proxyB.seen);
        assertTrue(proxyB.seen.stream().anyMatch(s -> BOB.equals(s.proxyAuthorization())),
                () -> "proxy B never got its own login: " + proxyB.seen);
    }

    private static String dropTheFirstConnection(Seen request, AtomicBoolean down, String body) {
        if (request.connection == 1) {
            down.set(true);
            return DROP;
        }
        return ok(body);
    }

    private static ProxyServer proxy(Peer peer, String user, String password) {
        return proxyServer("127.0.0.1", peer.port()).setRealm(basicAuthRealm(user, password)).build();
    }

    private static DefaultAsyncHttpClientConfig.Builder client() {
        return config()
                .setFollowRedirect(true)
                .setMaxRequestRetry(0)
                .setRequestTimeout(Duration.ofSeconds(20))
                .setUseInsecureTrustManager(true)
                .setDisableHttpsEndpointIdentificationAlgorithm(true);
    }

    // The response, or what failed it, so that a test can assert what the peers saw first.
    private static Object outcome(ListenableFuture<Response> future) throws InterruptedException, TimeoutException {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static Response response(Object outcome) {
        return assertInstanceOf(Response.class, outcome, () -> "the request failed: " + outcome);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ':' + password).getBytes(ISO_8859_1));
    }

    private static String ok(String body) {
        return "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
    }

    private static String status(int status) {
        return "HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\n\r\n";
    }

    private static String redirect(String location, boolean close) {
        return "HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\n"
                + (close ? "Connection: close\r\n" : "") + "\r\n";
    }

    private static String challenge(String proxyAuthenticate) {
        return "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: " + proxyAuthenticate
                + "\r\nContent-Length: 0\r\n\r\n";
    }

    private static String challengeAndClose(String proxyAuthenticate) {
        return "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: " + proxyAuthenticate
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    }

    private static SSLContext serverTls() throws Exception {
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream in = ProxyReselectionTest.class.getClassLoader().getResourceAsStream("ssltest-keystore.jks")) {
            keyStore.load(in, password);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static final class Seen {
        final int connection;
        final boolean tls;
        final String requestLine;
        final Map<String, String> headers;

        Seen(int connection, boolean tls, String requestLine, Map<String, String> headers) {
            this.connection = connection;
            this.tls = tls;
            this.requestLine = requestLine;
            this.headers = headers;
        }

        String target() {
            return requestLine.split(" ")[1];
        }

        boolean connect() {
            return requestLine.startsWith("CONNECT ");
        }

        @Nullable String proxyAuthorization() {
            return headers.get("proxy-authorization");
        }

        @Override
        public String toString() {
            return "#" + connection + (tls ? " tls " : " ") + requestLine
                    + (proxyAuthorization() != null ? " [Proxy-Authorization: " + proxyAuthorization() + ']' : "");
        }
    }

    /**
     * A loopback HTTP/1.1 peer standing in for a forward proxy or an origin. It records each request, and each
     * connection that opens with a TLS record, before answering; with a server TLS context it also accepts TLS.
     */
    private static final class Peer implements AutoCloseable {
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        private final ServerSocket server = new ServerSocket(0);
        private final @Nullable SSLContext tls;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();
        private volatile Function<Seen, String> answer = s -> status(500);

        Peer() throws IOException {
            this(null);
        }

        Peer(@Nullable SSLContext tls) throws IOException {
            this.tls = tls;
            Thread acceptor = new Thread(this::accept);
            acceptor.setDaemon(true);
            acceptor.start();
        }

        Peer answer(Function<Seen, String> answer) {
            this.answer = answer;
            return this;
        }

        int port() {
            return server.getLocalPort();
        }

        private void accept() {
            while (true) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException closed) {
                    return;
                }
                sockets.add(socket);
                int connection = connections.incrementAndGet();
                Thread worker = new Thread(() -> serve(socket, connection));
                worker.setDaemon(true);
                worker.start();
            }
        }

        private void serve(Socket socket, int connection) {
            try (Socket s = socket) {
                InputStream raw = s.getInputStream();
                int first = raw.read();
                if (first < 0) {
                    return;
                }
                InputStream consumed = new ByteArrayInputStream(new byte[]{(byte) first});
                if (first != 0x16) {
                    InputStream in = new BufferedInputStream(new SequenceInputStream(consumed, raw));
                    exchange(connection, false, in, s.getOutputStream());
                } else if (tls == null) {
                    seen.add(new Seen(connection, true, "TLS ClientHello", Collections.emptyMap()));
                } else {
                    Socket secured = tls.getSocketFactory().createSocket(s, consumed, true);
                    InputStream in = new BufferedInputStream(secured.getInputStream());
                    exchange(connection, true, in, secured.getOutputStream());
                }
            } catch (IOException ignored) {
                // the client went away
            }
        }

        private void exchange(int connection, boolean secured, InputStream in, OutputStream out) throws IOException {
            for (String requestLine = readLine(in); requestLine != null; requestLine = readLine(in)) {
                Map<String, String> headers = new HashMap<>();
                for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                    int colon = line.indexOf(':');
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    headers.put(name, line.substring(colon + 1).trim());
                }
                Seen request = new Seen(connection, secured, requestLine, headers);
                seen.add(request);
                String response = answer.apply(request);
                if (DROP.equals(response)) {
                    return;
                }
                if (TUNNEL.equals(response)) {
                    tunnel(request.target(), in, out);
                    return;
                }
                out.write(response.getBytes(ISO_8859_1));
                out.flush();
                if (response.startsWith("HTTP/1.1 101 ")) {
                    // WebSocket frames follow, which this peer does not read.
                    pump(in, OutputStream.nullOutputStream());
                    return;
                }
                if (response.contains("\r\nConnection: close\r\n")) {
                    return;
                }
            }
        }

        private void tunnel(String authority, InputStream in, OutputStream out) throws IOException {
            int colon = authority.lastIndexOf(':');
            int port = Integer.parseInt(authority.substring(colon + 1));
            try (Socket upstream = new Socket(authority.substring(0, colon), port)) {
                sockets.add(upstream);
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(ISO_8859_1));
                out.flush();
                InputStream fromUpstream = upstream.getInputStream();
                Thread back = new Thread(() -> pump(fromUpstream, out));
                back.setDaemon(true);
                back.start();
                pump(in, upstream.getOutputStream());
            }
        }

        private static void pump(InputStream from, OutputStream to) {
            byte[] buffer = new byte[8192];
            try {
                for (int n = from.read(buffer); n >= 0; n = from.read(buffer)) {
                    to.write(buffer, 0, n);
                    to.flush();
                }
            } catch (IOException ignored) {
                // either side went away
            }
        }

        private static @Nullable String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b = in.read(); b >= 0; b = in.read()) {
                if (b == '\n') {
                    String text = line.toString(ISO_8859_1);
                    return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
                }
                line.write(b);
            }
            return line.size() == 0 ? null : line.toString(ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}
