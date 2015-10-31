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

import org.testng.annotations.Test;

import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * See http://tools.ietf.org/html/rfc2616#section-3.3
 * 
 * @author slandelle
 */
public class DateParserTest {

    @Test(groups = "standalone")
    public void testRFC822() throws ParseException {
        Date date = DateParser.parse("Sun, 06 Nov 1994 08:49:37 GMT");
        assertNotNull(date);

        Calendar cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.setTime(date);
        assertEquals(cal.get(Calendar.DAY_OF_WEEK), Calendar.SUNDAY);
        assertEquals(cal.get(Calendar.DAY_OF_MONTH), 6);
        assertEquals(cal.get(Calendar.MONTH), Calendar.NOVEMBER);
        assertEquals(cal.get(Calendar.YEAR), 1994);
        assertEquals(cal.get(Calendar.HOUR), 8);
        assertEquals(cal.get(Calendar.MINUTE), 49);
        assertEquals(cal.get(Calendar.SECOND), 37);
    }

    @Test(groups = "standalone")
    public void testRFC822SingleDigitDayOfMonth() throws ParseException {
        Date date = DateParser.parse("Sun, 6 Nov 1994 08:49:37 GMT");
        assertNotNull(date);

        Calendar cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.setTime(date);
        assertEquals(cal.get(Calendar.DAY_OF_WEEK), Calendar.SUNDAY);
        assertEquals(cal.get(Calendar.DAY_OF_MONTH), 6);
        assertEquals(cal.get(Calendar.MONTH), Calendar.NOVEMBER);
        assertEquals(cal.get(Calendar.YEAR), 1994);
        assertEquals(cal.get(Calendar.HOUR), 8);
        assertEquals(cal.get(Calendar.MINUTE), 49);
        assertEquals(cal.get(Calendar.SECOND), 37);
    }

    @Test(groups = "standalone")
    public void testRFC822SingleDigitHour() throws ParseException {
        Date date = DateParser.parse("Sun, 6 Nov 1994 8:49:37 GMT");
        assertNotNull(date);

        Calendar cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.setTime(date);
        assertEquals(cal.get(Calendar.DAY_OF_WEEK), Calendar.SUNDAY);
        assertEquals(cal.get(Calendar.DAY_OF_MONTH), 6);
        assertEquals(cal.get(Calendar.MONTH), Calendar.NOVEMBER);
        assertEquals(cal.get(Calendar.YEAR), 1994);
        assertEquals(cal.get(Calendar.HOUR), 8);
        assertEquals(cal.get(Calendar.MINUTE), 49);
        assertEquals(cal.get(Calendar.SECOND), 37);
    }

    @Test(groups = "standalone")
    public void testRFC850() throws ParseException {
        Date date = DateParser.parse("Saturday, 06-Nov-94 08:49:37 GMT");
        assertNotNull(date);

        Calendar cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.setTime(date);
        assertEquals(cal.get(Calendar.DAY_OF_WEEK), Calendar.SATURDAY);
        assertEquals(cal.get(Calendar.DAY_OF_MONTH), 6);
        assertEquals(cal.get(Calendar.MONTH), Calendar.NOVEMBER);
        assertEquals(cal.get(Calendar.YEAR), 2094);
        assertEquals(cal.get(Calendar.HOUR), 8);
        assertEquals(cal.get(Calendar.MINUTE), 49);
        assertEquals(cal.get(Calendar.SECOND), 37);
    }

    @Test(groups = "standalone")
    public void testANSIC() throws ParseException {
        Date date = DateParser.parse("Sun Nov 6 08:49:37 1994");
        assertNotNull(date);

        Calendar cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.setTime(date);
        assertEquals(cal.get(Calendar.DAY_OF_WEEK), Calendar.SUNDAY);
        assertEquals(cal.get(Calendar.DAY_OF_MONTH), 6);
        assertEquals(cal.get(Calendar.MONTH), Calendar.NOVEMBER);
        assertEquals(cal.get(Calendar.YEAR), 1994);
        assertEquals(cal.get(Calendar.HOUR), 8);
        assertEquals(cal.get(Calendar.MINUTE), 49);
        assertEquals(cal.get(Calendar.SECOND), 37);
    }
}
