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
package org.asynchttpclient.date;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import org.testng.annotations.Test;

/**
 * See http://tools.ietf.org/html/rfc2616#section-3.3
 * 
 * @author slandelle
 */
public class RFC2616DateParserTest {

    @Test(groups = "fast")
    public void testRFC822() {
        RFC2616Date date = new RFC2616DateParser("Sun, 06 Nov 1994 08:49:37 GMT").parse();
        assertNotNull(date);
        assertEquals(date.dayOfMonth(), 6);
        assertEquals(date.month(), 11);
        assertEquals(date.year(), 1994);
        assertEquals(date.hour(), 8);
        assertEquals(date.minute(), 49);
        assertEquals(date.second(), 37);
    }

    @Test(groups = "fast")
    public void testRFC822SingleDigitDayOfMonth() {
        RFC2616Date date = new RFC2616DateParser("Sun, 6 Nov 1994 08:49:37 GMT").parse();
        assertNotNull(date);
        assertEquals(date.dayOfMonth(), 6);
    }

    @Test(groups = "fast")
    public void testRFC822TwoDigitsYear() {
        RFC2616Date date = new RFC2616DateParser("Sun, 6 Nov 94 08:49:37 GMT").parse();
        assertNotNull(date);
        assertEquals(date.year(), 1994);
    }

    @Test(groups = "fast")
    public void testRFC822SingleDigitHour() {
        RFC2616Date date = new RFC2616DateParser("Sun, 6 Nov 1994 8:49:37 GMT").parse();
        assertNotNull(date);
        assertEquals(date.hour(), 8);
    }

    @Test(groups = "fast")
    public void testRFC822SingleDigitMinute() {
        RFC2616Date date = new RFC2616DateParser("Sun, 6 Nov 1994 08:9:37 GMT").parse();
        assertNotNull(date);
        assertEquals(date.minute(), 9);
    }

    @Test(groups = "fast")
    public void testRFC822SingleDigitSecond() {
        RFC2616Date date = new RFC2616DateParser("Sun, 6 Nov 1994 08:49:7 GMT").parse();
        assertNotNull(date);
        assertEquals(date.second(), 7);
    }

    @Test(groups = "fast")
    public void testRFC6265() {
        RFC2616Date date = new RFC2616DateParser("Sun, 06 Nov 1994 08:49:37").parse();
        assertNotNull(date);
        assertEquals(date.dayOfMonth(), 6);
        assertEquals(date.month(), 11);
        assertEquals(date.year(), 1994);
        assertEquals(date.hour(), 8);
        assertEquals(date.minute(), 49);
        assertEquals(date.second(), 37);
    }

    @Test(groups = "fast")
    public void testRFC850() {
        RFC2616Date date = new RFC2616DateParser("Sunday, 06-Nov-94 08:49:37 GMT").parse();
        assertNotNull(date);
        assertEquals(date.dayOfMonth(), 6);
        assertEquals(date.month(), 11);
        assertEquals(date.year(), 1994);
        assertEquals(date.hour(), 8);
        assertEquals(date.minute(), 49);
        assertEquals(date.second(), 37);
    }

    @Test(groups = "fast")
    public void testANSIC() {
        RFC2616Date date = new RFC2616DateParser("Sun Nov  6 08:49:37 1994").parse();
        assertNotNull(date);
        assertEquals(date.dayOfMonth(), 6);
        assertEquals(date.month(), 11);
        assertEquals(date.year(), 1994);
        assertEquals(date.hour(), 8);
        assertEquals(date.minute(), 49);
        assertEquals(date.second(), 37);
    }
}
