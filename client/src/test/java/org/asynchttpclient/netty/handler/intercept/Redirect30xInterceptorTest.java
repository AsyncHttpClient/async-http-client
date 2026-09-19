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

    private static Uri uri(String scheme, String host, int port) {
        return new Uri(scheme, null, host, port, "/", null, null);
    }

    @Test
    public void sameOriginIgnoresHostCase() {
        // RFC 3986 3.2.2: host is case-insensitive, so this is one origin. Uri lower-cases the scheme but not
        // the host, so isSameBase would call it cross-origin and the arm would fail a same-origin upload.
        assertTrue(Redirect30xInterceptor.sameOrigin(uri("https", "example.com", -1),
                uri("https", "EXAMPLE.com", -1)));
        assertTrue(Redirect30xInterceptor.sameOrigin(uri("https", "EXAMPLE.COM", 8443),
                uri("https", "example.com", 8443)));
    }

    /**
     * The only place the ASCII-only fold can be pinned: no integration test can reach a non-ASCII host, since
     * a received Location cannot carry one (Netty decodes headers byte per char) and it would have to resolve.
     * <p>
     * U+0130 folds to ASCII 'i' under String.equalsIgnoreCase, while IDN.toASCII("\\u0130.example") is
     * "xn--i-9bb.example" - a different host we would then hand the content to. Swap the comparison back to
     * String.equalsIgnoreCase and this fails.
     */
    @Test
    public void sameOriginDoesNotFoldOutsideAscii() {
        Uri dotted = uri("https", "\u0130.example", -1);
        Uri ascii = uri("https", "i.example", -1);

        assertTrue(dotted.getHost().equalsIgnoreCase(ascii.getHost()),
                "precondition: String.equalsIgnoreCase folds these together, which is why AsciiString is used");
        assertFalse(Redirect30xInterceptor.sameOrigin(dotted, ascii),
                "a non-ASCII host must not be folded onto an ASCII one");
        assertFalse(Redirect30xInterceptor.sameOrigin(ascii, dotted));
    }

    @Test
    public void sameOriginComparesSchemeAndEffectivePort() {
        assertFalse(Redirect30xInterceptor.sameOrigin(uri("https", "example.com", -1),
                uri("http", "example.com", -1)), "scheme is part of the origin");
        assertFalse(Redirect30xInterceptor.sameOrigin(uri("https", "example.com", -1),
                uri("https", "other.example", -1)), "host is part of the origin");
        assertFalse(Redirect30xInterceptor.sameOrigin(uri("https", "example.com", 8443),
                uri("https", "example.com", 9443)), "port is part of the origin");

        // -1 means "the scheme's default port", so these are the same origin.
        assertTrue(Redirect30xInterceptor.sameOrigin(uri("https", "example.com", -1),
                uri("https", "example.com", 443)));
        assertTrue(Redirect30xInterceptor.sameOrigin(uri("http", "example.com", 80),
                uri("http", "example.com", -1)));
    }
}
