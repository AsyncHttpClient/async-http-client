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

import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 6265 Section 5.3 step 5: a cookie whose {@code Domain} names a public suffix must be ignored.
 * Without it, one site under a registry can plant a cookie that every other site under that registry
 * receives. Step 6, which asks only whether the request host sits under the Domain, does not catch it.
 */
public class PublicSuffixCookieTest {

    private static boolean reaches(String setterHost, String domainAttribute, String victimHost) {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        DefaultCookie cookie = new DefaultCookie("SID", "planted");
        if (domainAttribute != null) {
            cookie.setDomain(domainAttribute);
        }
        cookie.setPath("/");
        store.add(Uri.create("http://" + setterHost + "/"), cookie);

        List<Cookie> got = store.get(Uri.create("http://" + victimHost + "/"));
        return got.stream().anyMatch(c -> "SID".equals(c.name()));
    }

    @Test
    public void aCookieForAPublicSuffixIsNotPlanted() {
        assertFalse(reaches("evil.co.uk", "co.uk", "bank.co.uk"),
                "a host under co.uk must not set a cookie for co.uk itself");
        assertFalse(reaches("evil.co.uk", "uk", "bank.co.uk"),
                "a host must not set a cookie for a bare TLD");
        assertFalse(reaches("evil.com", "com", "bank.com"),
                "a host must not set a cookie for com");
    }

    /**
     * The existing cookie-tossing guard must keep working: this is the case #2196 fixed, and it is what
     * shows the new check is not the only thing standing between these two hosts.
     */
    @Test
    public void theExistingCrossSiteGuardStillHolds() {
        assertFalse(reaches("evil.co.uk", "bank.co.uk", "bank.co.uk"),
                "one host must not set a cookie naming an unrelated host");
    }

    /**
     * Ordinary cookies must be unaffected, or every user loses their session handling.
     */
    @Test
    public void ordinaryCookiesAreUnaffected() {
        assertTrue(reaches("bank.co.uk", "bank.co.uk", "www.bank.co.uk"),
                "a site must still set a cookie for its own registrable domain");
        assertTrue(reaches("bank.co.uk", null, "bank.co.uk"),
                "a host-only cookie must still be returned to that host");
        assertTrue(reaches("www.example.com", "example.com", "api.example.com"),
                "a site must still share a cookie across its own subdomains");
    }

    /**
     * RFC 6265 section 5.3 step 5 keeps a cookie whose Domain equals the request host, as a host-only
     * cookie. Dropping it would break ordinary single-label hosts, because dev, app, box, cloud and a
     * dozen more are ICANN suffixes as well as the short names Docker Compose and Kubernetes hand out.
     */
    @Test
    public void aSingleLabelHostCanStillSetItsOwnCookie() {
        for (String host : new String[]{"dev", "app", "box", "cloud", "build", "run"}) {
            assertTrue(PublicSuffixList.isPublicSuffix(host), host + " is expected to be an ICANN suffix");
            assertTrue(reaches(host, host, host),
                    "a host whose own name is a public suffix must still set a cookie for itself: " + host);
        }
    }

    /**
     * The locale must not decide whether the check engages. Under Turkish the default lowercasing turns I
     * into a dotless i, so every I-initial suffix would stop matching and the guard would fail open.
     */
    @Test
    public void matchingDoesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertTrue(PublicSuffixList.isPublicSuffix("INFO"), "INFO must match under a Turkish locale");
            assertTrue(PublicSuffixList.isPublicSuffix("CO.IN"), "CO.IN must match under a Turkish locale");
            assertFalse(reaches("evil.co.in", "co.in", "bank.co.in"),
                    "the guard must hold under a Turkish locale");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void theListRecognisesSuffixesAndRegistrableDomains() {
        assertTrue(PublicSuffixList.isPublicSuffix("co.uk"));
        assertTrue(PublicSuffixList.isPublicSuffix("com"));
        assertTrue(PublicSuffixList.isPublicSuffix("com.au"));
        assertTrue(PublicSuffixList.isPublicSuffix("CO.UK"), "matching must be case-insensitive");

        assertFalse(PublicSuffixList.isPublicSuffix("bank.co.uk"));
        assertFalse(PublicSuffixList.isPublicSuffix("example.com"));
        assertFalse(PublicSuffixList.isPublicSuffix(""));
        assertFalse(PublicSuffixList.isPublicSuffix(null));
    }
}
