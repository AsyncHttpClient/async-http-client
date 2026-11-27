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
package org.asynchttpclient.proxy;

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.DefaultRequest;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.RequestBuilderBase;
import org.asynchttpclient.Response;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.IOExceptionFilter;
import org.asynchttpclient.filter.ResponseFilter;
import org.asynchttpclient.testserver.SocksProxy;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.digestAuthRealm;
import static org.asynchttpclient.Dsl.ntlmAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CONNECT and a {@code Proxy-Authorization} are for the proxy. Once a tunnel is up its far end is the origin, so
 * neither may be written into it, however the attempt that opens the tunnel got there: a filter replaying the proxy's
 * answer to an authenticated CONNECT, or the retry of a 407 that closed the connection while another exchange pooled
 * a tunnel to the same origin.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
public class ProxyConnectRetryTest {

    private static final String USER = "alice";
    private static final String PASSWORD = "proxy-secret";
    private static final String LOGIN = "Basic " + Base64.getEncoder()
            .encodeToString((USER + ':' + PASSWORD).getBytes(ISO_8859_1));
    private static final String NTLM_TYPE_2 = "NTLM TlRMTVNTUAACAAAAAAAAACgAAAABggAAU3J2Tm9uY2UAAAAAAAAAAA==";
    private static final String TUNNEL = "<tunnel>";
    private static final String DROP = "<drop>";
    private static final long TIMEOUT_SECONDS = 30;

