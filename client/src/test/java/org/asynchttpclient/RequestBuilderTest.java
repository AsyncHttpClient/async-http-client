/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.get;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.asynchttpclient.cookie.Cookie;
import org.testng.annotations.Test;

import io.netty.handler.codec.http.HttpMethod;

public class RequestBuilderTest {

    private final static String SAFE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890-_~.";
    private final static String HEX_CHARS = "0123456789ABCDEF";

    @Test(groups = "standalone")
    public void testEncodesQueryParameters() throws UnsupportedEncodingException {
        String[] values = new String[] { "abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKQLMNOPQRSTUVWXYZ", "1234567890", "1234567890", "`~!@#$%^&*()", "`~!@#$%^&*()", "_+-=,.<>/?",
                "_+-=,.<>/?", ";:'\"[]{}\\| ", ";:'\"[]{}\\| " };

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
                    int hi = (c >> 4);
                    int lo = c & 0xF;
                    sb.append('%').append(HEX_CHARS.charAt(hi)).append(HEX_CHARS.charAt(lo));
                }
            }
            String expValue = sb.toString();
            Request request = builder.build();
            assertEquals(request.getUrl(), "http://example.com/?name=" + expValue);
        }
    }

    @Test(groups = "standalone")
    public void testChaining() throws IOException, ExecutionException, InterruptedException {
        Request request = get("http://foo.com").addQueryParam("x", "value").build();

        Request request2 = new RequestBuilder(request).build();

        assertEquals(request2.getUri(), request.getUri());
    }

    @Test(groups = "standalone")
    public void testParsesQueryParams() throws IOException, ExecutionException, InterruptedException {
        Request request = get("http://foo.com/?param1=value1").addQueryParam("param2", "value2").build();

        assertEquals(request.getUrl(), "http://foo.com/?param1=value1&param2=value2");
        List<Param> params = request.getQueryParams();
        assertEquals(params.size(), 2);
        assertEquals(params.get(0), new Param("param1", "value1"));
        assertEquals(params.get(1), new Param("param2", "value2"));
    }

    @Test(groups = "standalone")
    public void testUserProvidedRequestMethod() {
        Request req = new RequestBuilder("ABC").setUrl("http://foo.com").build();
        assertEquals(req.getMethod(), "ABC");
        assertEquals(req.getUrl(), "http://foo.com");
    }

    @Test(groups = "standalone")
    public void testPercentageEncodedUserInfo() {
        final Request req = get("http://hello:wor%20ld@foo.com").build();
        assertEquals(req.getMethod(), "GET");
        assertEquals(req.getUrl(), "http://hello:wor%20ld@foo.com");
    }

    @Test(groups = "standalone")
    public void testContentTypeCharsetToBodyEncoding() {
        final Request req = get("http://localhost").setHeader("Content-Type", "application/json; charset=utf-8").build();
        assertEquals(req.getCharset(), UTF_8);
        final Request req2 = get("http://localhost").setHeader("Content-Type", "application/json; charset=\"utf-8\"").build();
        assertEquals(req2.getCharset(), UTF_8);
    }
    
    @Test
    public void testDefaultMethod() {
        RequestBuilder requestBuilder = new RequestBuilder();
        String defaultMethodName = HttpMethod.GET.name();
        assertEquals(requestBuilder.method, defaultMethodName, "Default HTTP method should be " + defaultMethodName);
    }

    @Test
    public void testSetHeaders() {
        RequestBuilder requestBuilder = new RequestBuilder();
        assertTrue(requestBuilder.headers.isEmpty(), "Headers should be empty by default.");

        Map<String, Collection<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singleton("application/json"));
        requestBuilder.setHeaders(headers);
        assertTrue(requestBuilder.headers.contains("Content-Type"), "headers set by setHeaders have not been set");
        assertEquals(requestBuilder.headers.get("Content-Type"), "application/json", "header value incorrect");
    }

    public void testAddOrReplaceCookies() {
        RequestBuilder requestBuilder = new RequestBuilder();
        Cookie cookie = new Cookie("name", "value", false, "google.com", "/", 1000, true, true);
        requestBuilder.addOrReplaceCookie(cookie);
        assertEquals(requestBuilder.cookies.size(), 1, "cookies size should be 1 after adding one cookie");
        assertEquals(requestBuilder.cookies.get(0), cookie, "cookie does not match");

        Cookie cookie2 = new Cookie("name", "value2", true, "google2.com", "/path", 1001, false, false);
        requestBuilder.addOrReplaceCookie(cookie2);
        assertEquals(requestBuilder.cookies.size(), 1, "cookies size should remain 1 as we just replaced a cookie with same name");
        assertEquals(requestBuilder.cookies.get(0), cookie2, "cookie does not match");

        Cookie cookie3 = new Cookie("name", "value", false, "google.com", "/", 1000, true, true);
        requestBuilder.addOrReplaceCookie(cookie3);
        assertEquals(requestBuilder.cookies.size(), 2, "cookie size must be 2 after adding 1 more cookie i.e. cookie3");
    }
}
