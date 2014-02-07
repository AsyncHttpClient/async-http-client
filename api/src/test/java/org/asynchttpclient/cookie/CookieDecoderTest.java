/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.cookie;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import org.asynchttpclient.cookie.Cookie;
import org.asynchttpclient.cookie.CookieDecoder;
import org.testng.annotations.Test;

public class CookieDecoderTest {

    @Test(groups = "fast")
    public void testDecodeUnquoted() {
        Cookie cookie = CookieDecoder.decode("foo=value; domain=/; path=/");
        assertNotNull(cookie);
        assertEquals(cookie.getValue(), "value");
        assertEquals(cookie.getRawValue(), "value");
        assertEquals(cookie.getDomain(), "/");
        assertEquals(cookie.getPath(), "/");
    }

    @Test(groups = "fast")
    public void testDecodeQuoted() {
        Cookie cookie = CookieDecoder.decode("ALPHA=\"VALUE1\"; Domain=docs.foo.com; Path=/accounts; Expires=Wed, 05 Feb 2014 07:37:38 GMT; Secure; HttpOnly");
        assertNotNull(cookie);
        assertEquals(cookie.getValue(), "VALUE1");
        assertEquals(cookie.getRawValue(), "\"VALUE1\"");
    }

    @Test(groups = "fast")
    public void testDecodeQuotedContainingEscapedQuote() {
        Cookie cookie = CookieDecoder.decode("ALPHA=\"VALUE1\\\"\"; Domain=docs.foo.com; Path=/accounts; Expires=Wed, 05 Feb 2014 07:37:38 GMT; Secure; HttpOnly");
        assertNotNull(cookie);
        assertEquals(cookie.getValue(), "VALUE1\"");
        assertEquals(cookie.getRawValue(), "\"VALUE1\\\"\"");
    }
}
