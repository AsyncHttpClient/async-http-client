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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RFC 6265bis Section 5.7 steps 14 and 22: a Secure cookie is ignored when it arrives over a non-secure
 * scheme, and a non-Secure cookie arriving over a non-secure scheme must not overlay a Secure cookie the
 * store already holds. Without either rule anyone on the plaintext path of http://example.com can plant or
 * replace the cookie the site only ever sets inside TLS.
 */
public class SecureCookieSchemeTest {

    private static void set(ThreadSafeCookieStore store, String url, String setCookie) {
        store.add(Uri.create(url), ClientCookieDecoder.LAX.decode(setCookie));
    }

    private static String valueOf(ThreadSafeCookieStore store, String url, String name) {
        List<Cookie> cookies = store.get(Uri.create(url));
        return cookies.stream().filter(c -> name.equals(c.name())).map(Cookie::value).findFirst().orElse(null);
    }

    @Test
    public void aSecureCookieSetOverPlaintextIsIgnored() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "http://example.com/", "SID=planted; Path=/; Secure");

        assertNull(valueOf(store, "https://example.com/", "SID"),
                "a Secure cookie received over http must not be served to https");
        assertNull(valueOf(store, "http://example.com/", "SID"),
                "a Secure cookie received over http must not be stored at all");
    }

    @Test
    public void aPlaintextCookieCannotOverlayASecureCookie() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://example.com/", "SID=real; Path=/; Secure");

        set(store, "http://example.com/", "SID=planted; Path=/");
        assertEquals("real", valueOf(store, "https://example.com/", "SID"),
                "a non-Secure cookie received over http must not replace a Secure cookie");

        set(store, "http://www.example.com/", "SID=planted; Domain=example.com; Path=/");
        assertEquals("real", valueOf(store, "https://example.com/", "SID"),
                "a domain cookie received over http must not replace a Secure host cookie it domain-matches");

        set(store, "http://example.com/", "SID=; Path=/; Max-Age=0");
        assertEquals("real", valueOf(store, "https://example.com/", "SID"),
                "an expiring cookie received over http must not delete a Secure cookie");
    }

    @Test
    public void thePathOverlayRuleIsOneWay() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://example.com/login", "SID=real; Path=/login; Secure");

        set(store, "http://example.com/foo", "SID=other; Path=/foo");
        assertEquals("other", valueOf(store, "http://example.com/foo", "SID"),
                "a non-Secure cookie on an unrelated path is still stored");

        set(store, "http://example.com/login/en", "SID=planted; Path=/login/en");
        assertNull(valueOf(store, "http://example.com/login/en", "SID"),
                "a non-Secure cookie whose path sits under the Secure cookie's path is ignored");
        assertEquals("real", valueOf(store, "https://example.com/login/en", "SID"));
    }

    @Test
    public void cookiesSetOverASecureSchemeAreUnaffected() {
        ThreadSafeCookieStore store = new ThreadSafeCookieStore();
        set(store, "https://example.com/", "SID=real; Path=/; Secure");
        assertEquals("real", valueOf(store, "https://example.com/", "SID"));

        set(store, "https://example.com/", "SID=renewed; Path=/");
        assertEquals("renewed", valueOf(store, "https://example.com/", "SID"),
                "the origin itself may still replace its Secure cookie over https");

        set(store, "http://example.com/", "lang=en; Path=/");
        assertEquals("en", valueOf(store, "http://example.com/", "lang"),
                "an ordinary http cookie with no Secure counterpart is still stored");
    }
}
