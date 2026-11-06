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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.handler.codec.http.cookie.DefaultCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request a redirect leads to must carry what the redirect response just stored, not the cookies the
 * previous request went out with, whether the redirect keeps the method or switches to GET.
 */
public class RedirectCookieRotationTest extends AbstractBasicTest {

    private static final String RECEIVED_COOKIE = "received-cookie";

    @Override
    public AbstractHandler configureHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
                switch (target) {
                    case "/seed":
                        response.addHeader("Set-Cookie", "SID=old; Path=/");
                        response.addHeader("Set-Cookie", "P=scoped; Path=/p");
                        break;
                    case "/login":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=new; Path=/", "/home");
                        break;
                    case "/login-307":
                        redirect(response, 307, "SID=new; Path=/", "/home");
                        break;
                    case "/logout":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=; Path=/; Max-Age=0", "/home");
                        break;
                    case "/logout-303":
                        redirect(response, HttpServletResponse.SC_SEE_OTHER, "SID=; Path=/; Max-Age=0", "/home");
                        break;
                    case "/logout-307":
                        redirect(response, 307, "SID=; Path=/; Max-Age=0", "/home");
                        break;
                    case "/p/a":
                        redirect(response, HttpServletResponse.SC_FOUND, null, "/q/b");
                        break;
                    case "/bounce":
                        redirect(response, HttpServletResponse.SC_FOUND, null, "/home");
                        break;
                    case "/bounce-307":
                        redirect(response, 307, null, "/home");
                        break;
                    case "/reject-domain":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=evil; Domain=example.org; Path=/", "/home");
                        break;
                    case "/other-path":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=other; Path=/elsewhere", "/home");
                        break;
                    case "/other-path-same-value":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=old; Path=/elsewhere", "/home");
                        break;
                    case "/reject-domain-delete":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=; Domain=example.org; Path=/; Max-Age=0",
                                "/home");
                        break;
                    case "/other-path-delete":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=; Path=/elsewhere; Max-Age=0", "/home");
                        break;
                    case "/seed-root-p":
                        response.addHeader("Set-Cookie", "P=root; Path=/");
                        break;
                    case "/p/logout":
                        redirect(response, HttpServletResponse.SC_FOUND, "P=; Path=/p; Max-Age=0", "/home");
                        break;
                    case "/rotate-lower":
                        redirect(response, HttpServletResponse.SC_FOUND, "sid=new; Path=/", "/home");
                        break;
                    case "/delete-lower":
                        redirect(response, HttpServletResponse.SC_FOUND, "sid=; Path=/; Max-Age=0", "/home");
                        break;
                    case "/unparseable":
                        redirect(response, HttpServletResponse.SC_FOUND, "x=y; Domain=ex\u00e4mple.com", "/home");
                        break;
                    case "/see-other":
                        redirect(response, HttpServletResponse.SC_SEE_OTHER, null, "/home");
                        break;
                    case "/elsewhere":
                        redirect(response, HttpServletResponse.SC_FOUND, null, "http://127.0.0.1:" + port1 + "/home");
                        break;
                    default:
                        String cookie = request.getHeader("Cookie");
                        if (cookie != null) {
                            response.setHeader(RECEIVED_COOKIE, cookie);
                        }
                }
                baseRequest.setHandled(true);
            }
        };
    }

    @Test
    void aGetRedirectSendsTheSessionItRotated() throws Exception {
        assertEquals("SID=new", afterSeeding(client -> client.prepareGet(url("/login"))));
    }

    @Test
    void a307SendsTheSessionItRotated() throws Exception {
        assertEquals("SID=new", afterSeeding(client -> client.preparePost(url("/login-307"))));
    }

    @Test
    void aGetRedirectDoesNotResendACookieItDeleted() throws Exception {
        assertNull(afterSeeding(client -> client.prepareGet(url("/logout"))));
    }

    @Test
    void a307DoesNotResendACookieItDeleted() throws Exception {
        assertNull(afterSeeding(client -> client.preparePost(url("/logout-307"))));
    }

    @Test
    void aPathScopedCookieDoesNotFollowARedirectOutOfItsPath() throws Exception {
        String received = afterSeeding(client -> client.prepareGet(url("/p/a")));
        assertFalse(received != null && received.contains("P="), "sent to /q/b: " + received);
    }

    /** A Cookie header pair is the caller's even when the store holds the same cookie, so it may leave its path. */
    @Test
    void aRawPairEqualToAStoredCookieFollowsARedirectOutOfItsPath() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("P=scoped", "SID=old")),
                cookiesSent(withRawCookieAfterSeeding("P=scoped", client -> client.prepareGet(url("/p/a")))));
    }

    /** The store folds cookie names, so a redirect naming SID as sid still replaces it. */
    @Test
    void aRedirectReplacesACookieItNamesInAnotherCase() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("sid=new")),
                cookiesSent(afterSeeding(client -> client.prepareGet(url("/rotate-lower")))));
        assertNull(afterSeeding(client -> client.prepareGet(url("/delete-lower"))));
    }

    /** The decoder throws on a non-ASCII Domain; that drops the cookie, not the redirect. */
    @Test
    void aSetCookieTheDecoderRejectsDoesNotFailTheRedirect() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            Response home = client.prepareGet(url("/unparseable")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(200, home.getStatusCode());
            assertEquals("SID=old", home.getHeader(RECEIVED_COOKIE));
        }
    }

    // The next two already hold on main, where a redirect to GET is built from scratch; they keep it that way.

    @Test
    void aPostRedirectedToGetSendsTheSessionItRotated() throws Exception {
        assertEquals("SID=new", afterSeeding(client -> client.preparePost(url("/login"))));
    }

    @Test
    void a303DoesNotResendACookieItDeleted() throws Exception {
        assertNull(afterSeeding(client -> client.preparePost(url("/logout-303"))));
    }

    // The caller's own cookies follow a same-origin redirect; only the store's are replaced.

    @Test
    void theCallersCookieFollowsASameOriginRedirect() throws Exception {
        assertTrue(cookiesSent(withCallerCookie("X", "1", client -> client.prepareGet(url("/bounce")))).contains("X=1"),
                "GET, 302");
        assertTrue(cookiesSent(withCallerCookie("X", "1", client -> client.preparePost(url("/bounce-307"))))
                .contains("X=1"), "POST, 307");
    }

    @Test
    void theCallersCookieIsSentBesideTheSessionTheRedirectRotated() throws Exception {
        Set<String> sent = cookiesSent(withCallerCookie("X", "1", client -> client.prepareGet(url("/login"))));
        assertEquals(new HashSet<>(Arrays.asList("X=1", "SID=new")), sent);
    }

    @Test
    void theCallersCookieStillBeatsAStoredOneOfTheSameName() throws Exception {
        assertEquals("SID=mine", withCallerCookie("SID", "mine", client -> client.prepareGet(url("/bounce"))));
    }

    /** A caller-set Cookie header is the caller's too, but it must not outrank what the redirect rotated. */
    @Test
    void aCallerSetCookieHeaderDoesNotOutrankTheSessionTheRedirectRotated() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            String received = client.prepareGet(url("/login")).setHeader("Cookie", "SID=old; X=1")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals(new HashSet<>(Arrays.asList("X=1", "SID=new")), cookiesSent(received));
        }
    }

    @Test
    void aRawCookieWithAValueTheStrictEncoderRejectsSurvivesARedirect() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            String received = client.prepareGet(url("/bounce"))
                    .setHeader("Cookie", "X=a b; flag; Y=1")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals("X=a b; flag; Y=1", received);
        }
    }

    @Test
    void aRawCookieWithANonStrictValueSurvivesA307() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            String received = client.preparePost(url("/login-307"))
                    .setHeader("Cookie", "SID=old; X=a b")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals(new HashSet<>(Arrays.asList("X=a b", "SID=new")), cookiesSent(received));
        }
    }

    @Test
    void aRotatedCookieDoesNotDisplaceAnUnrelatedRawValue() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            String received = client.prepareGet(url("/login"))
                    .setHeader("Cookie", "SID=old; X=a b")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals(new HashSet<>(Arrays.asList("X=a b", "SID=new")), cookiesSent(received));
        }
    }

    @Test
    void rotationPreservesQuotedAndValuelessRawPairs() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            String received = client.prepareGet(url("/login"))
                    .setHeader("Cookie", "SID=old; X=\"a;b\"; flag; Y=a b")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals("X=\"a;b\"; flag; Y=a b; SID=new", received);
        }
    }

    @Test
    void aRawCookieTheRedirectDeletedIsNotResent() throws Exception {
        assertEquals("X=a b", withRawCookie("SID=old; X=a b", client -> client.prepareGet(url("/logout"))));
        assertNull(withRawCookie("SID=old", client -> client.prepareGet(url("/logout"))));
    }

    /**
     * Every ';' ends a raw pair, as it does where the request factory merges the header: a quote must not hide a
     * pair the redirect replaced in its neighbour's value, or the stale one is resent and the new one dropped.
     */
    @Test
    void aQuoteInARawValueDoesNotShieldTheSessionTheRedirectRotated() throws Exception {
        assertEquals("X=a\"b; SID=new", withRawCookie("X=a\"b; SID=old", client -> client.prepareGet(url("/login"))));
        assertEquals("X=\"a; SID=new", withRawCookie("X=\"a; SID=old\"", client -> client.prepareGet(url("/login"))));
    }

    @Test
    void aQuoteInARawValueDoesNotShieldACookieTheRedirectDeleted() throws Exception {
        assertEquals("X=\"a", withRawCookie("X=\"a; SID=old", client -> client.prepareGet(url("/logout"))));
    }

    /** Only the replaced pair is cut out; what is left, separators included, goes out as the caller wrote it. */
    @Test
    void theRawPairsARotationKeepsAreSentByteForByte() throws Exception {
        assertEquals("prefs={\"a\":\"x;y\"}; SID=new",
                withRawCookie("prefs={\"a\":\"x;y\"}; SID=old", client -> client.prepareGet(url("/login"))));
        assertEquals("A=1;B=2; SID=new", withRawCookie("A=1;B=2; SID=old", client -> client.prepareGet(url("/login"))));
        assertEquals("A=1;B=2; SID=new", withRawCookie("A=1;SID=old;B=2", client -> client.prepareGet(url("/login"))));
        assertEquals("A=1; SID=new", withRawCookie("SID=a; A=1; sid=b", client -> client.prepareGet(url("/login"))));
    }

    @Test
    void aRotationReachesEveryCookieHeaderTheCallerSet() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            String received = client.prepareGet(url("/login"))
                    .addHeader("Cookie", "X=a b; SID=old")
                    .addHeader("Cookie", "A=1;B=2")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals("X=a b; A=1;B=2; SID=new", received);
        }
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            String received = client.prepareGet(url("/login"))
                    .addHeader("Cookie", "A=1;B=2")
                    .addHeader("Cookie", "SID=old")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals("A=1;B=2; SID=new", received);
        }
    }

    @Test
    void aRawCookieSurvivesARotationWithTheLaxEncoder() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true).setUseLaxCookieEncoder(true))) {
            String received = client.prepareGet(url("/login"))
                    .setHeader("Cookie", "flag; X=a b; SID=old")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals("flag; X=a b; SID=new", received);
        }
    }

    @Test
    void rawCookieStillWinsOverAStoredCookieOfTheSameName() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            String received = client.prepareGet(url("/bounce"))
                    .setHeader("Cookie", "SID=mine; X=a b")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertEquals("SID=mine; X=a b", received);
        }
    }

    @Test
    void rawCookieDoesNotCrossAnOriginBoundary() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            String received = client.prepareGet(url("/elsewhere"))
                    .setHeader("Cookie", "X=a b")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            assertNull(received);
        }
    }

    /** A Set-Cookie the store refused, or filed for another path, does not replace the caller's cookie. */
    @Test
    void aSetCookieThatDoesNotReachTheNextHopLeavesTheCallersCookie() throws Exception {
        assertEquals("SID=mine", withCallerCookie("SID", "mine", client -> client.prepareGet(url("/reject-domain"))),
                "refused: Domain does not match");
        assertEquals("SID=mine", withCallerCookie("SID", "mine", client -> client.prepareGet(url("/other-path"))),
                "stored for /elsewhere, not sent to /home");
        assertEquals("SID=mine", withCallerCookie("SID", "mine",
                client -> client.prepareGet(url("/other-path-same-value"))),
                "stored for /elsewhere, with the value of the stored cookie sent to /home");
    }

    /** Nor does a deletion the store refused or that applies elsewhere, or the stored value takes its place. */
    @Test
    void aDeletionThatDoesNotReachTheNextHopLeavesTheCallersCookie() throws Exception {
        for (String path : Arrays.asList("/reject-domain-delete", "/other-path-delete")) {
            assertEquals("SID=mine", withCallerCookie("SID", "mine", client -> client.prepareGet(url(path))), path);
            assertEquals("SID=mine", withRawCookieAfterSeeding("SID=mine", client -> client.prepareGet(url(path))),
                    path + ", Cookie header");
        }
        assertEquals("SID=mine", withRawCookie("SID=mine", client -> client.prepareGet(url("/other-path-delete"))),
                "nothing stored");
    }

    /**
     * A deletion that applies only to the request it answered still removes the cookie sent with that request, from
     * the store or the caller's header, so it does not follow the redirect.
     */
    @Test
    void aCookieDeletedForThePathItWasSentToDoesNotFollowTheRedirect() throws Exception {
        assertEquals("SID=old", afterSeeding(client -> client.prepareGet(url("/p/logout"))), "cookie object");
        assertEquals("SID=old", withRawCookieAfterSeeding("P=scoped", client -> client.prepareGet(url("/p/logout"))),
                "Cookie header");
    }

    /** A stored cookie of that name for the next hop still follows, as it would without the caller's header. */
    @Test
    void aCookieDeletedForThePathItWasSentToLeavesTheOneStoredForTheNextHop() throws Exception {
        Set<String> expected = new HashSet<>(Arrays.asList("SID=old", "P=root"));
        assertEquals(expected, cookiesSent(logoutBesideARootCookie(null)), "cookie object");
        assertEquals(expected, cookiesSent(logoutBesideARootCookie("P=scoped")), "Cookie header");
    }

    // Without a cookie store every cookie on the request is the caller's own.

    @Test
    void withoutAStoreTheCallersCookieFollowsASameOriginRedirect() throws Exception {
        assertEquals("X=1", withoutAStore(client -> client.prepareGet(url("/login"))), "GET, 302");
        assertEquals("X=1", withoutAStore(client -> client.preparePost(url("/see-other"))), "POST, 303");
    }

    @Test
    void withoutAStoreTheCallersCookieStaysBehindOnACrossOriginRedirect() throws Exception {
        assertNull(withoutAStore(client -> client.prepareGet(url("/elsewhere"))));
    }

    private String afterSeeding(Function<AsyncHttpClient, BoundRequestBuilder> request) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            return request.apply(client).execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
        }
    }

    private String withCallerCookie(String name, String value, Function<AsyncHttpClient, BoundRequestBuilder> request)
            throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            return request.apply(client).addCookie(new DefaultCookie(name, value))
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
        }
    }

    private String withRawCookie(String header, Function<AsyncHttpClient, BoundRequestBuilder> request)
            throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            return request.apply(client).setHeader("Cookie", header)
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
        }
    }

    private String logoutBesideARootCookie(String cookieHeader) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            client.prepareGet(url("/seed-root-p")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            BoundRequestBuilder logout = client.prepareGet(url("/p/logout"));
            if (cookieHeader != null) {
                logout.setHeader("Cookie", cookieHeader);
            }
            return logout.execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
        }
    }

    private String withRawCookieAfterSeeding(String header, Function<AsyncHttpClient, BoundRequestBuilder> request)
            throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            return request.apply(client).setHeader("Cookie", header)
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
        }
    }

    private static Set<String> cookiesSent(String header) {
        return header == null ? new HashSet<>() : new HashSet<>(Arrays.asList(header.split("; ")));
    }

    private String withoutAStore(Function<AsyncHttpClient, BoundRequestBuilder> request) throws Exception {
        AsyncHttpClientConfig noStore = config().setFollowRedirect(true).setCookieStore(null).build();
        try (AsyncHttpClient client = asyncHttpClient(noStore)) {
            return request.apply(client).addCookie(new DefaultCookie("X", "1"))
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
        }
    }

    private static void redirect(HttpServletResponse response, int status, String setCookie, String location) {
        if (setCookie != null) {
            response.addHeader("Set-Cookie", setCookie);
        }
        response.setStatus(status);
        response.setHeader("Location", location);
    }

    private String url(String path) {
        return "http://localhost:" + port1 + path;
    }
}
