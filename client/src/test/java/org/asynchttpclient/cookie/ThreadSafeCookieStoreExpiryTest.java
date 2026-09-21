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

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ThreadSafeCookieStoreExpiryTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final ThreadSafeCookieStore store = new ThreadSafeCookieStore(now::get);

    @Test
    public void evictExpiredRemovesExpiredCookiesAndEmptyDomains() {
        store.add(Uri.create("https://foo.org/moodle/"), cookie("JSESSIONID", "FOO", 1));
        store.add(Uri.create("https://bar.org/moodle/"), cookie("JSESSIONID", "BAR", 1));
        store.add(Uri.create("https://bar.org/moodle/"), new DefaultCookie("UNEXPIRED_BAR", "BAR"));
        store.add(Uri.create("https://foobar.org/moodle/"), new DefaultCookie("UNEXPIRED_FOOBAR", "FOOBAR"));
        assertEquals(4, store.getAll().size());

        now.addAndGet(1000);
        store.evictExpired();
        assertEquals(3, store.getUnderlying().size());
        assertEquals(4, store.getAll().size());

        now.addAndGet(1000);
        store.evictExpired();
        assertEquals(2, store.getUnderlying().size());
        assertEquals(Set.of("UNEXPIRED_BAR", "UNEXPIRED_FOOBAR"),
                store.getAll().stream().map(Cookie::name).collect(Collectors.toSet()));
    }

    private static Cookie cookie(String name, String value, long maxAge) {
        DefaultCookie cookie = new DefaultCookie(name, value);
        cookie.setMaxAge(maxAge);
        return cookie;
    }
}
