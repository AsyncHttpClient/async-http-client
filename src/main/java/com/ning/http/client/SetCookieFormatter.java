/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package com.ning.http.client;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * SetCookieFormatter formats the Set-Cookie header value as specified by
 * http://www.w3.org/Protocols/rfc2109/rfc2109.txt but using the Expires field
 * as specified in section 10.1.2.
 */
public class SetCookieFormatter {
    /*
     * This should print out the date as Wdy, 20-Aug-08 12:00:00 GMT
     * See http://www.w3.org/Protocols/rfc2109/rfc2109.txt, sec 10.1.2
     */
    private final static String NETSCAPE_COOKIE_DATE_FORMAT = "EEE, dd-MMM-yy HH:MM:ss z";

    public static String toNetscapeCookieFormat(Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat(NETSCAPE_COOKIE_DATE_FORMAT);
        formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
        return formatter.format(date);
    }

    public static Date fromNetscapeCookieFormat(String dateString) throws ParseException {
        return new SimpleDateFormat(NETSCAPE_COOKIE_DATE_FORMAT).parse(dateString);
    }

    public static String parse(Cookie cookie) {
        StringBuilder buf = new StringBuilder();
        // Write <name>=<value>
        buf.append(cookie.getName()).append("=").append(cookie.getValue()).append(";");
        // Write optional Domain=<domain> if available
        if (cookie.getDomain() != null) {
            buf.append("Domain=").append(cookie.getDomain()).append(";");
        }
        // Write maxAge as an Expires header
        // Note that if maxAge is unspecified, the cookie will expire immediately, as maxAge will default to 0;
        long expiresTimestamp = System.currentTimeMillis() + ((long) cookie.getMaxAge() * 1000L);
        buf.append("Expires=").append(toNetscapeCookieFormat(new Date(expiresTimestamp))).append(";");
        // Write optional Path=<path> if available
        if (cookie.getPath() != null) {
            buf.append("Path=").append(cookie.getPath()).append(";");
        }
        // Write optional Secure attribute (no value) if available
        if (cookie.isSecure()) {
            buf.append("Secure");
        }
        return buf.toString();
    }
}
