/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpUtilsTest {

    private static String toUsAsciiString(ByteBuffer buf) {
        ByteBuf bb = Unpooled.wrappedBuffer(buf);
        try {
            return bb.toString(US_ASCII);
        } finally {
            bb.release();
        }
    }

    @Test
    public void testExtractCharsetWithoutQuotes() {
        Charset charset = HttpUtils.extractContentTypeCharsetAttribute("text/html; charset=iso-8859-1");
        assertEquals(ISO_8859_1, charset);
    }

    @Test
    public void testExtractCharsetWithSingleQuotes() {
        Charset charset = HttpUtils.extractContentTypeCharsetAttribute("text/html; charset='iso-8859-1'");
        assertEquals(ISO_8859_1, charset);
    }

    @Test
    public void testExtractCharsetWithDoubleQuotes() {
        Charset charset = HttpUtils.extractContentTypeCharsetAttribute("text/html; charset=\"iso-8859-1\"");
        assertEquals(ISO_8859_1, charset);
    }

    @Test
    public void testExtractCharsetWithDoubleQuotesAndSpaces() {
        Charset charset = HttpUtils.extractContentTypeCharsetAttribute("text/html; charset= \"iso-8859-1\" ");
        assertEquals(ISO_8859_1, charset);
    }

    @Test
    public void testExtractCharsetFallsBackToUtf8() {
        Charset charset = HttpUtils.extractContentTypeCharsetAttribute(APPLICATION_JSON.toString());
        assertNull(charset);
    }

    @Test
    public void testGetHostHeader() {
        Uri uri = Uri.create("https://stackoverflow.com/questions/1057564/pretty-git-branch-graphs");
        String hostHeader = HttpUtils.hostHeader(uri);
        assertEquals("stackoverflow.com", hostHeader, "Incorrect hostHeader returned");
    }

    @Test
    public void testDefaultFollowRedirect() {
        Request request = Dsl.get("https://shieldblaze.com").setVirtualHost("shieldblaze.com").build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
        boolean followRedirect = HttpUtils.followRedirect(config, request);
        assertFalse(followRedirect, "Default value of redirect should be false");
    }

    @Test
    public void testGetFollowRedirectInRequest() {
        Request request = Dsl.get("https://stackoverflow.com/questions/1057564").setFollowRedirect(true).build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
        boolean followRedirect = HttpUtils.followRedirect(config, request);
        assertTrue(followRedirect, "Follow redirect must be true as set in the request");
    }

    @Test
    public void testGetFollowRedirectInConfig() {
        Request request = Dsl.get("https://stackoverflow.com/questions/1057564").build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        boolean followRedirect = HttpUtils.followRedirect(config, request);
        assertTrue(followRedirect, "Follow redirect should be equal to value specified in config when not specified in request");
    }

    @Test
    public void testGetFollowRedirectPriorityGivenToRequest() {
        Request request = Dsl.get("https://stackoverflow.com/questions/1057564").setFollowRedirect(false).build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        boolean followRedirect = HttpUtils.followRedirect(config, request);
        assertFalse(followRedirect, "Follow redirect value set in request should be given priority");
    }

    private static void formUrlEncoding(Charset charset) throws Exception {
        String key = "key";
        String value = "中文";
        List<Param> params = new ArrayList<>();
        params.add(new Param(key, value));
        ByteBuffer ahcBytes = HttpUtils.urlEncodeFormParams(params, charset);
        String ahcString = toUsAsciiString(ahcBytes);
        String jdkString = key + '=' + URLEncoder.encode(value, charset);
        assertEquals(ahcString, jdkString);
    }

    @Test
    public void formUrlEncodingShouldSupportUtf8Charset() throws Exception {
        formUrlEncoding(UTF_8);
    }

    @Test
    public void formUrlEncodingShouldSupportNonUtf8Charset() throws Exception {
        formUrlEncoding(Charset.forName("GBK"));
    }

    @Test
    public void computeOriginForPlainUriWithImplicitPort() {
        assertEquals("http://foo.com", HttpUtils.originHeader(Uri.create("ws://foo.com/bar")));
    }

    @Test
    public void computeOriginForPlainUriWithDefaultPort() {
        assertEquals("http://foo.com", HttpUtils.originHeader(Uri.create("ws://foo.com:80/bar")));
    }

    @Test
    public void computeOriginForPlainUriWithNonDefaultPort() {
        assertEquals("http://foo.com:81", HttpUtils.originHeader(Uri.create("ws://foo.com:81/bar")));
    }

    @Test
    public void computeOriginForSecuredUriWithImplicitPort() {
        assertEquals("https://foo.com", HttpUtils.originHeader(Uri.create("wss://foo.com/bar")));
    }

    @Test
    public void computeOriginForSecuredUriWithDefaultPort() {
        assertEquals("https://foo.com", HttpUtils.originHeader(Uri.create("wss://foo.com:443/bar")));
    }

    @Test
    public void computeOriginForSecuredUriWithNonDefaultPort() {
        assertEquals("https://foo.com:444", HttpUtils.originHeader(Uri.create("wss://foo.com:444/bar")));
    }
}
