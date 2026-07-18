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
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior-equivalence tests for the imperative {@link ThreadSafeCookieStore#get(Uri)} scan that
 * replaced the per-sub-domain Stream pipeline. The returned cookie set must be unchanged; iteration
 * order is not contractual (Netty's {@code ClientCookieEncoder} re-sorts on the wire), so results are
 * compared as a SET of {@code name=value} pairs.
 */
public class ThreadSafeCookieStoreGetTest {

    private static Set<String> namesValues(List<Cookie> cookies) {
        Set<String> out = new TreeSet<>();
        for (Cookie c : cookies) {
            out.add(c.name() + '=' + c.value());
        }
        return out;
    }

    private static Set<String> setOf(String... values) {
        Set<String> out = new TreeSet<>();
        for (String v : values) {
            out.add(v);
        }
        return out;
    }

    @Test
    public void returnsCookieOnExactDomainAndPath() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar"),
                ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));

        assertEquals(setOf("ALPHA=VALUE1"), namesValues(store.get(Uri.create("http://www.foo.com/bar/baz"))));
    }

    @Test
    public void inheritsParentDomainCookieButExcludesHostOnlyCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        // Domain cookie on .foo.com -> visible to sub.foo.com
        store.add(Uri.create("http://foo.com/"),
                ClientCookieDecoder.LAX.decode("DOMAINCK=DV; Domain=foo.com; Path=/"));
        // Host-only cookie on foo.com (no Domain attr) -> NOT visible to a sub-domain request
        store.add(Uri.create("http://foo.com/"),
                ClientCookieDecoder.LAX.decode("HOSTONLY=HV; Path=/"));

        // Request to the sub-domain: sees the domain cookie, not the host-only one.
        assertEquals(setOf("DOMAINCK=DV"), namesValues(store.get(Uri.create("http://sub.foo.com/"))));
        // Request to the exact host: sees both.
        assertEquals(setOf("DOMAINCK=DV", "HOSTONLY=HV"), namesValues(store.get(Uri.create("http://foo.com/"))));
    }

    @Test
    public void excludesSecureCookieOnInsecureRequest() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("https://www.foo.com/"),
                ClientCookieDecoder.LAX.decode("SEC=SV; Domain=www.foo.com; Path=/; Secure"));
        store.add(Uri.create("http://www.foo.com/"),
                ClientCookieDecoder.LAX.decode("PLAIN=PV; Domain=www.foo.com; Path=/"));

        // Insecure request: only the non-secure cookie.
        assertEquals(setOf("PLAIN=PV"), namesValues(store.get(Uri.create("http://www.foo.com/"))));
        // Secure request: both.
        assertEquals(setOf("SEC=SV", "PLAIN=PV"), namesValues(store.get(Uri.create("https://www.foo.com/"))));
    }

    @Test
    public void excludesExpiredCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/bar"),
                ClientCookieDecoder.LAX.decode("LIVE=VALUE1; Domain=www.foo.com; path=/bar"));
        // Max-Age=0 expires immediately; the store drops it on add, so it must never be returned.
        store.add(Uri.create("http://www.foo.com/bar"),
                ClientCookieDecoder.LAX.decode("DEAD=GONE; Domain=www.foo.com; path=/bar; Max-Age=0"));

        assertEquals(setOf("LIVE=VALUE1"), namesValues(store.get(Uri.create("http://www.foo.com/bar"))));
    }

    @Test
    public void returnsMultipleDistinctCookiesAtSameDomainPath() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com/bar");
        store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=AV; Domain=www.foo.com; path=/bar"));
        store.add(uri, ClientCookieDecoder.LAX.decode("BETA=BV; Domain=www.foo.com; path=/bar"));

        assertEquals(setOf("ALPHA=AV", "BETA=BV"), namesValues(store.get(uri)));
    }

    @Test
    public void perDomainCookieCountIsCappedUnderFlood() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com/");
        int flood = ThreadSafeCookieStore.MAX_COOKIES_PER_DOMAIN + 50;
        for (int i = 0; i < flood; i++) {
            store.add(uri, ClientCookieDecoder.LAX.decode("c" + i + "=v" + i + "; Domain=www.foo.com; Path=/"));
        }
        assertEquals(ThreadSafeCookieStore.MAX_COOKIES_PER_DOMAIN, store.getUnderlying().get("www.foo.com").size(),
                "a single domain's cookies must be capped at MAX_COOKIES_PER_DOMAIN");
    }

    @Test
    public void cookiesUnderTheCapAreAllRetained() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com/");
        for (int i = 0; i < 20; i++) {
            store.add(uri, ClientCookieDecoder.LAX.decode("c" + i + "=v" + i + "; Domain=www.foo.com; Path=/"));
        }
        assertEquals(20, store.getUnderlying().get("www.foo.com").size(), "nothing is evicted below the cap");
        assertEquals(20, store.get(uri).size());
    }

    @Test
    public void capIsPerDomainNotGlobal() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        Uri foo = Uri.create("http://www.foo.com/");
        for (int i = 0; i < ThreadSafeCookieStore.MAX_COOKIES_PER_DOMAIN + 20; i++) {
            store.add(foo, ClientCookieDecoder.LAX.decode("c" + i + "=v" + i + "; Domain=www.foo.com; Path=/"));
        }
        store.add(Uri.create("http://www.bar.com/"),
                ClientCookieDecoder.LAX.decode("only=1; Domain=www.bar.com; Path=/"));

        assertEquals(ThreadSafeCookieStore.MAX_COOKIES_PER_DOMAIN, store.getUnderlying().get("www.foo.com").size());
        assertEquals(1, store.getUnderlying().get("www.bar.com").size(),
                "flooding one domain must not evict another domain's cookies");
    }

    @Test
    public void evictionDropsExpiredCookiesBeforeLiveOnes() throws InterruptedException {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        Uri uri = Uri.create("http://www.foo.com/");
        int cap = ThreadSafeCookieStore.MAX_COOKIES_PER_DOMAIN;
        int live = cap - 5;

        // Fill the bucket exactly to the cap: (cap - 5) session cookies that never expire ...
        for (int i = 0; i < live; i++) {
            store.add(uri, ClientCookieDecoder.LAX.decode("live" + i + "=v; Domain=www.foo.com; Path=/"));
        }
        // ... plus 5 short-lived cookies that will expire before the next add.
        for (int i = 0; i < 5; i++) {
            store.add(uri, ClientCookieDecoder.LAX.decode("exp" + i + "=v; Domain=www.foo.com; Path=/; Max-Age=1"));
        }
        assertEquals(cap, store.getUnderlying().get("www.foo.com").size(), "precondition: bucket filled to the cap");

        // Max-Age is second-granular, so let > 1s pass for the five short-lived cookies to expire.
        Thread.sleep(2100);

        // This add pushes the bucket over the cap and triggers eviction. RFC 6265 §5.3 drops expired
        // cookies first, so all five expired ones go and no live cookie is evicted.
        store.add(uri, ClientCookieDecoder.LAX.decode("trigger=v; Domain=www.foo.com; Path=/"));

        assertEquals(live + 1, store.getUnderlying().get("www.foo.com").size(),
                "eviction must drop the expired cookies first, leaving every live cookie in place");
        assertEquals(live + 1, store.get(uri).size(), "all live cookies (and only those) remain retrievable");
    }

    @Test
    public void returnsEmptyForUnknownDomain() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        store.add(Uri.create("http://www.foo.com/"),
                ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; Path=/"));

        List<Cookie> result = store.get(Uri.create("http://www.bar.com/"));
        assertTrue(result.isEmpty(), "no cookies should match an unrelated domain");
    }
}
