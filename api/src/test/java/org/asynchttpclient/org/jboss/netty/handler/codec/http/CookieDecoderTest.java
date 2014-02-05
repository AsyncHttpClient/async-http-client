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
package org.asynchttpclient.org.jboss.netty.handler.codec.http;

import static org.testng.Assert.assertEquals;

import java.util.List;

import org.asynchttpclient.Cookie;
import org.testng.annotations.Test;

public class CookieDecoderTest {

    @Test(groups = "fast")
    public void testDecodeUnquoted() {
        List<Cookie> cookies = CookieDecoder.decode("foo=value; domain=/; path=/");
        assertEquals(cookies.size(), 1);

        Cookie first = cookies.get(0);
        assertEquals(first.getValue(), "value");
        assertEquals(first.getRawValue(), "value");
        assertEquals(first.getDomain(), "/");
        assertEquals(first.getPath(), "/");
    }

    @Test(groups = "fast")
    public void testDecodeQuoted() {
        List<Cookie> cookies = CookieDecoder.decode("ALPHA=\"VALUE1\"; Domain=docs.foo.com; Path=/accounts; Expires=Wed, 05 Feb 2014 07:37:38 GMT; Secure; HttpOnly");
        assertEquals(cookies.size(), 1);

        Cookie first = cookies.get(0);
        assertEquals(first.getValue(), "VALUE1");
        assertEquals(first.getRawValue(), "\"VALUE1\"");
    }

    @Test(groups = "fast")
    public void testDecodeQuotedContainingEscapedQuote() {
        List<Cookie> cookies = CookieDecoder.decode("ALPHA=\"VALUE1\\\"\"; Domain=docs.foo.com; Path=/accounts; Expires=Wed, 05 Feb 2014 07:37:38 GMT; Secure; HttpOnly");
        assertEquals(cookies.size(), 1);

        Cookie first = cookies.get(0);
        assertEquals(first.getValue(), "VALUE1\"");
        assertEquals(first.getRawValue(), "\"VALUE1\\\"\"");
    }
}
