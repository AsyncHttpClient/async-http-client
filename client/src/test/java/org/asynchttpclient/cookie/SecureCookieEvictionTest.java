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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * rfc6265bis section 5.7: when removing excess cookies, non-Secure ones go before Secure ones, so plaintext
 * cannot flood a domain to evict a Secure cookie and then overlay it past step 16.
 */
public class SecureCookieEvictionTest {

    private static void set(ThreadSafeCookieStore store, String url, String setCookie) {
        store.add(Uri.create(url), ClientCookieDecoder.LAX.decode(setCookie));
    }

    private static String sent(ThreadSafeCookieStore store, String url, String name) {
        return store.get(Uri.create(url)).stream()
                .filter(c -> name.equals(c.name())).map(Cookie::value).findFirst().orElse(null);
    }

    @Test
    public void aPlaintextFloodCannotEvictASecureCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://example.com/", "SID=real; Path=/; Secure");

        for (int i = 0; i < ThreadSafeCookieStore.MAX_COOKIES_PER_DOMAIN; i++) {
            set(store, "http://example.com/", "junk" + i + "=x; Path=/");
        }
        set(store, "http://example.com/", "SID=planted; Path=/");

        assertEquals("real", sent(store, "https://example.com/", "SID"));
    }

    /** No network attacker: a plaintext sibling host floods the shared Domain bucket. */
    @Test
    public void aPlaintextSiblingFloodCannotEvictASecureDomainCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://www.example.com/", "SID=real; Domain=example.com; Path=/; Secure");

        for (int i = 0; i < ThreadSafeCookieStore.MAX_COOKIES_PER_DOMAIN; i++) {
            set(store, "http://blog.example.com/", "junk" + i + "=x; Domain=example.com; Path=/");
        }
        set(store, "http://blog.example.com/", "SID=planted; Domain=example.com; Path=/");

        assertEquals("real", sent(store, "https://www.example.com/", "SID"));
    }
}
