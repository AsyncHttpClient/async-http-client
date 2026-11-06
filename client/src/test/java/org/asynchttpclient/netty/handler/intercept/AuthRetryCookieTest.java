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
import org.asynchttpclient.BoundRequestBuilder;
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
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.digestAuthRealm;
import static org.asynchttpclient.Dsl.ntlmAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The retry after a 401 or 407 must carry the cookies the challenge set, not the ones the first attempt went out
 * with.
 */
public class AuthRetryCookieTest extends AbstractBasicTest {

    private static final String RECEIVED_COOKIE = "received-cookie";
    private static final String NTLM_TYPE_2 = "TlRMTVNTUAACAAAAAAAAACgAAAABggAAU3J2Tm9uY2UAAAAAAAAAAA==";

    @Override
    public AbstractHandler configureHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
                String authorization = request.getHeader("Authorization");
                if ("/seed".equals(target)) {
                    response.addHeader("Set-Cookie", "SID=old; Path=/");
                } else if ("/digest".equals(target) && authorization == null) {
                    challenge(response, HttpServletResponse.SC_UNAUTHORIZED, "WWW-Authenticate", "SID=new; Path=/",
                            "Digest realm=\"test\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", qop=\"auth\"");
                } else if ("/ntlm".equals(target) && authorization == null) {
                    challenge(response, HttpServletResponse.SC_UNAUTHORIZED, "WWW-Authenticate", "SID=mid; Path=/",
                            "NTLM");
                } else if ("/ntlm".equals(target) && authorization.startsWith("NTLM TlRMTVNTUAAB")
                        && "X=a b; SID=mid".equals(request.getHeader("Cookie"))) {
                    // The second round of the handshake rotates the session again. Only a first retry that
                    // carried the first rotation gets here; any other falls through and echoes what it sent.
                    challenge(response, HttpServletResponse.SC_UNAUTHORIZED, "WWW-Authenticate", "SID=new; Path=/",
                            "NTLM " + NTLM_TYPE_2);
                } else if ("/proxied".equals(target) && request.getHeader("Proxy-Authorization") == null) {
                    // This server is the proxy too: the request is sent to it in absolute form.
                    challenge(response, HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED, "Proxy-Authenticate",
                            null, "Basic realm=\"proxy\"");
                } else if ("/protected-delete-elsewhere".equals(target) && authorization == null) {
                    challenge(response, HttpServletResponse.SC_UNAUTHORIZED, "WWW-Authenticate",
                            "SID=; Path=/elsewhere; Max-Age=0", "Basic realm=\"test\"");
                } else if ("/protected".equals(target) && authorization == null) {
                    challenge(response, HttpServletResponse.SC_UNAUTHORIZED, "WWW-Authenticate", "SID=new; Path=/",
                            "Basic realm=\"test\"");
                } else {
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
    void theRetrySendsTheSessionTheChallengeSet() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("SID=new")), cookiesOnRetry(null));
    }

    @Test
    void theCallersCookieSurvivesTheRetry() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("X=1", "SID=new")), cookiesOnRetry(new DefaultCookie("X", "1")));
    }

    /** A caller-set Cookie header is the caller's too, but it must not outrank what the challenge rotated. */
    @Test
    void aCallerSetCookieHeaderDoesNotOutrankTheSessionTheChallengeSet() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("X=1", "SID=new")), cookiesSent(rawCookieOnRetry("SID=old; X=1",
                client -> client.prepareGet(url("/protected"))
                        .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false)))));
    }

    @Test
    void aRawCookieWithANonStrictValueSurvivesTheRetry() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("X=a b", "SID=new")), cookiesSent(rawCookieOnRetry("SID=old; X=a b",
                client -> client.prepareGet(url("/protected"))
                        .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false)))));
    }

    @Test
    void aRawCookieSurvivesADigestRetryBesideTheSessionTheChallengeSet() throws Exception {
        assertEquals("X=a b; SID=new", rawCookieOnRetry("SID=old; X=a b", client -> client.prepareGet(url("/digest"))
                .setRealm(digestAuthRealm("user", "pass").setUsePreemptiveAuth(false))));
    }

    @Test
    void aRawCookieSurvivesAProxyAuthenticationRetry() throws Exception {
        assertEquals("SID=old; X=a b", rawCookieOnRetry("SID=old; X=a b", client -> client.prepareGet(url("/proxied"))
                .setProxyServer(proxyServer("localhost", port2)
                        .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false)))));
    }

    /** NTLM takes two challenges, and the retry after each carries what that one set. */
    @Test
    void aRawCookieSurvivesEveryRoundOfAnNtlmHandshake() throws Exception {
        assertEquals("X=a b; SID=new", rawCookieOnRetry("SID=old; X=a b", client -> client.prepareGet(url("/ntlm"))
                .setRealm(ntlmAuthRealm("Zaphod", "Beeblebrox").setNtlmDomain("Ursa-Minor").setNtlmHost("LightCity"))));
    }

    /** Every ';' ends a raw pair, so a quote does not hide the session the challenge set in its neighbour's value. */
    @Test
    void aQuoteInARawValueDoesNotShieldTheSessionTheChallengeSet() throws Exception {
        assertEquals("X=\"a; SID=new", rawCookieOnRetry("X=\"a; SID=old", client -> client.prepareGet(url("/protected"))
                .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false))));
        assertEquals("X=a\"b; SID=new", rawCookieOnRetry("X=a\"b; SID=old", client -> client.prepareGet(url("/digest"))
                .setRealm(digestAuthRealm("user", "pass").setUsePreemptiveAuth(false))));
    }

    /** A deletion for another path leaves the store's cookie of that name, which must not replace the caller's. */
    @Test
    void aDeletionForAnotherPathLeavesTheCallersCookieOnTheRetry() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals("SID=mine", client.prepareGet(url("/protected-delete-elsewhere"))
                    .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false))
                    .setHeader("Cookie", "SID=mine")
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE), "Cookie header");
            assertEquals("SID=mine", client.prepareGet(url("/protected-delete-elsewhere"))
                    .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false))
                    .addCookie(new DefaultCookie("SID", "mine"))
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE), "cookie object");
        }
    }

    private static String rawCookieOnRetry(String header, Function<AsyncHttpClient, BoundRequestBuilder> request)
            throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            return request.apply(client).setHeader("Cookie", header)
                    .execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
        }
    }

    private static void challenge(HttpServletResponse response, int status, String authenticate, String setCookie,
                                  String challenge) {
        if (setCookie != null) {
            response.addHeader("Set-Cookie", setCookie);
        }
        response.setHeader(authenticate, challenge);
        response.setStatus(status);
    }

    private Set<String> cookiesOnRetry(DefaultCookie callerCookie) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            BoundRequestBuilder request = client.prepareGet(url("/protected"))
                    .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false));
            if (callerCookie != null) {
                request.addCookie(callerCookie);
            }
            return cookiesSent(request.execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE));
        }
    }

    private static Set<String> cookiesSent(String header) {
        return header == null ? new HashSet<>() : new HashSet<>(Arrays.asList(header.split("; ")));
    }

    private String url(String path) {
        return "http://localhost:" + port1 + path;
    }
}
