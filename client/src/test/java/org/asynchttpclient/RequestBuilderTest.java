/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestBuilderTest {

    private static final String SAFE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890-_*.";
    private static final String HEX_CHARS = "0123456789ABCDEF";

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testEncodesQueryParameters() {
        String[] values = {"abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKQLMNOPQRSTUVWXYZ", "1234567890", "1234567890", "`~!@#$%^&*()", "`~!@#$%^&*()", "_+-=,.<>/?",
                "_+-=,.<>/?", ";:'\"[]{}\\| ", ";:'\"[]{}\\| "};

        /*
         * as per RFC-5849 (Oauth), and RFC-3986 (percent encoding) we MUST
         * encode everything except for "safe" characters; and nothing but them.
         * Safe includes ascii letters (upper and lower case), digits (0 - 9)
         * and FOUR special characters: hyphen ('-'), underscore ('_'), tilde
         * ('~') and period ('.')). Everything else must be percent-encoded,
         * byte-by-byte, using UTF-8 encoding (meaning three-byte Unicode/UTF-8
         * code points are encoded as three three-letter percent-encode
         * entities).
         */
        for (String value : values) {
            RequestBuilder builder = get("http://example.com/").addQueryParam("name", value);

            StringBuilder sb = new StringBuilder();
            for (int i = 0, len = value.length(); i < len; ++i) {
                char c = value.charAt(i);
                if (SAFE_CHARS.indexOf(c) >= 0) {
                    sb.append(c);
                } else {
                    int hi = c >> 4;
                    int lo = c & 0xF;
                    sb.append('%').append(HEX_CHARS.charAt(hi)).append(HEX_CHARS.charAt(lo));
                }
            }
            String expValue = sb.toString();
            Request request = builder.build();
            assertEquals(request.getUrl(), "http://example.com/?name=" + expValue);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testChaining() {
        Request request = get("http://foo.com").addQueryParam("x", "value").build();
        Request request2 = request.toBuilder().build();

        assertEquals(request2.getUri(), request.getUri());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testParsesQueryParams() {
        Request request = get("http://foo.com/?param1=value1").addQueryParam("param2", "value2").build();

        assertEquals(request.getUrl(), "http://foo.com/?param1=value1&param2=value2");
        List<Param> params = request.getQueryParams();
        assertEquals(params.size(), 2);
        assertEquals(params.get(0), new Param("param1", "value1"));
        assertEquals(params.get(1), new Param("param2", "value2"));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testUserProvidedRequestMethod() {
        Request req = new RequestBuilder("ABC").setUrl("http://foo.com").build();
        assertEquals(req.getMethod(), "ABC");
        assertEquals(req.getUrl(), "http://foo.com");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPercentageEncodedUserInfo() {
        final Request req = get("http://hello:wor%20ld@foo.com").build();
        assertEquals(req.getMethod(), "GET");
        assertEquals(req.getUrl(), "http://hello:wor%20ld@foo.com");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testContentTypeCharsetToBodyEncoding() {
        final Request req = get("http://localhost").setHeader("Content-Type", "application/json; charset=utf-8").build();
        assertEquals(req.getCharset(), UTF_8);
        final Request req2 = get("http://localhost").setHeader("Content-Type", "application/json; charset=\"utf-8\"").build();
        assertEquals(req2.getCharset(), UTF_8);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testDefaultMethod() {
        RequestBuilder requestBuilder = new RequestBuilder();
        String defaultMethodName = HttpMethod.GET.name();
        assertEquals(requestBuilder.method, defaultMethodName, "Default HTTP method should be " + defaultMethodName);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSetHeaders() {
        RequestBuilder requestBuilder = new RequestBuilder();
        assertTrue(requestBuilder.headers.isEmpty(), "Headers should be empty by default.");

        Map<CharSequence, Collection<?>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singleton("application/json"));
        requestBuilder.setHeaders(headers);
        assertTrue(requestBuilder.headers.contains("Content-Type"), "headers set by setHeaders have not been set");
        assertEquals(requestBuilder.headers.get("Content-Type"), "application/json", "header value incorrect");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testAddOrReplaceCookies() {
        RequestBuilder requestBuilder = new RequestBuilder();
        Cookie cookie = new DefaultCookie("name", "value");
        cookie.setDomain("google.com");
        cookie.setPath("/");
        cookie.setMaxAge(1000);
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        requestBuilder.addOrReplaceCookie(cookie);
        assertEquals(requestBuilder.cookies.size(), 1, "cookies size should be 1 after adding one cookie");
        assertEquals(requestBuilder.cookies.get(0), cookie, "cookie does not match");

        Cookie cookie2 = new DefaultCookie("name", "value");
        cookie2.setDomain("google2.com");
        cookie2.setPath("/path");
        cookie2.setMaxAge(1001);
        cookie2.setSecure(false);
        cookie2.setHttpOnly(false);

        requestBuilder.addOrReplaceCookie(cookie2);
        assertEquals(requestBuilder.cookies.size(), 1, "cookies size should remain 1 as we just replaced a cookie with same name");
        assertEquals(requestBuilder.cookies.get(0), cookie2, "cookie does not match");

        Cookie cookie3 = new DefaultCookie("name2", "value");
        cookie3.setDomain("google.com");
        cookie3.setPath("/");
        cookie3.setMaxAge(1000);
        cookie3.setSecure(true);
        cookie3.setHttpOnly(true);
        requestBuilder.addOrReplaceCookie(cookie3);
        assertEquals(requestBuilder.cookies.size(), 2, "cookie size must be 2 after adding 1 more cookie i.e. cookie3");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testAddIfUnsetCookies() {
        RequestBuilder requestBuilder = new RequestBuilder();
        Cookie cookie = new DefaultCookie("name", "value");
        cookie.setDomain("google.com");
        cookie.setPath("/");
        cookie.setMaxAge(1000);
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        requestBuilder.addCookieIfUnset(cookie);
        assertEquals(requestBuilder.cookies.size(), 1, "cookies size should be 1 after adding one cookie");
        assertEquals(requestBuilder.cookies.get(0), cookie, "cookie does not match");

        Cookie cookie2 = new DefaultCookie("name", "value");
        cookie2.setDomain("google2.com");
        cookie2.setPath("/path");
        cookie2.setMaxAge(1001);
        cookie2.setSecure(false);
        cookie2.setHttpOnly(false);

        requestBuilder.addCookieIfUnset(cookie2);
        assertEquals(requestBuilder.cookies.size(), 1, "cookies size should remain 1 as we just ignored cookie2 because of a cookie with same name");
        assertEquals(requestBuilder.cookies.get(0), cookie, "cookie does not match");

        Cookie cookie3 = new DefaultCookie("name2", "value");
        cookie3.setDomain("google.com");
        cookie3.setPath("/");
        cookie3.setMaxAge(1000);
        cookie3.setSecure(true);
        cookie3.setHttpOnly(true);
        requestBuilder.addCookieIfUnset(cookie3);
        assertEquals(requestBuilder.cookies.size(), 2, "cookie size must be 2 after adding 1 more cookie i.e. cookie3");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSettingQueryParamsBeforeUrlShouldNotProduceNPE() {
        RequestBuilder requestBuilder = new RequestBuilder();
        requestBuilder.setQueryParams(singletonList(new Param("key", "value")));
        requestBuilder.setUrl("http://localhost");
        Request request = requestBuilder.build();
        assertEquals(request.getUrl(), "http://localhost?key=value");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSettingHeadersUsingMapWithStringKeys() {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("X-Forwarded-For", singletonList("10.0.0.1"));

        RequestBuilder requestBuilder = new RequestBuilder();
        requestBuilder.setHeaders(headers);
        requestBuilder.setUrl("http://localhost");
        Request request = requestBuilder.build();
        assertEquals(request.getHeaders().get("X-Forwarded-For"), "10.0.0.1");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testUserSetTextPlainContentTypeShouldNotBeModified() {
        Request request = post("http://localhost/test")
                .setHeader("Content-Type", "text/plain")
                .setBody("Hello World")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type should not be modified when user explicitly sets it");
        assertFalse(contentType.contains("charset"), "Charset should not be added to user-specified Content-Type");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testUserSetTextXmlContentTypeShouldNotBeModified() {
        Request request = post("http://localhost/test")
                .setHeader("Content-Type", "text/xml")
                .setBody("<test>Hello</test>")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/xml", contentType, "Content-Type should not be modified when user explicitly sets it");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testUserSetTextHtmlContentTypeShouldNotBeModified() {
        Request request = post("http://localhost/test")
                .setHeader("Content-Type", "text/html")
                .setBody("<html></html>")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/html", contentType, "Content-Type should not be modified when user explicitly sets it");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testUserSetContentTypeWithCharsetShouldBePreserved() {
        Request request = post("http://localhost/test")
                .setHeader("Content-Type", "text/xml; charset=ISO-8859-1")
                .setBody("<test>Hello</test>")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/xml; charset=ISO-8859-1", contentType, "User-specified charset should be preserved");
        assertTrue(contentType.contains("ISO-8859-1"), "ISO-8859-1 charset should be preserved");
        assertFalse(contentType.contains("UTF-8"), "UTF-8 should not be added");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testApplicationJsonContentTypeShouldNotBeModified() {
        Request request = post("http://localhost/test")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"key\": \"value\"}")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("application/json", contentType, "application/json should not be modified");
        assertFalse(contentType.contains("charset"), "Charset should not be added to application/json");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testAddHeaderContentTypeShouldNotBeModified() {
        Request request = post("http://localhost/test")
                .addHeader("Content-Type", "text/plain")
                .setBody("Hello World")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type set via addHeader should not be modified");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSetHeadersWithHttpHeadersShouldLockContentType() {
        HttpHeaders httpHeaders = new DefaultHttpHeaders();
        httpHeaders.set("Content-Type", "text/plain");

        Request request = post("http://localhost/test")
                .setHeaders(httpHeaders)
                .setBody("Hello World")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type set via setHeaders(HttpHeaders) should not be modified");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSetHeadersWithMapShouldLockContentType() {
        Map<String, List<String>> headerMap = new HashMap<>();
        headerMap.put("Content-Type", singletonList("text/plain"));

        Request request = post("http://localhost/test")
                .setHeaders(headerMap)
                .setBody("Hello World")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type set via setHeaders(Map) should not be modified");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSetSingleHeadersShouldLockContentType() {
        Map<String, String> headerMap = new HashMap<>();
        headerMap.put("Content-Type", "text/plain");

        Request request = post("http://localhost/test")
                .setSingleHeaders(headerMap)
                .setBody("Hello World")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type set via setSingleHeaders should not be modified");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testClearHeadersShouldResetContentTypeLock() {
        Request request = post("http://localhost/test")
                .setHeader("Content-Type", "text/plain")
                .clearHeaders()
                .setHeader("Content-Type", "text/xml")
                .setBody("<test></test>")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/xml", contentType, "Content-Type should still be preserved after clear and re-set");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPrototypeRequestShouldPreserveContentType() {
        Request original = post("http://localhost/test")
                .setHeader("Content-Type", "text/plain")
                .setBody("Hello")
                .build();

        Request copy = post("http://localhost/test")
                .setUrl(original.getUri().toUrl())
                .setHeaders(original.getHeaders())
                .setBody("Hello")
                .build();

        String contentType = copy.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type should be preserved from prototype");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testRequestBuilderFromPrototypeShouldPreserveContentType() {
        Request original = post("http://localhost/test")
                .setHeader("Content-Type", "text/plain")
                .setBody("Hello")
                .build();

        Request copy = new RequestBuilder(original).build();

        String contentType = copy.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type should be preserved from prototype via RequestBuilder");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testCaseInsensitiveContentTypeHeader() {
        Request request = post("http://localhost/test")
                .setHeader("content-type", "text/plain")
                .setBody("Hello World")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type should be matched case-insensitively");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSetHeaderWithIterableShouldLockContentType() {
        Request request = post("http://localhost/test")
                .setHeader("Content-Type", singletonList("text/plain"))
                .setBody("Hello World")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type set via setHeader(Iterable) should not be modified");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testAddHeaderWithIterableShouldLockContentType() {
        Request request = post("http://localhost/test")
                .addHeader("Content-Type", singletonList("text/plain"))
                .setBody("Hello World")
                .build();

        String contentType = request.getHeaders().get("Content-Type");
        assertEquals("text/plain", contentType, "Content-Type set via addHeader(Iterable) should not be modified");
    }
}
