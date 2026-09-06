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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            // Upper case, so a default-locale fold would miss the ROOT-folded table under Turkish.
            assertFalse(reaches("evil.CO.IN", "CO.IN", "bank.CO.IN"),
                    "an uppercase Domain must not escape the guard under a Turkish locale");
            assertFalse(reaches("evil.INFO", "INFO", "bank.INFO"),
                    "an uppercase single-label suffix must not escape the guard under a Turkish locale");
            assertFalse(reaches("evil.INFO", "INFO", "bank.Info"),
                    "nor must a mixed-case victim host");
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

    /** Only the A-label gets past the cookie decoder, so the Unicode rules must match in that form. */
    @Test
    public void anInternationalisedSuffixIsRecognisedByItsALabel() {
        assertTrue(PublicSuffixList.isPublicSuffix("\u0440\u0444"));
        assertTrue(PublicSuffixList.isPublicSuffix("xn--p1ai"), "its A-label must match too");
        assertTrue(PublicSuffixList.isPublicSuffix("\u4e2d\u56fd"));
        assertTrue(PublicSuffixList.isPublicSuffix("xn--fiqs8s"), "its A-label must match too");

        assertFalse(reaches("evil.xn--p1ai", "xn--p1ai", "bank.xn--p1ai"),
                "a cookie for an internationalised registry must not be planted");
        assertTrue(reaches("bank.xn--p1ai", "bank.xn--p1ai", "www.bank.xn--p1ai"),
                "an ordinary domain under that registry must still work");
    }

    /**
     * The A-label table is only asked about names containing {@code xn--}. Nameprep can map some non-ASCII to
     * plain ASCII, which would slip that gate; no shipped rule does, and this fails if a refresh adds one.
     */
    @Test
    public void everyInternationalisedRuleIsReachableThroughTheALabelGate() {
        List<String> unreachable = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                PublicSuffixList.class.getResourceAsStream("/org/asynchttpclient/cookie/public_suffix_list.dat"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String rule = line.trim();
                if (rule.isEmpty() || rule.startsWith("//")) {
                    continue;
                }
                if (rule.chars().allMatch(c -> c <= 0x7F)) {
                    continue;
                }
                String bare = rule.startsWith("!") ? rule.substring(1)
                        : rule.startsWith("*.") ? rule.substring(2) : rule;
                // Here rather than in the read's catch, so a failure names the rule.
                String ascii;
                try {
                    ascii = IDN.toASCII(bare, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
                } catch (RuntimeException e) {
                    unreachable.add(bare + " -> no A-label form (" + e.getMessage() + ')');
                    continue;
                }
                if (!ascii.contains("xn--")) {
                    unreachable.add(bare + " -> " + ascii);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not read the bundled public suffix list", e);
        }

        assertTrue(unreachable.isEmpty(),
                "these rules would never be matched by their A-label spelling: " + unreachable);
    }

    /** The private section: {@code github.io} subdomains belong to different people. */
    @Test
    public void aCookieForAPrivateSuffixIsNotPlanted() {
        assertTrue(PublicSuffixList.isPublicSuffix("github.io"));
        assertTrue(PublicSuffixList.isPublicSuffix("herokuapp.com"));

        assertFalse(reaches("attacker.github.io", "github.io", "victim.github.io"),
                "one GitHub Pages site must not set a cookie for another");
        assertFalse(reaches("a.herokuapp.com", "herokuapp.com", "b.herokuapp.com"),
                "one Heroku app must not set a cookie for another");
    }

    /**
     * A Domain equal to the request host is kept, but as host-only: stored as a domain cookie, a service
     * reached as a bare name like {@code app} hands it to every sibling under that suffix.
     */
    @Test
    public void aDomainEqualToAPublicSuffixHostBecomesHostOnly() {
        assertFalse(reaches("app", "app", "evil.app"), "a bare 'app' must not set a cookie for *.app");
        assertFalse(reaches("dev", "dev", "anything.dev"));
        assertFalse(reaches("foo.ck", "foo.ck", "victim.foo.ck"));

        assertTrue(reaches("app", "app", "app"), "it must still come back to the host that set it");
        assertTrue(reaches("app", null, "app"), "and a host-only cookie there is unaffected");
        assertTrue(reaches("www.ck", "www.ck", "a.www.ck"),
                "a PSL exception rule names something registrable, so it keeps working");
    }

    /**
     * RFC 6265 Section 5.1.3: the suffix branch of the domain-match applies only to a host name, or a cookie
     * planted by one address reaches every address sharing its last label.
     */
    @Test
    public void aNumericDomainIsNotTossedOntoUnrelatedAddresses() {
        assertFalse(reaches("198.51.100.1", "1", "127.0.0.1"),
                "Domain=1 from an IPv4 literal must not reach 127.0.0.1");
        assertFalse(reaches("198.51.100.254", "254", "10.0.0.254"), "any final octet, not just .1");
        assertFalse(reaches("198.51.0.1", "0.1", "127.0.0.1"), "nor a multi-label numeric suffix");
        // Isolates the retrieval guard: Domain equal to the host takes the equality branch, and 0.1 is no
        // public suffix, so only stopping the walk keeps this from 127.0.0.1.
        assertFalse(reaches("0.1", "0.1", "127.0.0.1"),
                "an IP-literal request host must not walk up to a numeric parent key");
        assertFalse(reaches("0.1", "0.1", "10.0.0.1"));
        assertTrue(reaches("0.1", "0.1", "0.1"), "but its own exact entry must still be returned");

        assertTrue(reaches("127.0.0.1", null, "127.0.0.1"), "a host-only cookie on an address still works");
        assertTrue(reaches("127.0.0.1", "127.0.0.1", "127.0.0.1"), "so does Domain equal to the address");
        assertTrue(reaches("www.example.com", "example.com", "api.example.com"),
                "and ordinary domain cookies are untouched");
    }

    /**
     * Isolates the storage guard. Asserted on the jar, not through {@code get()}, because the retrieval walk
     * would hide the entry anyway.
     */
    @Test
    public void anIpLiteralDoesNotFileACookieUnderANumericParent() {
        assertTrue(jarOf("198.51.0.1", "0.1").isEmpty(),
                "Domain=0.1 from an IPv4 literal must never enter the jar");
        assertTrue(jarOf("198.51.100.254", "100.254").isEmpty());
        assertTrue(jarOf("198.51.100.1", "1").isEmpty());
        // The system resolver also reads hex, so these are addresses too.
        assertTrue(jarOf("198.51.100.0x1", "100.0x1").isEmpty());
        assertTrue(jarOf("127.0.0.0x1", "0.0x1").isEmpty());

        assertFalse(jarOf("www.example.com", "example.com").isEmpty(),
                "an ordinary domain cookie must still be stored");
        assertFalse(jarOf("127.0.0.1", null).isEmpty(), "and so must a host-only one on an address");
    }

    private static List<Cookie> jarOf(String setterHost, String domainAttribute) {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        DefaultCookie cookie = new DefaultCookie("SID", "planted");
        if (domainAttribute != null) {
            cookie.setDomain(domainAttribute);
        }
        cookie.setPath("/");
        store.add(Uri.create("http://" + setterHost + "/"), cookie);
        return store.getAll();
    }

    /**
     * Names must fold with {@link Locale#ROOT}, or under Turkish a later {@code sid} misses the key of
     * {@code SID} and a logout leaves the original in the jar.
     */
    @Test
    public void cookieNamesFoldIndependentlyOfTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            ThreadSafeCookieStore store = new ThreadSafeCookieStore();
            DefaultCookie upper = new DefaultCookie("SID", "first");
            upper.setPath("/");
            store.add(Uri.create("http://example.com/"), upper);
            DefaultCookie lower = new DefaultCookie("sid", "second");
            lower.setPath("/");
            store.add(Uri.create("http://example.com/"), lower);

            assertEquals(1, store.getAll().size(),
                    "the two spellings must land on one key under a Turkish locale too: " + store.getAll());
        } finally {
            Locale.setDefault(original);
        }
    }

    /**
     * {@code 127.0.0.1.} resolves like {@code 127.0.0.1}, and a {@code Location} header lets an attacker pick
     * that spelling, so every guard above must see through it.
     */
    @Test
    public void aTrailingDotIsNotASecondNameForTheSameHost() {
        assertFalse(reaches("127.0.0.1.", "1.", "127.0.1.1"), "a trailing dot must not re-open the IP toss");
        assertFalse(reaches("127.0.0.1.", "1", "127.0.0.1"));
        assertFalse(reaches("evil.co.uk.", "co.uk.", "bank.co.uk"),
                "nor the public-suffix toss");
        assertFalse(reaches("a.github.io.", "github.io.", "b.github.io"));

        assertTrue(reaches("www.example.com.", "example.com.", "api.example.com"),
                "an ordinary domain cookie written with trailing dots still works");
        assertTrue(reaches("www.example.com.", "example.com", "api.example.com"),
                "and the two spellings name one host, not two");
    }

    /**
     * The list's default rule is "*": without it a site under a wildcard-only TLD such as {@code .ck} sets a
     * cookie for every other {@code .ck} site, and {@code a.local} sets one for {@code b.local}.
     */
    @Test
    public void anUnlistedSingleLabelIsAPublicSuffix() {
        assertTrue(PublicSuffixList.isPublicSuffix("ck"), "a wildcard-only ccTLD names no registrable site");
        assertTrue(PublicSuffixList.isPublicSuffix("local"));
        assertTrue(PublicSuffixList.isPublicSuffix("internal"));

        assertFalse(reaches("evil.ck", "ck", "victim.ck"));
        assertFalse(reaches("a.local", "local", "b.local"));
        assertFalse(reaches("a.internal", "internal", "b.internal"));

        // An exception rule still names something registrable, and a host may always keep its own cookie.
        assertFalse(PublicSuffixList.isPublicSuffix("www.ck"));
        assertTrue(reaches("www.ck", "www.ck", "a.www.ck"));
        assertTrue(reaches("localhost", null, "localhost"), "a host-only cookie on localhost still works");
        assertTrue(reaches("localhost", "localhost", "localhost"), "so does Domain equal to it");
    }
}
