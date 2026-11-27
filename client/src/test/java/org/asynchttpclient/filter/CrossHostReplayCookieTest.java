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
package org.asynchttpclient.filter;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultRequest;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.uri.Uri;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cookies the client takes from its store for a request were selected for that request's URI. A request a
 * filter sends elsewhere, whether a replay of a clone or a retargeted request, must carry the store's cookies for
 * where it goes and none of those for where it was going, while the cookies the application or the filter set stay.
 * <p>
 * The two origins are 127.0.0.1 and localhost on the same port: cookies do not tell ports apart, hosts they do.
 */
public class CrossHostReplayCookieTest extends AbstractBasicTest {

    private static final String RECEIVED_COOKIE = "received-cookie";

    // The handler changes it while a request is in flight, as another exchange would.
    private volatile CookieStore store = new ThreadSafeCookieStore();

    @Override
    @BeforeAll
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector http = addHttpConnector(server);
        ServerConnector https = addHttpsConnector(server);
        server.setHandler(configureHandler());
        server.start();
        port1 = http.getLocalPort();
        port2 = https.getLocalPort();
    }

    @Override
    @AfterAll
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
    }

    @BeforeEach
    public void newStore() {
        store = new ThreadSafeCookieStore();
    }

    @Override
    public AbstractHandler configureHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
                baseRequest.setHandled(true);
                switch (target) {
                    case "/fail":
                        response.setStatus(503);
                        break;
                    case "/fail-rotating":
                        response.addHeader("Set-Cookie", "A_SESSION=rotated; Path=/");
                        response.setStatus(503);
                        break;
                    case "/hop":
                        response.setStatus(302);
                        response.setHeader("Location", "/fail");
                        break;
                    case "/to-a":
                        response.setStatus(302);
                        response.setHeader("Location", a("/fail"));
                        break;
                    case "/to-sink":
                        response.setStatus(302);
                        response.setHeader("Location", "/sink");
                        break;
                    case "/fail-sticky":
                        response.addHeader("Set-Cookie", "STICKY=x; Path=/");
                        response.setStatus(503);
                        break;
                    case "/fail-after-401":
                        if (request.getHeader("Authorization") == null) {
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Basic realm=\"test\"");
                        } else {
                            response.setStatus(503);
                        }
                        break;
                    case "/rotated-before-407":
                        // This server is the proxy too: the request is sent to it in absolute form.
                        if (request.getHeader("Proxy-Authorization") == null) {
                            add(store, a("/"), "A_SESSION=fresh; Path=/");
                            response.setStatus(407);
                            response.setHeader("Proxy-Authenticate", "Basic realm=\"proxy\"");
                            break;
                        }
                        echoCookie(request, response);
                        break;
                    case "/drop":
                        baseRequest.getHttpChannel().abort(new IOException("closed without a response"));
                        break;
                    case "/rotated-in-flight":
                        add(store, a("/"), "A_SESSION=fresh; Path=/");
                        response.setStatus(302);
                        response.setHeader("Location", "/sink");
                        break;
                    case "/deleted-in-flight":
                        store.remove(cookie -> "A_SESSION".equals(cookie.name()));
                        response.setStatus(302);
                        response.setHeader("Location", "/sink");
                        break;
                    case "/rotated-before-401":
                        if (request.getHeader("Authorization") == null) {
                            add(store, a("/"), "A_SESSION=fresh; Path=/");
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Basic realm=\"test\"");
                            break;
                        }
                        echoCookie(request, response);
                        break;
                    default:
                        echoCookie(request, response);
                }
            }
        };
    }

    private static void echoCookie(HttpServletRequest request, HttpServletResponse response) {
        String cookie = request.getHeader("Cookie");
        if (cookie != null) {
            response.setHeader(RECEIVED_COOKIE, cookie);
        }
    }

    private String a(String path) {
        return "http://127.0.0.1:" + port1 + path;
    }

    private String b(String path) {
        return "http://localhost:" + port1 + path;
    }

    private String secureA(String path) {
        return "https://127.0.0.1:" + port2 + path;
    }

    private static void add(CookieStore store, String url, String setCookie) {
        store.add(Uri.create(url), ClientCookieDecoder.STRICT.decode(setCookie));
    }

    /** Replays a 503 with what {@code clone} makes of a copy of the request that got it. */
    private static ResponseFilter replayOn503(UnaryOperator<RequestBuilder> clone) {
        return new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                    return new FilterContext.FilterContextBuilder<>(ctx)
                            .request(clone.apply(ctx.getRequest().toBuilder()).build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
    }

    private ResponseFilter failoverToB() {
        return replayOn503(r -> r.setUrl(b("/sink")));
    }

    private RequestFilter retargetToB() {
        return retargetTo(b("/sink"));
    }

    private static RequestFilter retargetTo(String target) {
        return new RequestFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                return new FilterContext.FilterContextBuilder<>(ctx)
                        .request(ctx.getRequest().toBuilder().setUrl(target).build())
                        .build();
            }
        };
    }

    /** Hands out a builder of the first request it sees, as the client is about to send it. */
    private static RequestFilter capture(AtomicReference<RequestBuilder> sent) {
        return new RequestFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                sent.compareAndSet(null, ctx.getRequest().toBuilder());
                return ctx;
            }
        };
    }

    /** Replays a request to /drop, which the server closes without a response, onto B. */
    private IOExceptionFilter failoverToBOnIOException() {
        String target = b("/sink");
        return new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getIOException() != null && "/drop".equals(ctx.getRequest().getUri().getPath())) {
                    return new FilterContext.FilterContextBuilder<>(ctx)
                            .request(ctx.getRequest().toBuilder().setUrl(target).build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };
    }

    private Response get(DefaultAsyncHttpClientConfig.Builder config, RequestBuilder request) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            return client.executeRequest(request).get(TIMEOUT, TimeUnit.SECONDS);
        }
    }

    /** The pairs of the Cookie header the server that answered got, which the encoder orders by path. */
    private static Set<String> cookiesReceived(Response response, String expectedHost) {
        assertEquals(200, response.getStatusCode());
        assertEquals(expectedHost, response.getUri().getHost(), "the host that answered");
        Set<String> pairs = new HashSet<>();
        String header = response.getHeader(RECEIVED_COOKIE);
        if (header != null) {
            for (String pair : header.split(";")) {
                pairs.add(pair.trim());
            }
        }
        return pairs;
    }

    @Test
    public void aCloneReplayedToAnotherHostDoesNotCarryTheStoresCookiesForTheFirst() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store).addResponseFilter(failoverToB()),
                new RequestBuilder().setUrl(a("/fail")));

        assertEquals(Set.of(), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aCloneReplayedToAnotherHostCarriesTheStoresCookiesForIt() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, b("/"), "B_SESSION=bob; Path=/");

        Response response = get(config().setCookieStore(store).addResponseFilter(failoverToB()),
                new RequestBuilder().setUrl(a("/fail")));

        assertEquals(Set.of("B_SESSION=bob"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void theCallersCookiesFollowACloneReplayedToAnotherHost() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store).addResponseFilter(failoverToB()),
                new RequestBuilder().setUrl(a("/fail"))
                        .addCookie(new DefaultCookie("CALLER", "mine"))
                        .setHeader("Cookie", "HDR=raw"));

        assertEquals(Set.of("CALLER=mine", "HDR=raw"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aCookieTheFilterAddsToTheCloneFollowsIt() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, b("/"), "B_SESSION=bob; Path=/");

        Response response = get(config().setCookieStore(store).addResponseFilter(
                        replayOn503(r -> r.setUrl(b("/sink")).addCookie(new DefaultCookie("FOR_B", "x")))),
                new RequestBuilder().setUrl(a("/fail")));

        assertEquals(Set.of("FOR_B=x", "B_SESSION=bob"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aReplayToTheSameHostSendsTheValueTheFailingResponseRotated() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store)
                        .addResponseFilter(replayOn503(r -> r.setUrl(a("/sink")))),
                new RequestBuilder().setUrl(a("/fail-rotating")));

        assertEquals(Set.of("A_SESSION=rotated"), cookiesReceived(response, "127.0.0.1"));
    }

    @Test
    public void aReplayToTheSameHostDoesNotAddBackStoredCookiesTheFilterRemoved() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store).addResponseFilter(replayOn503(r -> {
                    r.resetCookies();
                    return r.setUrl(a("/sink"));
                })),
                new RequestBuilder().setUrl(a("/fail")).addCookie(new DefaultCookie("CALLER", "mine")));

        assertEquals(Set.of(), cookiesReceived(response, "127.0.0.1"));
    }

    @Test
    public void aReplayToTheSameHostDoesNotAddBackAStoredCookieTheFilterDropped() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, a("/"), "A_PREF=1; Path=/");
        String target = a("/sink");
        ResponseFilter dropSession = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                if (ctx.getResponseStatus() != null && ctx.getResponseStatus().getStatusCode() == 503) {
                    List<Cookie> kept = new ArrayList<>(ctx.getRequest().getCookies());
                    kept.removeIf(cookie -> "A_SESSION".equals(cookie.name()));
                    return new FilterContext.FilterContextBuilder<>(ctx)
                            .request(ctx.getRequest().toBuilder().setCookies(kept).setUrl(target).build())
                            .replayRequest(true)
                            .build();
                }
                return ctx;
            }
        };

        Response response = get(config().setCookieStore(store).addResponseFilter(dropSession),
                new RequestBuilder().setUrl(a("/fail")));

        assertEquals(Set.of("A_PREF=1"), cookiesReceived(response, "127.0.0.1"));
    }

    @Test
    public void aCloneReplayedToAnotherHostAfterASameOriginRedirectDoesNotCarryTheStoresCookies() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store).setFollowRedirect(true)
                        .addResponseFilter(failoverToB()),
                new RequestBuilder().setUrl(a("/hop")));

        assertEquals(Set.of(), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aCloneReplayedToAnotherHostAfterACrossOriginRedirectDoesNotCarryTheStoresCookies() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, b("/"), "B_SESSION=bob; Path=/");

        Response response = get(config().setCookieStore(store).setFollowRedirect(true)
                        .addResponseFilter(failoverToB()),
                new RequestBuilder().setUrl(b("/to-a")));

        assertEquals(Set.of("B_SESSION=bob"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aRequestOfAnotherTypeReplayedToAnotherHostDoesNotCarryTheStoresCookies() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        var built = new RequestBuilder().setUrl(a("/fail")).build();
        // Not a DefaultRequest, so the client adds the store's cookies through its builder.
        DefaultRequest request = new DefaultRequest(built.getMethod(), built.getUri(), built.getAddress(),
                built.getLocalAddress(), built.getHeaders(), built.getCookies(), built.getByteData(),
                built.getCompositeByteData(), built.getStringData(), built.getByteBufferData(), built.getByteBufData(),
                built.getStreamData(), built.getBodyGenerator(), built.getFormParams(), built.getBodyParts(),
                built.getVirtualHost(), built.getProxyServer(), built.getRealm(), built.getFile(),
                built.getFollowRedirect(), built.getRequestTimeout(), built.getReadTimeout(), built.getRangeOffset(),
                built.getCharset(), built.getChannelPoolPartitioning(), built.getNameResolver()) {
        };

        try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(store)
                .addResponseFilter(failoverToB()))) {
            Response response = client.executeRequest(request).get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(Set.of(), cookiesReceived(response, "localhost"));
        }
    }

    @Test
    public void aRequestFilterThatRetargetsTheRequestGetsTheStoresCookiesForTheNewHost() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, b("/"), "B_SESSION=bob; Path=/");
        RequestFilter retarget = retargetToB();

        Response response = get(config().setCookieStore(store).addRequestFilter(retarget),
                new RequestBuilder().setUrl(a("/sink")).addCookie(new DefaultCookie("CALLER", "mine")));

        assertEquals(Set.of("CALLER=mine", "B_SESSION=bob"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aSecureCookieDoesNotFollowACloneReplayedFromHttpsToHttp() throws Exception {
        add(store, secureA("/"), "S_SESSION=top; Path=/; Secure");
        add(store, secureA("/"), "PLAIN=ok; Path=/");

        Response response = get(config().setCookieStore(store).setUseInsecureTrustManager(true)
                        .addResponseFilter(replayOn503(r -> r.setUrl(a("/sink")))),
                new RequestBuilder().setUrl(secureA("/fail")));

        assertEquals(Set.of("PLAIN=ok"), cookiesReceived(response, "127.0.0.1"));
        assertEquals("http", response.getUri().getScheme());
    }

    @Test
    public void aCloneReplayedToAnotherHostByAnIOExceptionFilterDoesNotCarryTheStoresCookies() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        IOExceptionFilter failover = failoverToBOnIOException();

        Response response = get(config().setCookieStore(store).addIOExceptionFilter(failover),
                new RequestBuilder().setUrl(a("/drop")).addCookie(new DefaultCookie("CALLER", "mine")));

        assertEquals(Set.of("CALLER=mine"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aStoreOfTheApplicationsOwnThatHandsOutCopiesIsBoundToTheNewHostToo() throws Exception {
        CookieStore copying = new CopyingStore("none");
        add(copying, a("/"), "A_SESSION=alice; Path=/");
        add(copying, b("/"), "B_SESSION=bob; Path=/");

        Response response = get(config().setCookieStore(copying).addResponseFilter(failoverToB()),
                new RequestBuilder().setUrl(a("/fail")));

        assertEquals(Set.of("B_SESSION=bob"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aStoreFailingForTheHostARequestFilterRetargetsToFailsTheRequest() throws Exception {
        CookieStore failing = new CopyingStore("localhost");
        RequestFilter retarget = retargetToB();
        AtomicReference<Throwable> notified = new AtomicReference<>();

        try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(failing).addRequestFilter(retarget))) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                    .executeRequest(new RequestBuilder().setUrl(a("/sink")), new AsyncCompletionHandlerBase() {
                        @Override
                        public void onThrowable(Throwable t) {
                            notified.set(t);
                        }
                    })
                    .get(TIMEOUT, TimeUnit.SECONDS));

            assertEquals(CopyingStore.FAILURE, thrown.getCause().getMessage());
            assertSame(thrown.getCause(), notified.get());
        }
    }

    @Test
    public void aStoreFailingForTheHostAReplayGoesToFailsTheExchange() throws Exception {
        CookieStore failing = new CopyingStore("localhost");
        add(failing, a("/"), "A_SESSION=alice; Path=/");
        IOExceptionFilter failover = failoverToBOnIOException();

        try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(failing)
                .addIOExceptionFilter(failover))) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                    .executeRequest(new RequestBuilder().setUrl(a("/drop")))
                    .get(TIMEOUT, TimeUnit.SECONDS));

            assertEquals(CopyingStore.FAILURE, thrown.getCause().getMessage());
        }
    }

    @Test
    public void aRequestTheClientBuiltReExecutedToAnotherHostDoesNotCarryTheStoresCookiesForTheFirst()
            throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, b("/"), "A_SESSION=bob; Path=/");
        AtomicReference<RequestBuilder> sent = new AtomicReference<>();

        try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(store).addRequestFilter(capture(sent)))) {
            client.executeRequest(new RequestBuilder().setUrl(a("/sink"))).get(TIMEOUT, TimeUnit.SECONDS);
            Response response = client.executeRequest(sent.get().setUrl(b("/sink"))).get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(Set.of("A_SESSION=bob"), cookiesReceived(response, "localhost"));
        }
    }

    @Test
    public void aSecureCookieDoesNotFollowARequestTheClientBuiltReExecutedOverHttp() throws Exception {
        add(store, secureA("/"), "S_SESSION=top; Path=/; Secure");
        AtomicReference<RequestBuilder> sent = new AtomicReference<>();

        try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(store).setUseInsecureTrustManager(true)
                .addRequestFilter(capture(sent)))) {
            client.executeRequest(new RequestBuilder().setUrl(secureA("/sink"))).get(TIMEOUT, TimeUnit.SECONDS);
            Response response = client.executeRequest(sent.get().setUrl(b("/sink"))).get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(Set.of(), cookiesReceived(response, "localhost"));
        }
    }

    @Test
    public void aClientWithoutAStoreDoesNotSendTheStoresCookiesAnotherClientAdded() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        AtomicReference<RequestBuilder> sent = new AtomicReference<>();
        try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(store).addRequestFilter(capture(sent)))) {
            client.executeRequest(new RequestBuilder().setUrl(a("/sink"))
                    .addCookie(new DefaultCookie("CALLER", "mine"))).get(TIMEOUT, TimeUnit.SECONDS);
        }

        try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(null))) {
            Response response = client.executeRequest(sent.get().setUrl(b("/sink"))).get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(Set.of("CALLER=mine"), cookiesReceived(response, "localhost"));
        }
    }

    @Test
    public void aRequestFilterThatRetargetsFromHttpsToHttpDoesNotSendASecureCookie() throws Exception {
        add(store, secureA("/"), "S_SESSION=top; Path=/; Secure");
        add(store, secureA("/"), "PLAIN=ok; Path=/");

        Response response = get(config().setCookieStore(store).setUseInsecureTrustManager(true)
                        .addRequestFilter(retargetTo(a("/sink"))),
                new RequestBuilder().setUrl(secureA("/sink")));

        assertEquals(Set.of("PLAIN=ok"), cookiesReceived(response, "127.0.0.1"));
        assertEquals("http", response.getUri().getScheme());
    }

    @Test
    public void aRequestFilterThatChangesOnlyThePathGetsTheStoresCookiesForTheNewPath() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, a("/p"), "SCOPED=p; Path=/p");

        Response response = get(config().setCookieStore(store).addRequestFilter(retargetTo(a("/q/sink"))),
                new RequestBuilder().setUrl(a("/p/sink")));

        assertEquals(Set.of("A_SESSION=alice"), cookiesReceived(response, "127.0.0.1"));
    }

    @Test
    public void aRequestFilterReadsTheStoresCookiesAsDefaultCookiesWithTheirAttributes() throws Exception {
        add(store, secureA("/"), "S_SESSION=top; Path=/; Secure; SameSite=Strict; Partitioned");
        AtomicReference<List<Cookie>> seen = new AtomicReference<>();
        RequestFilter inspect = new RequestFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                seen.set(ctx.getRequest().getCookies());
                return ctx;
            }
        };

        Response response = get(config().setCookieStore(store).setUseInsecureTrustManager(true)
                        .addRequestFilter(inspect),
                new RequestBuilder().setUrl(secureA("/sink")));

        assertEquals(Set.of("S_SESSION=top"), cookiesReceived(response, "127.0.0.1"));
        DefaultCookie cookie = assertInstanceOf(DefaultCookie.class, seen.get().get(0));
        assertEquals(SameSite.Strict, cookie.sameSite());
        assertTrue(cookie.isPartitioned());
    }

    @Test
    public void aReplayToTheSameHostSendsACookieTheFailingResponseSetUnderANewName() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store)
                        .addResponseFilter(replayOn503(r -> r.setUrl(a("/sink")))),
                new RequestBuilder().setUrl(a("/fail-sticky")));

        assertEquals(Set.of("A_SESSION=alice", "STICKY=x"), cookiesReceived(response, "127.0.0.1"));
    }

    @Test
    public void aCallersCookieEqualToAStoredOneStaysTheCallersAfterASameOriginRedirect() throws Exception {
        add(store, a("/"), "PREF=1; Path=/");
        DefaultAsyncHttpClientConfig.Builder config = config().setCookieStore(store).setFollowRedirect(true)
                .addResponseFilter(failoverToB());

        Response withoutRedirect = get(config,
                new RequestBuilder().setUrl(a("/fail")).addCookie(new DefaultCookie("PREF", "1")));
        Response afterRedirect = get(config,
                new RequestBuilder().setUrl(a("/hop")).addCookie(new DefaultCookie("PREF", "1")));

        assertEquals(Set.of("PREF=1"), cookiesReceived(withoutRedirect, "localhost"));
        assertEquals(Set.of("PREF=1"), cookiesReceived(afterRedirect, "localhost"));
    }

    @Test
    public void aStoreFailingForTheHostAReplayGoesToSendsNothingThereAndLeavesItsPooledConnection() throws Exception {
        CopyingStore failing = new CopyingStore("none");
        add(failing, a("/"), "A_SESSION=alice; Path=/");
        // One event loop, so that a task queued on it runs after the replay that failed has returned.
        EventLoopGroup loop = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try (RawOrigin origin = new RawOrigin()) {
            String b = "http://localhost:" + origin.port();
            try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(failing).setEventLoopGroup(loop)
                    .addResponseFilter(replayOn503(r -> r.setUrl(b + "/sink"))))) {
                client.executeRequest(new RequestBuilder().setUrl(b + "/warm")).get(TIMEOUT, TimeUnit.SECONDS);
                failing.failingHost = "localhost";

                ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                        .executeRequest(new RequestBuilder().setUrl(a("/fail")))
                        .get(TIMEOUT, TimeUnit.SECONDS));
                assertEquals(CopyingStore.FAILURE, thrown.getCause().getMessage());
                loop.submit(() -> { }).get(TIMEOUT, TimeUnit.SECONDS);
                failing.failingHost = "none";
                client.executeRequest(new RequestBuilder().setUrl(b + "/again")).get(TIMEOUT, TimeUnit.SECONDS);
            }

            assertEquals(List.of("/warm cookie=null", "/again cookie=null"),
                    origin.firstConnection.get(TIMEOUT, TimeUnit.SECONDS));
        } finally {
            loop.shutdownGracefully(0, 0, TimeUnit.SECONDS).get(TIMEOUT, TimeUnit.SECONDS);
        }
    }

    @Test
    public void aStoreFailingForTheHostARequestFilterRetargetsToReleasesWhatTheFiltersHold() throws Exception {
        CookieStore failing = new CopyingStore("localhost");
        try (AsyncHttpClient client = asyncHttpClient(config().setCookieStore(failing)
                .addRequestFilter(new ThrottleRequestFilter(1, 1000))
                .addRequestFilter(retargetTo(b("/sink"))))) {
            for (int i = 0; i < 2; i++) {
                ExecutionException thrown = assertThrows(ExecutionException.class, () -> client
                        .executeRequest(new RequestBuilder().setUrl(a("/sink")))
                        .get(TIMEOUT, TimeUnit.SECONDS));

                assertEquals(CopyingStore.FAILURE, thrown.getCause().getMessage(), "request " + i);
            }
        }
    }

    @Test
    public void aProxyAuthenticationRetrySendsTheValueTheStoreHoldsNow() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store),
                new RequestBuilder().setUrl(a("/rotated-before-407"))
                        .setProxyServer(proxyServer("127.0.0.1", port1)
                                .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false))));

        assertEquals(Set.of("A_SESSION=fresh"), cookiesReceived(response, "127.0.0.1"));
    }

    @Test
    public void aRedirectAfterAReplayToAnotherHostCarriesOnlyThatHostsCookies() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, b("/"), "B_SESSION=bob; Path=/");

        Response response = get(config().setCookieStore(store).setFollowRedirect(true)
                        .addResponseFilter(replayOn503(r -> r.setUrl(b("/to-sink")))),
                new RequestBuilder().setUrl(a("/fail")).addCookie(new DefaultCookie("CALLER", "mine")));

        assertEquals(Set.of("CALLER=mine", "B_SESSION=bob"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aReplayToAnotherHostAfterAnAuthenticationRetryDoesNotCarryTheStoresCookiesForTheFirst()
            throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");
        add(store, b("/"), "B_SESSION=bob; Path=/");

        Response response = get(config().setCookieStore(store).addResponseFilter(failoverToB()),
                new RequestBuilder().setUrl(a("/fail-after-401"))
                        .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false)));

        assertEquals(Set.of("B_SESSION=bob"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void withoutAStoreEveryCookieFollowsACloneReplayedToAnotherHost() throws Exception {
        Response response = get(config().setCookieStore(null).addResponseFilter(failoverToB()),
                new RequestBuilder().setUrl(a("/fail"))
                        .addCookie(new DefaultCookie("CALLER", "mine"))
                        .setHeader("Cookie", "HDR=raw"));

        assertEquals(Set.of("CALLER=mine", "HDR=raw"), cookiesReceived(response, "localhost"));
    }

    @Test
    public void aRedirectSendsTheValueTheStoreHoldsNowRatherThanTheOneTheRequestWentOutWith() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store).setFollowRedirect(true),
                new RequestBuilder().setUrl(a("/rotated-in-flight")));

        assertEquals(Set.of("A_SESSION=fresh"), cookiesReceived(response, "127.0.0.1"));
    }

    @Test
    public void aRedirectDoesNotResendACookieDeletedFromTheStoreInFlight() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store).setFollowRedirect(true),
                new RequestBuilder().setUrl(a("/deleted-in-flight")).addCookie(new DefaultCookie("CALLER", "mine")));

        assertEquals(Set.of("CALLER=mine"), cookiesReceived(response, "127.0.0.1"));
    }

    @Test
    public void anAuthenticationRetrySendsTheValueTheStoreHoldsNow() throws Exception {
        add(store, a("/"), "A_SESSION=alice; Path=/");

        Response response = get(config().setCookieStore(store),
                new RequestBuilder().setUrl(a("/rotated-before-401"))
                        .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false)));

        assertEquals(Set.of("A_SESSION=fresh"), cookiesReceived(response, "127.0.0.1"));
    }

    /** A store of the application's own: it hands out copies, and fails for one host. */
    private static final class CopyingStore implements CookieStore {

        static final String FAILURE = "the store failed";

        private final CookieStore stored = new ThreadSafeCookieStore();
        volatile String failingHost;

        CopyingStore(String failingHost) {
            this.failingHost = failingHost;
        }

        @Override
        public void add(Uri uri, Cookie cookie) {
            stored.add(uri, cookie);
        }

        @Override
        public List<Cookie> get(Uri uri) {
            if (failingHost.equals(uri.getHost())) {
                throw new IllegalStateException(FAILURE);
            }
            List<Cookie> copies = new ArrayList<>();
            for (Cookie cookie : stored.get(uri)) {
                DefaultCookie copy = new DefaultCookie(cookie.name(), cookie.value());
                copy.setPath(cookie.path());
                copies.add(copy);
            }
            return copies;
        }

        @Override
        public List<Cookie> getAll() {
            return stored.getAll();
        }

        @Override
        public boolean remove(Predicate<Cookie> predicate) {
            return stored.remove(predicate);
        }

        @Override
        public boolean clear() {
            return stored.clear();
        }

        @Override
        public void evictExpired() {
            stored.evictExpired();
        }

        @Override
        public int incrementAndGet() {
            return stored.incrementAndGet();
        }

        @Override
        public int decrementAndGet() {
            return stored.decrementAndGet();
        }

        @Override
        public int count() {
            return stored.count();
        }
    }

    /** An origin that records what it reads on the first connection, up to the moment the client closes it. */
    private static final class RawOrigin implements AutoCloseable {

        final CompletableFuture<List<String>> firstConnection = new CompletableFuture<>();
        private final ServerSocket socket = new ServerSocket(0);

        RawOrigin() throws IOException {
            Thread thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            boolean first = true;
            while (!socket.isClosed()) {
                Socket connection;
                try {
                    connection = socket.accept();
                } catch (IOException e) {
                    return;
                }
                CompletableFuture<List<String>> requests = first ? firstConnection : new CompletableFuture<>();
                first = false;
                Thread worker = new Thread(() -> handle(connection, requests));
                worker.setDaemon(true);
                worker.start();
            }
        }

        private static void handle(Socket socket, CompletableFuture<List<String>> requests) {
            List<String> seen = new ArrayList<>();
            try (Socket connection = socket) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), ISO_8859_1));
                String requestLine;
                while ((requestLine = in.readLine()) != null) {
                    String cookie = null;
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Cookie:", 0, 7)) {
                            cookie = line.substring(7).trim();
                        }
                    }
                    seen.add(requestLine.split(" ")[1] + " cookie=" + cookie);
                    connection.getOutputStream()
                            .write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(ISO_8859_1));
                    connection.getOutputStream().flush();
                }
            } catch (IOException e) {
                // The client reset the connection rather than closing it: what was read is still the answer.
            }
            requests.complete(seen);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
