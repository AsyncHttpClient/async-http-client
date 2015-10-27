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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * A parser for <a href="http://tools.ietf.org/html/rfc2616#section-3.3">RFC2616
 * Date format</a>.
 * 
 * @author slandelle
 */
public final class DateParser {

    private static final DateTimeFormatter PROPER_FORMAT_RFC822 = DateTimeFormatter.RFC_1123_DATE_TIME;
    // give up on pre 2000 dates
    private static final DateTimeFormatter OBSOLETE_FORMAT1_RFC850 = DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss z", Locale.ENGLISH);
    private static final DateTimeFormatter OBSOLETE_FORMAT2_ANSIC = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH);

    private static Date parseZonedDateTimeSilent(String text, DateTimeFormatter formatter) {
        try {
            return Date.from(ZonedDateTime.parse(text, formatter).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    private static Date parseDateTimeSilent(String text, DateTimeFormatter formatter) {
        try {
            return Date.from(LocalDateTime.parse(text, formatter).toInstant(ZoneOffset.UTC));
        } catch (Exception e) {
            return null;
        }
    }

    public static Date parse(String text) {
        Date date = parseZonedDateTimeSilent(text, PROPER_FORMAT_RFC822);
        if (date == null) {
            date = parseZonedDateTimeSilent(text, OBSOLETE_FORMAT1_RFC850);
        }
        if (date == null) {
            date = parseDateTimeSilent(text, OBSOLETE_FORMAT2_ANSIC);
        }
        return date;
    }
}
