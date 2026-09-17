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
package org.asynchttpclient.cookie;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 6265bis Section 5.7 steps 13 and 16: a plaintext response may neither set a Secure cookie nor overlay one.
 * The client sends only the first cookie of each name, so the ordering tests name the cookie that must win.
 */
public class SecureCookieSchemeTest {

    private static void set(ThreadSafeCookieStore store, String url, String setCookie) {
        store.add(Uri.create(url), ClientCookieDecoder.LAX.decode(setCookie));
    }

    /** The value the client would actually send: the first of that name, which addCookieIfUnset keeps. */
    private static String sent(ThreadSafeCookieStore store, String url, String name) {
        List<Cookie> cookies = store.get(Uri.create(url));
        return cookies.stream().filter(c -> name.equals(c.name())).map(Cookie::value).findFirst().orElse(null);
    }

    // step 13: a Secure cookie from an insecure context

    @Test
    public void aSecureCookieSetOverPlaintextIsIgnored() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "http://example.com/", "SID=planted; Path=/; Secure");

        assertNull(sent(store, "https://example.com/", "SID"),
                "a Secure cookie received over http must not be served to https");
        assertTrue(store.getAll().isEmpty(), "a Secure cookie received over http must not be stored at all");
    }

    /** The case with no attacker on the path at all: a plaintext sibling under the same site. */
    @Test
    public void aPlaintextSiblingCannotPlantASecureDomainCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "http://insecure.example.com/", "SID=planted; Secure; Domain=example.com; Path=/");

        assertNull(sent(store, "https://bank.example.com/", "SID"));
    }

    // step 16: a plaintext cookie overlaying a Secure one

    @Test
    public void aPlaintextCookieCannotOverlayASecureCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://example.com/", "SID=real; Path=/; Secure");

        set(store, "http://example.com/", "SID=planted; Path=/");
        assertEquals("real", sent(store, "https://example.com/", "SID"),
                "a non-Secure cookie received over http must not replace a Secure cookie");

        set(store, "http://www.example.com/", "SID=planted; Domain=example.com; Path=/");
        assertEquals("real", sent(store, "https://example.com/", "SID"),
                "a domain cookie received over http must not replace a Secure host cookie it domain-matches");

        set(store, "http://example.com/", "SID=; Path=/; Max-Age=0");
        assertEquals("real", sent(store, "https://example.com/", "SID"),
                "an expiring cookie received over http must not delete a Secure cookie");
    }

    /** Step 16's domain test is "domain-matches ... or vice-versa", so each direction needs its own arm. */
    @Test
    public void theOverlayCheckMatchesDomainsInBothDirections() {
        ThreadSafeCookieStore parentOverHost = new ThreadSafeCookieStore();
        set(parentOverHost, "https://bank.example.com/", "SID=good; Secure; Path=/");
        set(parentOverHost, "http://insecure.example.com/", "SID=evil; Domain=example.com; Path=/");
        assertEquals(1, parentOverHost.getAll().size(),
                "a plaintext Domain cookie over a Secure host cookie: " + parentOverHost.getAll());

        ThreadSafeCookieStore hostUnderParent = new ThreadSafeCookieStore();
        set(hostUnderParent, "https://bank.example.com/", "SID=good; Secure; Domain=example.com; Path=/");
        set(hostUnderParent, "http://www.example.com/", "SID=evil; Path=/");
        assertEquals(1, hostUnderParent.getAll().size(),
                "a plaintext host cookie under a Secure Domain cookie: " + hostUnderParent.getAll());
    }

    @Test
    public void thePathOverlayRuleIsOneWay() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://example.com/login", "SID=real; Path=/login; Secure");

        set(store, "http://example.com/foo", "SID=other; Path=/foo");
        assertEquals("other", sent(store, "http://example.com/foo", "SID"),
                "a non-Secure cookie on an unrelated path is still stored");

        set(store, "http://example.com/login/en", "SID=planted; Path=/login/en");
        assertNull(sent(store, "http://example.com/login/en", "SID"),
                "a non-Secure cookie whose path sits under the Secure cookie's path is ignored");
        assertEquals("real", sent(store, "https://example.com/login/en", "SID"));
    }

    /** Only a live Secure cookie protects its name; once it has expired a plaintext cookie may take the name. */
    @Test
    public void anExpiredSecureCookieNoLongerBlocksThePlaintextOne() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ThreadSafeCookieStore store = new ThreadSafeCookieStore(now::get);
        set(store, "https://example.com/", "SID=old; Secure; Path=/; Max-Age=1");
        now.addAndGet(2_001);

        set(store, "http://example.com/", "SID=fresh; Path=/");
        assertEquals("fresh", sent(store, "http://example.com/", "SID"));
    }

    /** Under a Turkish default locale, SID must still fold to the key a plaintext "sid" is checked against. */
    @Test
    public void theOverlayCheckDoesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            ThreadSafeCookieStore store = new ThreadSafeCookieStore();
            set(store, "https://example.com/", "SID=good; Secure; Path=/");
            set(store, "http://example.com/", "sid=evil; Path=/");

            assertEquals(1, store.getAll().size(), "the plaintext cookie must not be stored: " + store.getAll());
            assertEquals("good", store.getAll().get(0).value());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void cookiesSetOverASecureSchemeAreUnaffected() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://example.com/", "SID=real; Path=/; Secure");
        assertEquals("real", sent(store, "https://example.com/", "SID"));

        set(store, "https://example.com/", "SID=renewed; Path=/");
        assertEquals("renewed", sent(store, "https://example.com/", "SID"),
                "the origin itself may still replace its Secure cookie over https");

        set(store, "http://example.com/", "lang=en; Path=/");
        assertEquals("en", sent(store, "http://example.com/", "lang"),
                "an ordinary http cookie with no Secure counterpart is still stored");
    }

    // which duplicate is sent

    /**
     * Step 16 is one-way, so a plaintext cookie for a broader path may sit beside a Secure one for a narrower
     * path. The TLS cookie must be the one sent, across buckets and within one host.
     */
    @Test
    public void aBroadPathPlaintextCookieNeverShadowsATlsCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://www.example.com/account/", "SID=good; Secure; Domain=example.com; Path=/account");
        set(store, "http://www.example.com/", "SID=evil; Path=/");
        assertEquals("good", sent(store, "https://www.example.com/account/me", "SID"));

        ThreadSafeCookieStore sameHost = new ThreadSafeCookieStore();
        set(sameHost, "https://example.com/app/", "JSESSIONID=good; Secure; Path=/app");
        set(sameHost, "http://example.com/", "JSESSIONID=evil; Path=/");
        assertEquals("good", sent(sameHost, "https://example.com/app/page", "JSESSIONID"));
    }

    /** The GHSA's own threat model: a plaintext sibling plants first, and the site sets its Secure cookie later. */
    @Test
    public void aCookiePlantedOverPlaintextBeforeTheSiteSetsItsOwnDoesNotWin() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "http://insecure.example.com/", "SID=evil; Domain=example.com; Path=/");
        set(store, "https://bank.example.com/", "SID=good; Secure; Path=/");
        assertEquals("good", sent(store, "https://bank.example.com/", "SID"));
    }

    @Test
    public void aLongerPathPlantedOverPlaintextBeforehandDoesNotWin() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "http://insecure.example.com/", "SID=evil; Domain=example.com; Path=/account");
        set(store, "https://bank.example.com/", "SID=good; Secure; Path=/");
        assertEquals("good", sent(store, "https://bank.example.com/account/x", "SID"));
    }

    /** A sibling's Domain cookie never displaces the host's own, whatever its path, and however often the host refreshes. */
    @Test
    public void aSiblingDomainCookieNeverDisplacesTheHostsOwn() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://bank.example.com/", "SID=good1; Secure; Path=/");
        set(store, "https://evil.example.com/", "SID=evil; Secure; Domain=example.com; Path=/");
        set(store, "https://evil.example.com/", "CSRF=evil; Secure; Domain=example.com; Path=/account");
        set(store, "https://bank.example.com/", "CSRF=good; Secure; Path=/");
        set(store, "https://bank.example.com/", "SID=good2; Secure; Path=/");
        assertEquals("good2", sent(store, "https://bank.example.com/", "SID"));
        assertEquals("good", sent(store, "https://bank.example.com/account/x", "CSRF"));
    }

    /** Provenance, not the attribute: an https sibling's Secure toss does not outrank the host's own TLS cookie. */
    @Test
    public void theSecureAttributeAloneDoesNotOutrankTheHostsOwnTlsCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://bank.example.com/", "SID=good; Path=/");
        set(store, "https://evil.example.com/", "SID=evil; Secure; Domain=example.com; Path=/");
        assertEquals("good", sent(store, "https://bank.example.com/", "SID"));
    }

    /**
     * Provenance in the plaintext direction too: behind a TLS-terminating load balancer a site often sets its
     * session cookie over HTTPS without the Secure attribute, and a plaintext host cookie must not outrank it.
     */
    @Test
    public void aTlsCookieWithoutTheSecureAttributeStillOutranksAPlaintextOne() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://www.example.com/account/", "SID=good; Domain=example.com; Path=/account");
        set(store, "http://www.example.com/", "SID=evil; Path=/");
        assertEquals("good", sent(store, "https://www.example.com/account/me", "SID"));
    }

    /**
     * Single sign-on: a plaintext attacker plants a host-only cookie on the application host before the login
     * host sets its Domain session cookie over TLS.
     */
    @Test
    public void aPlaintextHostCookiePlantedBeforeSingleSignOnDoesNotWin() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "http://www.example.com/", "SID=evil; Path=/");
        set(store, "https://login.example.com/", "SID=good; Domain=example.com; Path=/");
        assertEquals("good", sent(store, "https://www.example.com/", "SID"));
    }

    /**
     * Within one host, longer paths first. Bucket order is hash order, so this uses many pairs and checks that
     * at least one was stored root-first, or the sort would go unexercised.
     */
    @Test
    public void deeperPathsComeFirstWithinOneHost() {
        int storedRootFirst = 0;
        int misordered = 0;
        for (int i = 0; i < 64; i++) {
            ThreadSafeCookieStore store = new ThreadSafeCookieStore();
            set(store, "https://example.com/", "n" + i + "=root; Path=/");
            set(store, "https://example.com/d" + i + "/", "n" + i + "=deep; Path=/d" + i);

            Map<String, ? extends Map<?, ?>> jar = store.getUnderlying();
            for (Object stored : jar.get("example.com").values()) {
                if (String.valueOf(stored).contains("=root")) {
                    storedRootFirst++;
                    break;
                }
                if (String.valueOf(stored).contains("=deep")) {
                    break;
                }
            }
            if (!"deep".equals(sent(store, "https://example.com/d" + i + "/x", "n" + i))) {
                misordered++;
            }
        }
        assertTrue(storedRootFirst > 0, "no pair is stored root-first on this layout, so nothing tests the sort");
        assertEquals(0, misordered);
    }

    // loopback

    /** Loopback is a secure context for a development server's own cookies. */
    @Test
    public void aPlaintextLoopbackServerGetsItsOwnSecureCookieBack() {
        for (String origin : new String[]{"http://localhost:8080/", "http://127.0.0.1:8080/", "http://127.0.0.2/",
                "http://[::1]:8080/", "http://[0:0:0:0:0:0:0:1]/"}) {
            ThreadSafeCookieStore store = new ThreadSafeCookieStore();
            set(store, origin, "dev=1; Secure; Path=/");
            assertEquals("1", sent(store, origin, "dev"), origin);

            set(store, origin, "dev=2; Secure; Path=/");
            assertEquals("2", sent(store, origin, "dev"), origin + " may refresh its own Secure cookie");
        }
    }

    /** But a Secure cookie that arrived over TLS is never downgraded to plaintext, loopback or not. */
    @Test
    public void aSecureCookieReceivedOverTlsIsNeverSentInPlaintext() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://localhost:8443/", "idp=tls-only; Secure; Path=/");
        assertNull(sent(store, "http://localhost:9999/", "idp"));
        assertEquals("tls-only", sent(store, "https://localhost:9999/", "idp"));
    }

    /** Nor can a plaintext loopback port overlay one, with or without the Secure attribute. */
    @Test
    public void aPlaintextLoopbackPortCannotOverlayATlsCookie() {
        for (String attack : new String[]{"idp=from-plaintext; Secure; Path=/", "idp=from-plaintext; Path=/"}) {
            ThreadSafeCookieStore store = new ThreadSafeCookieStore();
            set(store, "https://localhost:8443/", "idp=tls-only; Secure; Path=/");
            set(store, "http://localhost:9999/", attack);
            assertEquals("tls-only", sent(store, "https://localhost:8443/", "idp"), attack);
        }
    }

    /** Spellings the JDK does not accept as an address literal are resolved as names, and can reach any host. */
    @Test
    public void numericNamesThatAreNotLoopbackAddressesAreNotSecure() {
        for (String origin : new String[]{"http://127.0.0.256/", "http://127.16777216/", "http://127.99999999999/",
                "http://127.0.0.1.1/", "http://127.evil.example/", "http://127/", "http://evil.localhost/",
                "http://localhost.example.com/"}) {
            ThreadSafeCookieStore store = new ThreadSafeCookieStore();
            set(store, origin, "dev=1; Secure; Path=/");
            assertTrue(store.getAll().isEmpty(), origin + " must not keep a Secure cookie");
        }
    }
}
