/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
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

import java.util.Date;

import org.testng.annotations.Test;

public class HttpHeaderDateFormatterTest {
    /**
     * This date is set at "06 Nov 1994 08:49:37 GMT", from
     * <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html">examples in RFC documentation</a>
     */
    private static final Date DATE = new Date(784111777000L);

    @Test
    public void testParseWithSingleDigitDay() {
        assertEquals(DATE, HttpHeaderDateFormatter.parse("Sun, 6 Nov 1994 08:49:37 GMT"));
    }

    @Test
    public void testParseWithDoubleDigitDay() {
        assertEquals(DATE, HttpHeaderDateFormatter.parse("Sun, 06 Nov 1994 08:49:37 GMT"));
    }

    @Test
    public void testParseWithDashSeparatorSingleDigitDay() {
        assertEquals(DATE, HttpHeaderDateFormatter.parse("Sunday, 06-Nov-94 08:49:37 GMT"));
    }

    @Test
    public void testParseWithSingleDoubleDigitDay() {
        assertEquals(DATE, HttpHeaderDateFormatter.parse("Sunday, 6-Nov-94 08:49:37 GMT"));
    }

    @Test
    public void testParseWithoutGMT() {
        assertEquals(DATE, HttpHeaderDateFormatter.parse("Sun Nov 6 08:49:37 1994"));
    }

    @Test
    public void testParseWithFunkyTimezone() {
        assertEquals(DATE, HttpHeaderDateFormatter.parse("Sun Nov 6 08:49:37 1994 -0000"));
    }

    @Test
    public void testFormat() {
        assertEquals("Sun, 6 Nov 1994 08:49:37 GMT", HttpHeaderDateFormatter.format(DATE));
    }
}
