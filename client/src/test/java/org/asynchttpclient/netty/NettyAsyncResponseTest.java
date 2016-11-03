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

import static org.testng.Assert.*;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.cookie.Cookie;
import org.testng.annotations.Test;

public class NettyAsyncResponseTest {

    @Test(groups = "standalone")
    public void testCookieParseExpires() {
        // e.g. "Tue, 27 Oct 2015 12:54:24 GMT";
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

        Date date = new Date(System.currentTimeMillis() + 60000);
        final String cookieDef = String.format("efmembercheck=true; expires=%s; path=/; domain=.eclipse.org", sdf.format(date));

        HttpResponseHeaders responseHeaders = new HttpResponseHeaders(new DefaultHttpHeaders().add(HttpHeaders.Names.SET_COOKIE, cookieDef));
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null, null), responseHeaders, null);

        List<Cookie> cookies = response.getCookies();
        assertEquals(cookies.size(), 1);

        Cookie cookie = cookies.get(0);
        assertTrue(cookie.getMaxAge() >= 58 && cookie.getMaxAge() <= 60);
    }

    @Test(groups = "standalone")
    public void testCookieParseMaxAge() {
        final String cookieDef = "efmembercheck=true; max-age=60; path=/; domain=.eclipse.org";
        
        HttpResponseHeaders responseHeaders = new HttpResponseHeaders(new DefaultHttpHeaders().add(HttpHeaders.Names.SET_COOKIE, cookieDef));
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null, null), responseHeaders, null);
        List<Cookie> cookies = response.getCookies();
        assertEquals(cookies.size(), 1);

        Cookie cookie = cookies.get(0);
        assertEquals(cookie.getMaxAge(), 60);
    }

    @Test(groups = "standalone")
    public void testCookieParseWeirdExpiresValue() {
        final String cookieDef = "efmembercheck=true; expires=60; path=/; domain=.eclipse.org";
        HttpResponseHeaders responseHeaders = new HttpResponseHeaders(new DefaultHttpHeaders().add(HttpHeaders.Names.SET_COOKIE, cookieDef));
        NettyResponse response = new NettyResponse(new NettyResponseStatus(null, null, null, null), responseHeaders, null);

        List<Cookie> cookies = response.getCookies();
        assertEquals(cookies.size(), 1);

        Cookie cookie = cookies.get(0);
        assertEquals(cookie.getMaxAge(), Long.MIN_VALUE);
    }
}
