/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CookieStoreTest {

    private static final Logger logger = LoggerFactory.getLogger(CookieStoreTest.class);

    @BeforeEach
    public void setUpGlobal() {
        logger.info("Local HTTP server started successfully");
        System.out.println("--Start");
    }

    @AfterEach
    public void tearDownGlobal() {
        System.out.println("--Stop");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void runAllSequentiallyBecauseNotThreadSafe() throws Exception {
        addCookieWithEmptyPath();
        dontReturnCookieForAnotherDomain();
        returnCookieWhenItWasSetOnSamePath();
        returnCookieWhenItWasSetOnParentPath();
        dontReturnCookieWhenDomainMatchesButPathIsDifferent();
        dontReturnCookieWhenDomainMatchesButPathIsParent();
        returnCookieWhenDomainMatchesAndPathIsChild();
        returnCookieWhenItWasSetOnSubdomain();
        replaceCookieWhenSetOnSameDomainAndPath();
        dontReplaceCookiesWhenTheyHaveDifferentName();
        expireCookieWhenSetWithDateInThePast();
        cookieWithSameNameMustCoexistIfSetOnDifferentDomains();
        handleMissingDomainAsRequestHost();
        handleMissingPathAsSlash();
        returnTheCookieWheniTSissuedFromRequestWithSubpath();
        handleMissingPathAsRequestPathWhenFromRootDir();
        handleMissingPathAsRequestPathWhenPathIsNotEmpty();
        handleDomainInCaseInsensitiveManner();
        handleCookieNameInCaseInsensitiveManner();
        handleCookiePathInCaseSensitiveManner();
        ignoreQueryParametersInUri();
        shouldServerOnSubdomainWhenDomainMatches();
        replaceCookieWhenSetOnSamePathBySameUri();
        handleMultipleCookieOfSameNameOnDifferentPaths();
        handleTrailingSlashesInPaths();
        returnMultipleCookiesEvenIfTheyHaveSameName();
        shouldServeCookiesBasedOnTheUriScheme();
        shouldAlsoServeNonSecureCookiesBasedOnTheUriScheme();
        shouldNotServeSecureCookiesForDefaultRetrievedHttpUriScheme();
        shouldServeSecureCookiesForSpecificallyRetrievedHttpUriScheme();
        shouldCleanExpiredCookieFromUnderlyingDataStructure();
    }

    private static void addCookieWithEmptyPath() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com");
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; path="));
        assertFalse(store.get(uri).isEmpty());
    }

    private static void dontReturnCookieForAnotherDomain() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; path="));
        assertTrue(store.get(Uri.create("http://www.bar.com")).isEmpty());
    }

    private static void returnCookieWhenItWasSetOnSamePath() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; path=/bar/"));
        assertEquals(1, store.get(Uri.create("http://www.foo.com/bar/")).size());
    }

    private static void returnCookieWhenItWasSetOnParentPath() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
        assertEquals(1, store.get(Uri.create("http://www.foo.com/bar/baz")).size());
    }

    private static void dontReturnCookieWhenDomainMatchesButPathIsDifferent() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
        assertTrue(store.get(Uri.create("http://www.foo.com/baz")).isEmpty());
    }

    private static void dontReturnCookieWhenDomainMatchesButPathIsParent() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
        assertTrue(store.get(Uri.create("http://www.foo.com")).isEmpty());
    }

    private static void returnCookieWhenDomainMatchesAndPathIsChild() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
        assertEquals(1, store.get(Uri.create("http://www.foo.com/bar/baz")).size());
    }

    private static void returnCookieWhenItWasSetOnSubdomain() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=.foo.com"));
        assertEquals(1, store.get(Uri.create("http://bar.foo.com")).size());
    }

    private static void replaceCookieWhenSetOnSameDomainAndPath() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com/bar/baz");
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE2; Domain=www.foo.com; path=/bar"));
        assertEquals(1, store.getAll().size());
        assertEquals("VALUE2", store.get(uri).get(0).value());
    }

    private static void dontReplaceCookiesWhenTheyHaveDifferentName() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com/bar/baz");
        store.add(uri, ClientCookieDecoder.LAX.decode("BETA=VALUE1; Domain=www.foo.com; path=/bar"));
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE2; Domain=www.foo.com; path=/bar"));
        assertEquals(2, store.get(uri).size());
    }

    private static void expireCookieWhenSetWithDateInThePast() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com/bar");
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=EXPIRED; Domain=www.foo.com; Path=/bar; Expires=Sun, 06 Nov 1994 08:49:37 GMT"));
        assertTrue(store.getAll().isEmpty());
    }

    private static void cookieWithSameNameMustCoexistIfSetOnDifferentDomains() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri1 = Uri.create("http://www.foo.com");
        store.add(uri1, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com"));
        Uri uri2 = Uri.create("http://www.bar.com");
        store.add(uri2, ClientCookieDecoder.LAX.decode("ALPHA=VALUE2; Domain=www.bar.com"));

        assertEquals(1, store.get(uri1).size());
        assertEquals("VALUE1", store.get(uri1).get(0).value());

        assertEquals(1, store.get(uri2).size());
        assertEquals("VALUE2", store.get(uri2).get(0).value());
    }

    private static void handleMissingDomainAsRequestHost() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com");
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Path=/"));
        assertEquals(1, store.get(uri).size());
    }

    private static void handleMissingPathAsSlash() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com");
        store.add(uri, ClientCookieDecoder.LAX.decode("tooe_token=0b1d81dd02d207491a6e9b0a2af9470da9eb1dad"));
        assertEquals(1, store.get(uri).size());
    }

    private static void returnTheCookieWheniTSissuedFromRequestWithSubpath() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE; path=/"));
        assertEquals(1, store.get(Uri.create("http://www.foo.com")).size());
    }

    private static void handleMissingPathAsRequestPathWhenFromRootDir() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com");
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1"));
        assertEquals(1, store.get(uri).size());
    }

    private static void handleMissingPathAsRequestPathWhenPathIsNotEmpty() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
        assertTrue(store.get(Uri.create("http://www.foo.com/baz")).isEmpty());
    }

    // RFC 2965 sec. 3.3.3
    private static void handleDomainInCaseInsensitiveManner() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1"));
        assertEquals(1, store.get(Uri.create("http://www.FoO.com/bar")).size());
    }

    // RFC 2965 sec. 3.3.3
    private static void handleCookieNameInCaseInsensitiveManner() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com/bar/baz");
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
        store.add(uri, ClientCookieDecoder.LAX.decode("alpha=VALUE2; Domain=www.foo.com; path=/bar"));
        assertEquals(1, store.getAll().size());
        assertEquals("VALUE2", store.get(uri).get(0).value());
    }

    // RFC 2965 sec. 3.3.3
    private static void handleCookiePathInCaseSensitiveManner() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/foo/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1"));
        assertTrue(store.get(Uri.create("http://www.FoO.com/Foo/bAr")).isEmpty());
    }

    private static void ignoreQueryParametersInUri() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar?query1"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/"));
        assertEquals(1, store.get(Uri.create("http://www.foo.com/bar?query2")).size());
    }

    // RFC 6265, 5.1.3.  Domain Matching
    private static void shouldServerOnSubdomainWhenDomainMatches() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("https://x.foo.org/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/; Domain=foo.org;"));
        assertEquals(1, store.get(Uri.create("https://y.x.foo.org/")).size());
    }

    // NOTE: Similar to replaceCookieWhenSetOnSameDomainAndPath()
    private static void replaceCookieWhenSetOnSamePathBySameUri() {
        CookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("https://foo.org/");
        store.add(uri, ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
        store.add(uri, ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
        store.add(uri, ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/"));
        assertEquals(1, store.getAll().size());
        assertEquals("VALUE3", store.get(uri).get(0).value());
    }

    private static void handleMultipleCookieOfSameNameOnDifferentPaths() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("cookie=VALUE0; path=/"));
        store.add(Uri.create("http://www.foo.com/foo/bar"), ClientCookieDecoder.LAX.decode("cookie=VALUE1; path=/foo/bar/"));
        store.add(Uri.create("http://www.foo.com/foo/baz"), ClientCookieDecoder.LAX.decode("cookie=VALUE2; path=/foo/baz/"));

        Uri uri1 = Uri.create("http://www.foo.com/foo/bar/");
        List<Cookie> cookies1 = store.get(uri1);
        assertEquals(2, cookies1.size());
        assertEquals(2, cookies1.stream().filter(c -> "VALUE0".equals(c.value()) || "VALUE1".equals(c.value())).count());

        Uri uri2 = Uri.create("http://www.foo.com/foo/baz/");
        List<Cookie> cookies2 = store.get(uri2);
        assertEquals(2, cookies2.size());
        assertEquals(2, cookies2.stream().filter(c -> "VALUE0".equals(c.value()) || "VALUE2".equals(c.value())).count());
    }

    private static void handleTrailingSlashesInPaths() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(
                Uri.create("https://vagrant.moolb.com/app/consumer/j_spring_cas_security_check?ticket=ST-5-Q7gzqPpvG3N3Bb02bm3q-llinder-vagrantmgr.moolb.com"),
                ClientCookieDecoder.LAX.decode("JSESSIONID=211D17F016132BCBD31D9ABB31D90960; Path=/app/consumer/; HttpOnly"));
        assertEquals(1, store.getAll().size());
        assertEquals("211D17F016132BCBD31D9ABB31D90960", store.get(Uri.create("https://vagrant.moolb.com/app/consumer/")).get(0).value());
    }

    private static void returnMultipleCookiesEvenIfTheyHaveSameName() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://foo.com"), ClientCookieDecoder.LAX.decode("JSESSIONID=FOO; Domain=.foo.com"));
        store.add(Uri.create("http://sub.foo.com"), ClientCookieDecoder.LAX.decode("JSESSIONID=BAR; Domain=sub.foo.com"));

        Uri uri1 = Uri.create("http://sub.foo.com");
        List<Cookie> cookies1 = store.get(uri1);
        assertEquals(2, cookies1.size());
        assertEquals(2, cookies1.stream().filter(c -> "FOO".equals(c.value()) || "BAR".equals(c.value())).count());

        List<String> encodedCookieStrings = cookies1.stream().map(ClientCookieEncoder.LAX::encode).collect(Collectors.toList());
        assertTrue(encodedCookieStrings.contains("JSESSIONID=FOO"));
        assertTrue(encodedCookieStrings.contains("JSESSIONID=BAR"));
    }

    // rfc6265#section-1 Cookies for a given host are shared  across all the ports on that host
    private static void shouldServeCookiesBasedOnTheUriScheme() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("https://foo.org/moodle/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
        store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
        store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/; Secure"));

        Uri uri = Uri.create("https://foo.org/moodle/login");
        assertEquals(1, store.getAll().size());
        assertEquals("VALUE3", store.get(uri).get(0).value());
        assertTrue(store.get(uri).get(0).isSecure());
    }

    // rfc6265#section-1 Cookies for a given host are shared  across all the ports on that host
    private static void shouldAlsoServeNonSecureCookiesBasedOnTheUriScheme() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("https://foo.org/moodle/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
        store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
        store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/; HttpOnly"));

        Uri uri = Uri.create("https://foo.org/moodle/login");
        assertEquals(1, store.getAll().size());
        assertEquals("VALUE3", store.get(uri).get(0).value());
        assertFalse(store.get(uri).get(0).isSecure());
    }

    // rfc6265#section-1 Cookies for a given host are shared  across all the ports on that host
    private static void shouldNotServeSecureCookiesForDefaultRetrievedHttpUriScheme() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("https://foo.org/moodle/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
        store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
        store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/; Secure"));

        Uri uri = Uri.create("http://foo.org/moodle/login");
        assertTrue(store.get(uri).isEmpty());
    }

    // rfc6265#section-1 Cookies for a given host are shared  across all the ports on that host
    private static void shouldServeSecureCookiesForSpecificallyRetrievedHttpUriScheme() {
        CookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("https://foo.org/moodle/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
        store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
        store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/; Secure"));

        Uri uri = Uri.create("https://foo.org/moodle/login");
        assertEquals(1, store.get(uri).size());
        assertEquals("VALUE3", store.get(uri).get(0).value());
        assertTrue(store.get(uri).get(0).isSecure());
    }

    private static void shouldCleanExpiredCookieFromUnderlyingDataStructure() throws Exception {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("https://foo.org/moodle/"), getCookie("JSESSIONID", "FOO", 1));
        store.add(Uri.create("https://bar.org/moodle/"), getCookie("JSESSIONID", "BAR", 1));
        store.add(Uri.create("https://bar.org/moodle/"), new DefaultCookie("UNEXPIRED_BAR", "BAR"));
        store.add(Uri.create("https://foobar.org/moodle/"), new DefaultCookie("UNEXPIRED_FOOBAR", "FOOBAR"));


        assertEquals(4, store.getAll().size());
        Thread.sleep(2000);
        store.evictExpired();
        assertEquals(2, store.getUnderlying().size());
        Collection<String> unexpiredCookieNames = store.getAll().stream().map(Cookie::name).collect(Collectors.toList());
        assertTrue(unexpiredCookieNames.containsAll(Set.of("UNEXPIRED_BAR", "UNEXPIRED_FOOBAR")));
    }

    private static Cookie getCookie(String key, String value, int maxAge) {
        DefaultCookie cookie = new DefaultCookie(key, value);
        cookie.setMaxAge(maxAge);
        return cookie;
    }
}