    @Test
    public void replayingTheProxyAnswerToAnAuthenticatedConnectOpensANewTunnelForTheRequest() throws Exception {
        AtomicInteger authenticatedConnects = new AtomicInteger();
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> !LOGIN.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"p\"", false)
                     : authenticatedConnects.incrementAndGet() == 1 ? status(503) : TUNNEL);
             AsyncHttpClient client = asyncHttpClient(client(proxy).addResponseFilter(new ReplayOn503()))) {

            Response response = response(outcome(client.prepareGet(origin.url()).execute()));

            assertNothingButTheRequestReached(origin);
            assertEquals(200, response.getStatusCode(), () -> "origin saw " + origin.seen);
            assertEquals("over-tls", response.getResponseBody());
            assertEquals(3, proxy.connects(), () -> "proxy saw " + proxy.seen);
        }
    }

    @Test
    public void replayingTheProxyAnswerNeverTakesAPooledTunnel() throws Exception {
        CountDownLatch heldArrived = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> {
                 if (s.connection != 1) {
                     return LOGIN.equals(s.proxyAuthorization()) ? TUNNEL : challenge("Basic realm=\"p\"", false);
                 }
                 if (s.proxyAuthorization() != null) {
                     return status(503);
                 }
                 // The first exchange's challenge waits until the second has pooled its tunnel.
                 heldArrived.countDown();
                 await(otherDone);
                 return challenge("Basic realm=\"p\"", false);
             });
             AsyncHttpClient client = asyncHttpClient(client(proxy).addResponseFilter(new ReplayOn503()))) {

            ListenableFuture<Response> held = client.prepareGet(origin.url()).execute();
            assertTrue(heldArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Response other = response(outcome(client.prepareGet(origin.url()).execute()));
            otherDone.countDown();
            Response replayed = response(outcome(held));

            assertNothingButTheRequestReached(origin);
            assertEquals(200, other.getStatusCode());
            assertEquals(200, replayed.getStatusCode(), () -> "origin saw " + origin.seen);
            assertEquals(2, origin.seen.size(), () -> "origin saw " + origin.seen);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void replayingTheProxyAnswerSendsTheApplicationsRequest(boolean filterEdits) throws Exception {
        AtomicInteger authenticatedConnects = new AtomicInteger();
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> !LOGIN.equals(s.proxyAuthorization())
                     ? "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"p\"\r\n"
                     + "Set-Cookie: sid=; Max-Age=0\r\nSet-Cookie: pref=; Max-Age=0\r\nContent-Length: 0\r\n\r\n"
                     : authenticatedConnects.incrementAndGet() == 1 ? status(503) : TUNNEL);
             AsyncHttpClient client = asyncHttpClient(client(proxy).addResponseFilter(new ResponseFilter() {
                 @Override
                 public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                     if (ctx.getResponseStatus() == null || ctx.getResponseStatus().getStatusCode() != 503) {
                         return ctx;
                     }
                     FilterContext.FilterContextBuilder<T> replay = new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true);
                     return filterEdits ? replay.request(ctx.getRequest().toBuilder().setHeader("X-Replayed", "yes").build()).build()
                             : replay.build();
                 }
             }))) {

            Response response = response(outcome(client.preparePost(origin.url())
                    .setHeader("Cookie", "sid=caller")
                    .addCookie(new DefaultCookie("pref", "1"))
                    .setBody("payload")
                    .execute()));

            assertEquals(200, response.getStatusCode(), () -> "origin saw " + origin.seen);
            assertOnlyReached(origin, true, "POST /x HTTP/1.1");
            assertEquals(1, origin.seen.size(), () -> "origin saw " + origin.seen);
            Seen request = origin.seen.get(0);
            assertEquals("payload", request.body);
            String cookies = request.headers.get("cookie");
            assertTrue(cookies != null && cookies.contains("sid=caller") && cookies.contains("pref=1"), cookies);
            assertEquals(filterEdits ? "yes" : null, request.headers.get("x-replayed"));
        }
    }

    // The filter's own copy keeps the URI as it is, so only AHC's copy of the replay could encode it again.
    @ParameterizedTest(name = "disableUrlEncoding={0}")
    @ValueSource(booleans = {true, false})
    public void replayingTheProxyAnswerKeepsTheRequestTarget(boolean disableUrlEncoding) throws Exception {
        AtomicInteger authenticatedConnects = new AtomicInteger();
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> !LOGIN.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"p\"", false)
                     : authenticatedConnects.incrementAndGet() == 1 ? status(503) : TUNNEL);
             AsyncHttpClient client = asyncHttpClient(client(proxy)
                     .setDisableUrlEncodingForBoundRequests(disableUrlEncoding)
                     .addResponseFilter(replayOn503(ProxyConnectRetryTest::rawCopyMarkedAsReplayed)))) {

            Response response = response(outcome(client.prepareGet(rawTarget(origin)).execute()));

            assertReachedOnceAsReplayed(origin, disableUrlEncoding);
            assertEquals(200, response.getStatusCode());
        }
    }

    // The same copy, for an IOException filter replaying a CONNECT whose connection was lost mid-handshake.
    @ParameterizedTest(name = "disableUrlEncoding={0}")
    @ValueSource(booleans = {true, false})
    public void replayingAConnectThatLostItsConnectionKeepsTheRequestTarget(boolean disableUrlEncoding) throws Exception {
        AtomicInteger type1Seen = new AtomicInteger();
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> {
                 String auth = s.proxyAuthorization();
                 if (auth == null) {
                     return challenge("NTLM", false);
                 }
                 if (auth.startsWith("NTLM TlRMTVNTUAAB")) {
                     return type1Seen.getAndIncrement() == 0 ? DROP : challenge(NTLM_TYPE_2, false);
                 }
                 return auth.startsWith("NTLM TlRMTVNTUAAD") ? TUNNEL : status(403);
             });
             AsyncHttpClient client = asyncHttpClient(client(proxy, ntlm())
                     .setMaxRequestRetry(1)
                     .setDisableUrlEncodingForBoundRequests(disableUrlEncoding)
                     .addIOExceptionFilter(replayOnIOException(ProxyConnectRetryTest::rawCopyMarkedAsReplayed)))) {

            Response response = response(outcome(client.prepareGet(rawTarget(origin)).execute()));

            assertReachedOnceAsReplayed(origin, disableUrlEncoding);
            assertEquals(200, response.getStatusCode());
            assertEquals(2, type1Seen.get(), () -> "proxy saw " + proxy.seen);
        }
    }

    @Test
    public void replayingTheProxyAnswerCopiesARequestSubclassThroughItsToBuilder() throws Exception {
        AtomicInteger authenticatedConnects = new AtomicInteger();
        AtomicInteger toBuilderCalls = new AtomicInteger();
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> !LOGIN.equals(s.proxyAuthorization()) ? challenge("Basic realm=\"p\"", false)
                     : authenticatedConnects.incrementAndGet() == 1 ? status(503) : TUNNEL);
             AsyncHttpClient client = asyncHttpClient(client(proxy)
                     .addResponseFilter(replayOn503(request -> countingToBuilder(request, toBuilderCalls))))) {

            Response response = response(outcome(client.prepareGet(origin.url()).execute()));

            assertEquals(200, response.getStatusCode(), () -> "origin saw " + origin.seen);
            assertNothingButTheRequestReached(origin);
            assertEquals(1, toBuilderCalls.get());
        }
    }

    @Test
    public void replayingTheProxyAnswerStartsTheProxyHandshakeOver() throws Exception {
        AtomicInteger type1Seen = new AtomicInteger();
        AtomicInteger type3Seen = new AtomicInteger();
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> {
                 String auth = s.proxyAuthorization();
                 if (auth == null) {
                     return challenge("NTLM", false);
                 }
                 if (auth.startsWith("NTLM TlRMTVNTUAAB")) {
                     type1Seen.incrementAndGet();
                     return challenge(NTLM_TYPE_2, false);
                 }
                 if (!auth.startsWith("NTLM TlRMTVNTUAAD")) {
                     return status(403);
                 }
                 return type3Seen.getAndIncrement() == 0 ? status(503) : TUNNEL;
             });
             AsyncHttpClient client = asyncHttpClient(client(proxy, ntlm()).addResponseFilter(new ReplayOn503()))) {

            Response response = response(outcome(client.prepareGet(origin.url()).execute()));

            assertNothingButTheRequestReached(origin);
            assertEquals(200, response.getStatusCode(), () -> "proxy saw " + proxy.seen);
            assertEquals(2, type1Seen.get(), () -> "proxy saw " + proxy.seen);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "socks"})
    public void replayingTheProxyAnswerOntoAnotherRouteNeverSendsTheConnectToTheOrigin(String route) throws Exception {
        SocksProxy socks = new SocksProxy(60_000);
        Thread socksThread = new Thread(() -> {
            try {
                socks.run();
            } catch (IOException ignored) {
                // stopped
            }
        });
        socksThread.setDaemon(true);
        socksThread.start();
        ProxyServer failover = "socks".equals(route)
                ? proxyServer("127.0.0.1", socks.getPort()).setProxyType(ProxyType.SOCKS_V4).build() : null;
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> s.proxyAuthorization() == null ? challenge("NTLM", false) : status(503));
             AsyncHttpClient client = asyncHttpClient(direct().addResponseFilter(new ResponseFilter() {
                 @Override
                 public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                     if (ctx.getResponseStatus() == null || ctx.getResponseStatus().getStatusCode() != 503) {
                         return ctx;
                     }
                     Request failingOver = ctx.getRequest().toBuilder().setProxyServer(failover).build();
                     return new FilterContext.FilterContextBuilder<>(ctx).request(failingOver).replayRequest(true).build();
                 }
             }))) {

            Response response = response(outcome(client.prepareGet(origin.url())
                    .setProxyServer(proxyServer("127.0.0.1", proxy.port()).setRealm(ntlm()))
                    .execute()));

            assertNothingButTheRequestReached(origin);
            assertEquals(200, response.getStatusCode(), () -> "origin saw " + origin.seen);
            assertTrue(proxy.seen.stream().anyMatch(s -> s.proxyAuthorization() != null), () -> "proxy saw " + proxy.seen);
        } finally {
            socks.stop();
        }
    }

    @Test
    public void aConnectTheApplicationSendsIsAnsweredByTheProxy() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> LOGIN.equals(s.proxyAuthorization()) ? TUNNEL : challenge("Basic realm=\"p\"", false));
             AsyncHttpClient client = asyncHttpClient(client(proxy))) {

            Response response = response(outcome(client.prepareConnect(origin.url()).execute()));

            assertEquals(200, response.getStatusCode(), () -> "origin saw " + origin.seen);
            assertTrue(origin.seen.isEmpty(), () -> "origin saw " + origin.seen);
            assertEquals(2, proxy.connects(), () -> "proxy saw " + proxy.seen);

            assertEquals(200, response(outcome(client.prepareGet(origin.url()).execute())).getStatusCode());
            assertNothingButTheRequestReached(origin);
        }
    }

    @Test
    public void replayingAConnectThatLostItsConnectionMidHandshakeKeepsTheLoginOutOfTheTunnel() throws Exception {
        AtomicInteger type1Seen = new AtomicInteger();
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> {
                 String auth = s.proxyAuthorization();
                 if (auth == null) {
                     return challenge("NTLM", false);
                 }
                 if (auth.startsWith("NTLM TlRMTVNTUAAB")) {
                     return type1Seen.getAndIncrement() == 0 ? DROP : challenge(NTLM_TYPE_2, false);
                 }
                 return auth.startsWith("NTLM TlRMTVNTUAAD") ? TUNNEL : status(403);
             });
             AsyncHttpClient client = asyncHttpClient(config()
                     .setMaxRequestRetry(1)
                     .setRequestTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                     .setUseInsecureTrustManager(true)
                     .setDisableHttpsEndpointIdentificationAlgorithm(true)
                     .setProxyServer(proxyServer("127.0.0.1", proxy.port()).setRealm(ntlm()))
                     .addIOExceptionFilter(new ReplayOnIOException()))) {

            Response response = response(outcome(client.prepareGet(origin.url()).execute()));

            assertNothingButTheRequestReached(origin);
            assertEquals(200, response.getStatusCode(), () -> "origin saw " + origin.seen);
            assertEquals(2, type1Seen.get(), () -> "proxy saw " + proxy.seen);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "chunked", "http/1.0"})
    public void retryOfA407ThatClosedTheConnectionNeverTakesAPooledTunnel(String closing) throws Exception {
        assertA407RetryNeverTakesAPooledTunnel(basicAuthRealm(USER, PASSWORD), "Basic realm=\"p\"", LOGIN::equals, closing);
    }

    @Test
    public void digestRetryOfA407ThatClosedTheConnectionNeverTakesAPooledTunnel() throws Exception {
        assertA407RetryNeverTakesAPooledTunnel(digestAuthRealm(USER, PASSWORD), "Digest realm=\"p\", nonce=\"n0\", qop=\"auth\"",
                auth -> auth != null && auth.startsWith("Digest "), "close");
    }

    private static void assertA407RetryNeverTakesAPooledTunnel(Realm.Builder realm, String challenge,
                                                               Predicate<String> authenticated, String closing) throws Exception {
        CountDownLatch heldArrived = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> {
                 if (authenticated.test(s.proxyAuthorization())) {
                     return TUNNEL;
                 }
                 if (s.connection != 1) {
                     return challenge(challenge, false);
                 }
                 // The first exchange's challenge waits until the second has pooled its tunnel.
                 heldArrived.countDown();
                 await(otherDone);
                 return closingChallenge(closing, challenge);
             });
             AsyncHttpClient client = asyncHttpClient(client(proxy, realm))) {

            ListenableFuture<Response> held = client.prepareGet(origin.url()).execute();
            assertTrue(heldArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Response other = response(outcome(client.prepareGet(origin.url()).execute()));
            otherDone.countDown();
            Response retried = response(outcome(held));

            assertNothingButTheRequestReached(origin);
            assertEquals(200, other.getStatusCode());
            assertEquals(200, retried.getStatusCode(), () -> "origin saw " + origin.seen);
            assertEquals("over-tls", retried.getResponseBody());
            assertEquals(2, origin.seen.size(), () -> "origin saw " + origin.seen);
        }
    }

    @Test
    public void aProxyAuthorizationTheRequestCarriesGoesToTheProxyOnly() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> LOGIN.equals(s.proxyAuthorization()) ? TUNNEL : status(403));
             AsyncHttpClient client = asyncHttpClient(client(proxy))) {

            Response response = response(outcome(client.prepareGet(origin.url())
                    .setProxyServer(proxyServer("127.0.0.1", proxy.port()))
                    .setHeader("Proxy-Authorization", LOGIN)
                    .execute()));

            assertNothingButTheRequestReached(origin);
            assertEquals(200, response.getStatusCode(), () -> "proxy saw " + proxy.seen);
        }
    }

    @Test
    public void aProxyAuthorizationTheRequestCarriesStaysOutOfAWebSocketTunnel() throws Exception {
        try (Peer origin = new Peer().answer(ProxyConnectRetryTest::webSocketAnswer);
             Peer proxy = new Peer().answer(s -> LOGIN.equals(s.proxyAuthorization()) ? TUNNEL : status(403));
             AsyncHttpClient client = asyncHttpClient(direct())) {

            WebSocket webSocket = client.prepareGet("ws://127.0.0.1:" + origin.port() + "/x")
                    .setProxyServer(proxyServer("127.0.0.1", proxy.port()))
                    .setHeader("Proxy-Authorization", LOGIN)
                    .execute(new WebSocketUpgradeHandler.Builder().build())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertTrue(webSocket.isOpen());
            assertOnlyReached(origin, false, "GET /x HTTP/1.1");
            assertEquals(1, origin.seen.size(), () -> "origin saw " + origin.seen);
        }
    }

    @Test
    public void aProxyAuthorizationTheRequestCarriesGoesToAnHttpsProxyOnly() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer(serverTls()).answer(s -> LOGIN.equals(s.proxyAuthorization()) ? TUNNEL : status(403));
             AsyncHttpClient client = asyncHttpClient(direct())) {

            Response response = response(outcome(client.prepareGet(origin.url())
                    .setProxyServer(proxyServer("127.0.0.1", proxy.port()).setProxyType(ProxyType.HTTPS))
                    .setHeader("Proxy-Authorization", LOGIN)
                    .execute()));

            assertNothingButTheRequestReached(origin);
            assertEquals(200, response.getStatusCode(), () -> "proxy saw " + proxy.seen);
            assertTrue(proxy.seen.stream().allMatch(s -> s.tls && s.connect()), () -> "proxy saw " + proxy.seen);
        }
    }

    @Test
    public void challengedConnectOpensATunnel() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> LOGIN.equals(s.proxyAuthorization()) ? TUNNEL : challenge("Basic realm=\"p\"", false));
             AsyncHttpClient client = asyncHttpClient(client(proxy))) {

            Response response = response(outcome(client.prepareGet(origin.url()).execute()));

            assertEquals(200, response.getStatusCode());
            assertEquals("over-tls", response.getResponseBody());
            assertNothingButTheRequestReached(origin);
            assertEquals(2, proxy.connects(), () -> "proxy saw " + proxy.seen);
            assertEquals(1, proxy.connections.get(), () -> "proxy saw " + proxy.seen);
        }
    }

    @Test
    public void sequentialRequestsShareOneTunnel() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> LOGIN.equals(s.proxyAuthorization()) ? TUNNEL : challenge("Basic realm=\"p\"", false));
             AsyncHttpClient client = asyncHttpClient(client(proxy))) {

            assertEquals(200, response(outcome(client.prepareGet(origin.url()).execute())).getStatusCode());
            assertEquals(200, response(outcome(client.prepareGet(origin.url()).execute())).getStatusCode());

            assertNothingButTheRequestReached(origin);
            assertEquals(2, origin.seen.size(), () -> "origin saw " + origin.seen);
            assertEquals(1, origin.connections.get(), () -> "origin saw " + origin.seen);
            assertEquals(2, proxy.connects(), () -> "proxy saw " + proxy.seen);
        }
    }

    @Test
    public void proxyErrorWithoutAFilterReachesTheApplication() throws Exception {
        try (Peer origin = new Peer(serverTls()).answer(ProxyConnectRetryTest::originAnswer);
             Peer proxy = new Peer().answer(s -> LOGIN.equals(s.proxyAuthorization()) ? status(503) : challenge("Basic realm=\"p\"", false));
             AsyncHttpClient client = asyncHttpClient(client(proxy))) {

            Response response = response(outcome(client.prepareGet(origin.url()).execute()));

            assertEquals(503, response.getStatusCode());
            assertTrue(origin.seen.isEmpty(), () -> "origin saw " + origin.seen);
            assertEquals(2, proxy.connects(), () -> "proxy saw " + proxy.seen);
        }
    }

    private static void assertNothingButTheRequestReached(Peer origin) {
        assertOnlyReached(origin, true, "GET /x HTTP/1.1");
    }

    private static void assertOnlyReached(Peer origin, boolean tls, String requestLine) {
        assertTrue(origin.seen.stream().allMatch(s -> s.tls == tls && requestLine.equals(s.requestLine) && s.proxyAuthorization() == null),
                () -> "origin saw " + origin.seen);
    }

    private static String rawTarget(Peer origin) {
        return "https://127.0.0.1:" + origin.port() + "/a|b?q={x}";
    }

    private static Request rawCopyMarkedAsReplayed(Request request) {
        return new RawCopy(request).setHeader("X-Replayed", "yes").build();
    }

    private static void assertReachedOnceAsReplayed(Peer origin, boolean disableUrlEncoding) {
        assertOnlyReached(origin, true, disableUrlEncoding ? "GET /a|b?q={x} HTTP/1.1" : "GET /a%7Cb?q=%7Bx%7D HTTP/1.1");
        assertEquals(1, origin.seen.size(), () -> "origin saw " + origin.seen);
        assertEquals("yes", origin.seen.get(0).headers.get("x-replayed"), () -> "the filter's request was not sent: " + origin.seen);
    }

    private static String originAnswer(Seen request) {
        return request.connect() ? status(405) : ok("over-tls");
    }

    private static String webSocketAnswer(Seen request) {
        String key = request.headers.get("sec-websocket-key");
        if (request.connect() || key == null) {
            return status(405);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(ISO_8859_1));
            return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(digest) + "\r\n\r\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DefaultAsyncHttpClientConfig.Builder client(Peer proxy) {
        return client(proxy, basicAuthRealm(USER, PASSWORD));
    }

    private static DefaultAsyncHttpClientConfig.Builder client(Peer proxy, Realm.Builder realm) {
        return direct().setProxyServer(proxyServer("127.0.0.1", proxy.port()).setRealm(realm));
    }

    private static DefaultAsyncHttpClientConfig.Builder direct() {
        return config()
                .setMaxRequestRetry(0)
                .setRequestTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .setUseInsecureTrustManager(true)
                .setDisableHttpsEndpointIdentificationAlgorithm(true);
    }

    private static Realm.Builder ntlm() {
        return ntlmAuthRealm("Zaphod", "Beeblebrox").setNtlmDomain("Ursa-Minor").setNtlmHost("LightCity");
    }

    private static Object outcome(ListenableFuture<Response> future) throws InterruptedException, TimeoutException {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static Response response(Object outcome) {
        return assertInstanceOf(Response.class, outcome, () -> "the request failed: " + outcome);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String ok(String body) {
        return "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
    }

    private static String status(int status) {
        return "HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\n\r\n";
    }

    private static String challenge(String proxyAuthenticate, boolean close) {
        return "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: " + proxyAuthenticate
                + "\r\nContent-Length: 0\r\n" + (close ? "Connection: close\r\n" : "") + "\r\n";
    }

    private static String closingChallenge(String closing, String proxyAuthenticate) {
        switch (closing) {
            case "close":
                return challenge(proxyAuthenticate, true);
            case "chunked":
                return "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: " + proxyAuthenticate + "\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n6\r\ndenied\r\n0\r\n\r\n";
            default:
                return "HTTP/1.0 407 Proxy Authentication Required\r\nProxy-Authenticate: " + proxyAuthenticate + "\r\n"
                        + "Content-Length: 0\r\n\r\n";
        }
    }

    private static SSLContext serverTls() throws Exception {
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream in = ProxyConnectRetryTest.class.getClassLoader().getResourceAsStream("ssltest-keystore.jks")) {
            keyStore.load(in, password);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static final class ReplayOn503 implements ResponseFilter {
        @Override
        public <T> FilterContext<T> filter(FilterContext<T> ctx) {
            if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                return new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true).build();
            }
            return ctx;
        }
    }

    /** Replays a 503 with the request the given function makes of the one the filter is shown. */
    private static ResponseFilter replayOn503(Function<Request, Request> replay) {
        return new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getResponseStatus() == null || ctx.getResponseStatus().getStatusCode() != 503) {
                    return ctx;
                }
                return new FilterContext.FilterContextBuilder<>(ctx).request(replay.apply(ctx.getRequest()))
                        .replayRequest(true).build();
            }
        };
    }

    /** Replays an IOException with the request the given function makes of the one the filter is shown. */
    private static IOExceptionFilter replayOnIOException(Function<Request, Request> replay) {
        return new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                return new FilterContext.FilterContextBuilder<>(ctx).request(replay.apply(ctx.getRequest()))
                        .replayRequest(true).build();
            }
        };
    }

    /** Copies a request with its URI as it is, as a filter that keeps a URI built without encoding has to. */
    private static final class RawCopy extends RequestBuilderBase<RawCopy> {
        RawCopy(Request prototype) {
            super(prototype, true, false);
        }
    }

    private static Request countingToBuilder(Request built, AtomicInteger toBuilderCalls) {
        return new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(), built.getLocalAddress(),
                built.getHeaders(), built.getCookies(), built.getByteData(), built.getCompositeByteData(),
                built.getStringData(), built.getByteBufferData(), built.getByteBufData(), built.getStreamData(),
                built.getBodyGenerator(), built.getFormParams(), built.getBodyParts(), built.getVirtualHost(),
                built.getProxyServer(), built.getRealm(), built.getFile(), built.getFollowRedirect(),
                built.getRequestTimeout(), built.getReadTimeout(), built.getRangeOffset(), built.getCharset(),
                built.getChannelPoolPartitioning(), built.getNameResolver()) {
            @Override
            public RequestBuilder toBuilder() {
                toBuilderCalls.incrementAndGet();
                return super.toBuilder();
            }
        };
    }

    private static final class ReplayOnIOException implements IOExceptionFilter {
        @Override
        public <T> FilterContext<T> filter(FilterContext<T> ctx) {
            return new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true).build();
        }
    }

    private static final class Seen {
        final int connection;
        final boolean tls;
        final String requestLine;
        final Map<String, String> headers;
        final String body;

        Seen(int connection, boolean tls, String requestLine, Map<String, String> headers, String body) {
            this.connection = connection;
            this.tls = tls;
            this.requestLine = requestLine;
            this.headers = headers;
            this.body = body;
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
     * A loopback HTTP/1.1 peer standing in for a forward proxy or an origin. It records each request before answering;
     * with a server TLS context it accepts TLS, and as a proxy it tunnels a CONNECT it answers with {@link #TUNNEL}.
     */
    private static final class Peer implements AutoCloseable {
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        final AtomicInteger connections = new AtomicInteger();
        private final ServerSocket server = new ServerSocket(0);
        private final @Nullable SSLContext tls;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
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

        String url() {
            return "https://127.0.0.1:" + port() + "/x";
        }

        long connects() {
            return seen.stream().filter(Seen::connect).count();
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
                    exchange(connection, false, new BufferedInputStream(new SequenceInputStream(consumed, raw)), s.getOutputStream());
                } else if (tls == null) {
                    seen.add(new Seen(connection, true, "TLS ClientHello", Collections.emptyMap(), ""));
                } else {
                    Socket secured = tls.getSocketFactory().createSocket(s, consumed, true);
                    exchange(connection, true, new BufferedInputStream(secured.getInputStream()), secured.getOutputStream());
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
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
                String length = headers.get("content-length");
                byte[] body = new byte[length != null ? Integer.parseInt(length) : 0];
                for (int read = 0; read < body.length; ) {
                    int n = in.read(body, read, body.length - read);
                    if (n < 0) {
                        return;
                    }
                    read += n;
                }
                Seen request = new Seen(connection, secured, requestLine, headers, new String(body, ISO_8859_1));
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
                if (response.startsWith("HTTP/1.0 ") || response.contains("\r\nConnection: close\r\n")) {
                    return;
                }
            }
        }

        private void tunnel(String authority, InputStream in, OutputStream out) throws IOException {
            int colon = authority.lastIndexOf(':');
            try (Socket upstream = new Socket(authority.substring(0, colon), Integer.parseInt(authority.substring(colon + 1)))) {
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
