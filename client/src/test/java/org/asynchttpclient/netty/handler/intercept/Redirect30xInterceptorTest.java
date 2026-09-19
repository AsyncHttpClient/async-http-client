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

import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link Redirect30xInterceptor#isRedirect(int)}: the 3xx class check and the
 * {@link Redirect30xInterceptor#REDIRECT_STATUSES} membership must agree, so a 3xx that is not a followed
 * redirect is rejected just like a non-3xx status.
 */
public class Redirect30xInterceptorTest {

    private static Uri host(String scheme, String host, int port) {
        return new Uri(scheme, null, host, port, "/", null, null);
    }

    @Test
    public void sameOriginFoldsHostCaseButOnlyInAscii() {
        assertTrue(Redirect30xInterceptor.sameOrigin(host("https", "example.com", 443),
                host("https", "EXAMPLE.com", 443)));

        // The one case an integration test cannot reach, because Netty decodes header values byte per char:
        // a non-ASCII host on the caller's own request URI. String.equalsIgnoreCase would call these equal,
        // and \\u0130.example punycodes to xn--i-9bb.example, a different host.
        assertFalse(Redirect30xInterceptor.sameOrigin(host("https", "i.example", 443),
                host("https", "\u0130.example", 443)));
    }

    @Test
    public void sameOriginResolvesTheDefaultPort() {
        assertTrue(Redirect30xInterceptor.sameOrigin(host("https", "example.com", -1),
                host("https", "example.com", 443)));
        assertTrue(Redirect30xInterceptor.sameOrigin(host("ws", "example.com", -1),
                host("ws", "example.com", 80)));
    }

    @Test
    public void secureUpgradeCoversTheDefaultPortPair() {
        assertTrue(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", -1),
                host("https", "example.com", -1)));
        assertTrue(Redirect30xInterceptor.secureUpgrade(host("ws", "example.com", -1),
                host("wss", "example.com", -1)));
        // RFC 6797 section 8.3 preserves an explicit port rather than inventing one.
        assertTrue(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", 8080),
                host("https", "example.com", 8080)));
        // Both spellings of the default pair, since getExplicitPort resolves -1 through the scheme default.
        assertTrue(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", 80),
                host("https", "example.com", -1)));
        assertTrue(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", -1),
                host("https", "example.com", 443)));
        // Deliberately looser than RFC 6797 section 8.3, which would map an explicit 80 to 443. Keeping the
        // port means the hop lands on the endpoint the redirect was served from, so it reaches nobody new.
        assertTrue(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", 80),
                host("https", "example.com", 80)));
        assertTrue(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", -1),
                host("https", "example.com", 80)));
    }

    @Test
    public void secureUpgradeRefusesAnythingButTheSameHostGoingSecure() {
        assertFalse(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", 8080),
                host("https", "example.com", 9999)), "a port change is not an upgrade");
        assertFalse(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", -1),
                host("https", "other.example", -1)), "the host has to match");
        assertFalse(Redirect30xInterceptor.secureUpgrade(host("https", "example.com", -1),
                host("http", "example.com", -1)), "a downgrade is not an upgrade");
        assertFalse(Redirect30xInterceptor.secureUpgrade(host("http", "example.com", -1),
                host("wss", "example.com", -1)), "only http to https and ws to wss count");
    }

    @Test
    public void sameOriginSeparatesSchemeAndPort() {
        assertFalse(Redirect30xInterceptor.sameOrigin(host("https", "example.com", 443),
                host("http", "example.com", 443)));
        assertFalse(Redirect30xInterceptor.sameOrigin(host("https", "example.com", 443),
                host("https", "example.com", 8443)));
    }

    @Test
    public void acceptsTheFollowedRedirectStatuses() {
        for (int statusCode : new int[]{301, 302, 303, 307, 308}) {
            assertTrue(Redirect30xInterceptor.isRedirect(statusCode), statusCode + " should be a redirect");
        }
    }

    @Test
    public void rejects3xxStatusesThatAreNotFollowed() {
        // in the 3xx class, but not redirects this interceptor acts on: 304 in particular must fall through
        // to the normal response path rather than be treated as a redirect
        for (int statusCode : new int[]{300, 304, 305, 306, 399}) {
            assertFalse(Redirect30xInterceptor.isRedirect(statusCode), statusCode + " should not be a redirect");
        }
    }

    @Test
    public void rejectsStatusesOutsideThe3xxClass() {
        for (int statusCode : new int[]{100, 200, 204, 299, 400, 404, 500}) {
            assertFalse(Redirect30xInterceptor.isRedirect(statusCode), statusCode + " should not be a redirect");
        }
    }
}
