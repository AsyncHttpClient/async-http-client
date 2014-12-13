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
package com.ning.http.client.cookie;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A parser for <a href="http://tools.ietf.org/html/rfc2616#section-3.3">RFC2616
 * Date format</a>.
 * 
 * @author slandelle
 */
@SuppressWarnings("serial")
public class RFC2616DateParser extends SimpleDateFormat {

    private final SimpleDateFormat format1 = new RFC2616DateParserObsolete1();
    private final SimpleDateFormat format2 = new RFC2616DateParserObsolete2();

    private static final ThreadLocal<RFC2616DateParser> DATE_FORMAT_HOLDER = new ThreadLocal<RFC2616DateParser>() {
        @Override
        protected RFC2616DateParser initialValue() {
            return new RFC2616DateParser();
        }
    };

    public static RFC2616DateParser get() {
        return DATE_FORMAT_HOLDER.get();
    }

    /**
     * Standard date format<p>
     * Sun, 06 Nov 1994 08:49:37 GMT -> E, d MMM yyyy HH:mm:ss z
     */
    private RFC2616DateParser() {
        super("E, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
        setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    @Override
    public Date parse(String text, ParsePosition pos) {
        Date date = super.parse(text, pos);
        if (date == null) {
            date = format1.parse(text, pos);
        }
        if (date == null) {
            date = format2.parse(text, pos);
        }
        return date;
    }

    /**
     * First obsolete format<p>
     * Sunday, 06-Nov-94 08:49:37 GMT -> E, d-MMM-y HH:mm:ss z
     */
    private static final class RFC2616DateParserObsolete1 extends SimpleDateFormat {
        private static final long serialVersionUID = -3178072504225114298L;

        RFC2616DateParserObsolete1() {
            super("E, dd-MMM-yy HH:mm:ss z", Locale.ENGLISH);
            setTimeZone(TimeZone.getTimeZone("GMT"));
        }
    }

    /**
     * Second obsolete format
     * <p>
     * Sun Nov 6 08:49:37 1994 -> EEE, MMM d HH:mm:ss yyyy
     */
    private static final class RFC2616DateParserObsolete2 extends SimpleDateFormat {
        private static final long serialVersionUID = 3010674519968303714L;

        RFC2616DateParserObsolete2() {
            super("E MMM d HH:mm:ss yyyy", Locale.ENGLISH);
            setTimeZone(TimeZone.getTimeZone("GMT"));
        }
    }
}
