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
package com.ning.http.client.async;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

import com.ning.http.client.Param;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class RequestBuilderTest {

    private final static String SAFE_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890-_~.";
    private final static String HEX_CHARS = "0123456789ABCDEF";

    @Test(groups = {"standalone", "default_provider"})
    public void testEncodesQueryParameters() throws UnsupportedEncodingException {
        String[] values = new String[]{
                "abcdefghijklmnopqrstuvwxyz",
                "ABCDEFGHIJKQLMNOPQRSTUVWXYZ",
                "1234567890", "1234567890",
                "`~!@#$%^&*()", "`~!@#$%^&*()",
                "_+-=,.<>/?", "_+-=,.<>/?",
                ";:'\"[]{}\\| ", ";:'\"[]{}\\| "
        };

        /* as per RFC-5849 (Oauth), and RFC-3986 (percent encoding) we MUST
         * encode everything except for "safe" characters; and nothing but them.
         * Safe includes ascii letters (upper and lower case), digits (0 - 9)
         * and FOUR special characters: hyphen ('-'), underscore ('_'),
         * tilde ('~') and period ('.')). Everything else must be percent-encoded,
         * byte-by-byte, using UTF-8 encoding (meaning three-byte Unicode/UTF-8
         * code points are encoded as three three-letter percent-encode entities).
         */
        for (String value : values) {
            RequestBuilder builder = new RequestBuilder("GET").
                    setUrl("http://example.com/").
                    addQueryParam("name", value);

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

    @Test(groups = {"standalone", "default_provider"})
    public void testChaining() throws IOException, ExecutionException, InterruptedException {
        Request request = new RequestBuilder("GET")
                .setUrl("http://foo.com")
                .addQueryParam("x", "value")
                .build();

        Request request2 = new RequestBuilder(request).build();

        assertEquals(request2.getUri(), request.getUri());
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testParsesQueryParams() throws IOException, ExecutionException, InterruptedException {
        Request request = new RequestBuilder("GET")
                .setUrl("http://foo.com/?param1=value1")
                .addQueryParam("param2", "value2")
                .build();

        assertEquals(request.getUrl(), "http://foo.com/?param1=value1&param2=value2");
        List<Param> params = request.getQueryParams();
        assertEquals(params.size(), 2);
        assertEquals(params.get(0), new Param("param1", "value1"));
        assertEquals(params.get(1), new Param("param2", "value2"));
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testUserProvidedRequestMethod() {
        Request req = new RequestBuilder("ABC").setUrl("http://foo.com").build();
        assertEquals(req.getMethod(), "ABC");
        assertEquals(req.getUrl(), "http://foo.com");
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testPercentageEncodedUserInfo() {
        final Request req = new RequestBuilder("GET").setUrl("http://hello:wor%20ld@foo.com").build();
        assertEquals(req.getMethod(), "GET");
        assertEquals(req.getUrl(), "http://hello:wor%20ld@foo.com");
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testContentTypeCharsetToBodyEncoding() {
        final Request req = new RequestBuilder("GET").setHeader("Content-Type", "application/json; charset=XXXX").build();
        assertEquals(req.getBodyEncoding(), "XXXX");
        final Request req2 = new RequestBuilder("GET").setHeader("Content-Type", "application/json; charset=\"XXXX\"").build();
        assertEquals(req2.getBodyEncoding(), "XXXX");
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testAddQueryParameter() throws UnsupportedEncodingException {
        RequestBuilder rb = new RequestBuilder("GET", false).setUrl("http://example.com/path")
                .addQueryParam("a", "1?&")
                .addQueryParam("b", "+ =");
        Request request = rb.build();
        assertEquals(request.getUrl(), "http://example.com/path?a=1%3F%26&b=%2B%20%3D");
    }

    @Test(groups = {"standalone", "default_provider"})
    public void testRawUrlQuery() throws UnsupportedEncodingException, URISyntaxException {
        String preEncodedUrl = "http://example.com/space%20mirror.php?%3Bteile";
        RequestBuilder rb = new RequestBuilder("GET", true).setUrl(preEncodedUrl);
        Request request = rb.build();
        assertEquals(request.getUrl(), preEncodedUrl);
        assertEquals(request.getUri().toJavaNetURI().toString(), preEncodedUrl);
    }
}
