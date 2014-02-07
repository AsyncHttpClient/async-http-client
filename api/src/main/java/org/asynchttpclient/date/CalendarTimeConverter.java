/*
 * Copyright (c) 2010-2014 Sonatype, Inc. All rights reserved.
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

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Calendar based TimeConverter.
 * Note that a Joda-Time or DateTime based implementation would be more efficient, but AHC doesn't have a dependency to JodaTime.
 * 
 * @author slandelle
 */
public class CalendarTimeConverter implements TimeConverter {

    public static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    @Override
    public long toTime(RFC2616Date dateElements) {

        Calendar calendar = new GregorianCalendar(//
                dateElements.year(), //
                dateElements.month() - 1, // beware, Calendar use months from 0 to 11
                dateElements.dayOfMonth(), //
                dateElements.hour(), //
                dateElements.minute(), //
                dateElements.second());
        calendar.setTimeZone(GMT);
        return calendar.getTimeInMillis();
    }
}
