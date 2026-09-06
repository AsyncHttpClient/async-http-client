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

    /** A Set-Cookie the store refused, or filed for another path, does not replace the caller's cookie. */
    @Test
    void aSetCookieThatDoesNotReachTheNextHopLeavesTheCallersCookie() throws Exception {
        assertEquals("SID=mine", withCallerCookie("SID", "mine", client -> client.prepareGet(url("/reject-domain"))),
                "refused: Domain does not match");
        assertEquals("SID=mine", withCallerCookie("SID", "mine", client -> client.prepareGet(url("/other-path"))),
                "stored for /elsewhere, not sent to /home");
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
