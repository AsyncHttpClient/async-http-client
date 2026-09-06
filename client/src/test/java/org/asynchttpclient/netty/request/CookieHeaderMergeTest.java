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
package org.asynchttpclient.netty.request;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A caller's own {@code Cookie} header must survive the request's cookie list, which used to replace it
 * outright even when the two named different cookies.
 */
public class CookieHeaderMergeTest {

    private static String outgoingCookieHeader(Request request) {
        NettyRequestFactory factory = new NettyRequestFactory(new DefaultAsyncHttpClientConfig.Builder().build());
        HttpRequest nettyRequest = factory.newNettyRequest(request, false, null, null, null).getHttpRequest();
        List<String> all = nettyRequest.headers().getAll(COOKIE);
        // rfc6265#section-5.4: a user agent MUST NOT attach more than one Cookie header field.
        assertTrue(all.size() <= 1, "expected at most one Cookie header, got " + all);
        return all.isEmpty() ? null : all.get(0);
    }

    @Test
    public void aCallerSetHeaderSurvivesAStoreCookieOfTheSameName() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "session=userA")
                .addCookie(new DefaultCookie("session", "userB"))
                .build();

        assertEquals("session=userA", outgoingCookieHeader(request),
                "the caller's explicit header must win over a same-name cookie from the list");
    }

    @Test
    public void aCallerSetHeaderSurvivesAStoreCookieOfADifferentName() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "session=mine")
                .addCookie(new DefaultCookie("tracking", "xyz"))
                .build();

        String header = outgoingCookieHeader(request);
        assertTrue(header.contains("session=mine"), "the caller's cookie must not be dropped: " + header);
        assertTrue(header.contains("tracking=xyz"), "the list's cookie must still be sent: " + header);
    }

    @Test
    public void everyCallerCookieInOneHeaderSurvives() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "a=1; b=2")
                .addCookie(new DefaultCookie("c", "3"))
                .build();

        String header = outgoingCookieHeader(request);
        assertTrue(header.contains("a=1"), header);
        assertTrue(header.contains("b=2"), header);
        assertTrue(header.contains("c=3"), header);
    }

    @Test
    public void headerNameCasingDoesNotMatter() {
        // Netty's header map is case-insensitive, so a caller who wrote COOKIE must be found too.
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("COOKIE", "session=userA")
                .addCookie(new DefaultCookie("session", "userB"))
                .build();

        assertEquals("session=userA", outgoingCookieHeader(request));
    }

    @Test
    public void severalCallerHeadersAreFoldedIntoOne() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .addHeader("Cookie", "a=1")
                .addHeader("Cookie", "b=2")
                .addCookie(new DefaultCookie("c", "3"))
                .build();

        String header = outgoingCookieHeader(request);
        assertTrue(header.contains("a=1") && header.contains("b=2") && header.contains("c=3"), header);
    }

    @Test
    public void cookieNamesAreComparedCaseSensitively() {
        // Cookie names are case-sensitive, so the caller's SID leaves the list's sid.
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "SID=upper")
                .addCookie(new DefaultCookie("sid", "lower"))
                .build();

        String header = outgoingCookieHeader(request);
        assertTrue(header.contains("SID=upper"), header);
        assertTrue(header.contains("sid=lower"), header);
    }

    @Test
    public void aCallerHeaderWithNoListIsUntouched() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "session=only")
                .build();

        assertEquals("session=only", outgoingCookieHeader(request));
    }

    @Test
    public void aListWithNoCallerHeaderIsEncodedAsBefore() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .addCookie(new DefaultCookie("session", "only"))
                .build();

        assertEquals("session=only", outgoingCookieHeader(request));
    }

    @Test
    public void noCookiesAtAllSendsNoHeader() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/").build();

        assertNull(outgoingCookieHeader(request));
    }

    /** Several caller-set Cookie fields fold into one (RFC 6265 Section 5.4), even with nothing to merge. */
    @Test
    public void severalCallerHeadersAreFoldedEvenWithNoCookiesOfOurOwn() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .addHeader("Cookie", "a=1")
                .addHeader("Cookie", "b=2")
                .build();

        String header = outgoingCookieHeader(request);
        assertTrue(header.contains("a=1") && header.contains("b=2"), header);
    }

    /**
     * A {@code ;} inside a value is illegal but writable, and must come back as written. A jar cookie makes
     * this take the merge path.
     */
    @Test
    public void aCallerHeaderIsNotRewritten() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "a=\"x;y\"")
                .addCookie(new DefaultCookie("j", "1"))
                .build();

        String header = outgoingCookieHeader(request);
        assertTrue(header.startsWith("a=\"x;y\""), "the caller's value must survive byte for byte: " + header);
        assertTrue(header.contains("j=1"), header);
    }

    /** A caller header ending in the delimiter must not produce an empty cookie between it and ours. */
    @Test
    public void aTrailingSemicolonDoesNotDoubleUp() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "a=1;")
                .addCookie(new DefaultCookie("j", "1"))
                .build();

        assertEquals("a=1; j=1", outgoingCookieHeader(request));
    }

    /** {@code a = 1} still names {@code a}, so the jar's own {@code a} must not be sent beside it. */
    @Test
    public void aSpacedCallerNameStillSuppressesTheJarCookie() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "a = 1")
                .addCookie(new DefaultCookie("a", "fromJar"))
                .build();

        String header = outgoingCookieHeader(request);
        assertFalse(header.contains("fromJar"), "the jar must not override the caller's cookie: " + header);
    }

    /** An empty Cookie field is dropped. Netty rejects a whitespace-only value, so empty is the reachable case. */
    @Test
    public void anEmptyCallerHeaderIsDropped() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .setHeader("Cookie", "")
                .build();

        assertNull(outgoingCookieHeader(request));
    }

    @Test
    public void anEmptyOrDelimiterOnlyFieldBetweenOthersLeavesNoGap() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/")
                .addHeader("Cookie", "a=1")
                .addHeader("Cookie", "")
                .addHeader("Cookie", ";")
                .addHeader("Cookie", "; b=2;")
                .build();

        assertEquals("a=1; b=2", outgoingCookieHeader(request));
    }
}
