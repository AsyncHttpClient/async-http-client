/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.HttpResponseBodyPart;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NettyAsyncResponseTest {

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testCookieParseExpires() {
        // e.g. "Tue, 27 Oct 2015 12:54:24 GMT";
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

        Date date = new Date(System.currentTimeMillis() + 60000);
        final String cookieDef = String.format("efmembercheck=true; expires=%s; path=/; domain=.eclipse.org", sdf.format(date));

        HttpHeaders responseHeaders = new DefaultHttpHeaders().add(SET_COOKIE, cookieDef);
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), responseHeaders, null);

        List<Cookie> cookies = response.getCookies();
        assertEquals(1, cookies.size());

        Cookie cookie = cookies.get(0);
        assertTrue(cookie.maxAge() >= 58 && cookie.maxAge() <= 60);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testCookieParseMaxAge() {
        final String cookieDef = "efmembercheck=true; max-age=60; path=/; domain=.eclipse.org";

        HttpHeaders responseHeaders = new DefaultHttpHeaders().add(SET_COOKIE, cookieDef);
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), responseHeaders, null);
        List<Cookie> cookies = response.getCookies();
        assertEquals(1, cookies.size());

        Cookie cookie = cookies.get(0);
        assertEquals(60, cookie.maxAge());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testCookieParseWeirdExpiresValue() {
        final String cookieDef = "efmembercheck=true; expires=60; path=/; domain=.eclipse.org";
        HttpHeaders responseHeaders = new DefaultHttpHeaders().add(SET_COOKIE, cookieDef);
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), responseHeaders, null);

        List<Cookie> cookies = response.getCookies();
        assertEquals(1, cookies.size());

        Cookie cookie = cookies.get(0);
        assertEquals(Long.MIN_VALUE, cookie.maxAge());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGetResponseBodyAsByteBuffer() {
        List<HttpResponseBodyPart> bodyParts = new LinkedList<>();
        bodyParts.add(new LazyResponseBodyPart(Unpooled.wrappedBuffer("Hello ".getBytes()), false));
        bodyParts.add(new LazyResponseBodyPart(Unpooled.wrappedBuffer("World".getBytes()), true));
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null), null, bodyParts);

        ByteBuf body = response.getResponseBodyAsByteBuf();
        assertEquals("Hello World", body.toString(StandardCharsets.UTF_8));
        body.release();
    }
}
