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

import static io.netty.util.internal.ObjectUtil.checkNotNull;

import io.netty.util.concurrent.FastThreadLocal;

import java.util.BitSet;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * A formatter for HTTP header dates, such as "Expires" and "Date" headers, or "expires" field in "Set-Cookie".
 *
 * On the parsing side, it honors RFC6265 (so it supports RFC1123).
 * Note that:
 * <ul>
 *     <li>Day of week is ignored and not validated</li>
 *     <li>Timezone is ignored, as RFC6265 assumes UTC</li>
 * </ul>
 * If you're looking for a date format that validates day of week, or supports other timezones, consider using
 * java.util.DateTimeFormatter.RFC_1123_DATE_TIME.
 *
 * On the formatting side, it uses RFC1123 format.
 *
 * @see <a href="https://tools.ietf.org/html/rfc6265#section-5.1.1">RFC6265</a> for the parsing side
 * @see <a href="https://tools.ietf.org/html/rfc1123#page-55">RFC1123</a> for the encoding side.
 */
public final class HttpHeaderDateFormatter {

    private static final BitSet DELIMITERS = new BitSet();
    static {
        DELIMITERS.set(0x09);
        for (char c = 0x20; c <= 0x2F; c++) {
            DELIMITERS.set(c);
        }
        for (char c = 0x3B; c <= 0x40; c++) {
            DELIMITERS.set(c);
        }
        for (char c = 0x5B; c <= 0x60; c++) {
            DELIMITERS.set(c);
        }
        for (char c = 0x7B; c <= 0x7E; c++) {
            DELIMITERS.set(c);
        }
    }

    private static final String[] DAY_OF_WEEK_TO_SHORT_NAME =
            new String[]{"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    private static final String[] CALENDAR_MONTH_TO_SHORT_NAME =
            new String[]{"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private static final FastThreadLocal<HttpHeaderDateFormatter> INSTANCES =
            new FastThreadLocal<HttpHeaderDateFormatter>() {
                @Override
                protected HttpHeaderDateFormatter initialValue() {
                    return new HttpHeaderDateFormatter();
                }
            };

    /**
     * Parse some text into a {@link Date}, according to RFC6265
     * @param txt text to parse
     * @return a {@link Date}, or null if text couldn't be parsed
     */
    public static Date parse(CharSequence txt) {
        return parse(checkNotNull(txt, "txt"), 0, txt.length());
    }

    /**
     * Parse some text into a {@link Date}, according to RFC6265
     * @param txt text to parse
     * @param start the start index inside <code>txt</code>
     * @param end the end index inside <code>txt</code>
     * @return a {@link Date}, or null if text couldn't be parsed
     */
    public static Date parse(CharSequence txt, int start, int end) {
        return formatter().parse0(checkNotNull(txt, "txt"), start, end);
    }

    /**
     * Format a {@link Date} into RFC1123 format
     * @param date the date to format
     * @return a RFC1123 string
     */
    public static String format(Date date) {
        return formatter().format0(checkNotNull(date, "date"));
    }

    /**
     * Append a {@link Date} to a {@link StringBuilder} into RFC1123 format
     * @param date the date to format
     * @param sb the StringBuilder
     * @return the same StringBuilder
     */
    public static StringBuilder append(Date date, StringBuilder sb) {
        return formatter().append0(checkNotNull(date, "date"), checkNotNull(sb, "sb"));
    }

    private static HttpHeaderDateFormatter formatter() {
        HttpHeaderDateFormatter formatter = INSTANCES.get();
        formatter.reset();
        return formatter;
    }

    // delimiter = %x09 / %x20-2F / %x3B-40 / %x5B-60 / %x7B-7E
    private static boolean isDelim(char c) {
        return DELIMITERS.get(c);
    }

    private static boolean isDigit(char c) {
        return c >= 0x30 && c <= 0x39;
    }

    private static int getNumericalValue(char c) {
        return (int) c - 48;
    }

    private final GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    private final StringBuilder sb = new StringBuilder(29); // Sun, 27 Nov 2016 19:37:15 GMT
    private boolean timeFound;
    private int hours;
    private int minutes;
    private int seconds;
    private boolean dayOfMonthFound;
    private int dayOfMonth;
    private boolean monthFound;
    private int month;
    private boolean yearFound;
    private int year;

    private HttpHeaderDateFormatter() {
        reset();
    }

    public void reset() {
        timeFound = false;
        hours = -1;
        minutes = -1;
        seconds = -1;
        dayOfMonthFound = false;
        dayOfMonth = -1;
        monthFound = false;
        month = -1;
        yearFound = false;
        year = -1;
        cal.set(Calendar.MILLISECOND, 0);
        sb.setLength(0);
    }

    private boolean tryParseTime(CharSequence txt, int tokenStart, int tokenEnd) {
        int len = tokenEnd - tokenStart;

        // d:m:yy to dd:mm:yyyy
        if (len < 6 || len > 10) {
            return false;
        }

        int localHours = -1;
        int localMinutes = -1;
        int localSeconds = -1;
        int currentPartNumber = 0;
        int currentPartValue = 0;

        for (int i = tokenStart; i < tokenEnd; i++) {
            char c = txt.charAt(i);
            if (isDigit(c)) {
                currentPartValue = currentPartValue * 10 + getNumericalValue(c);
            } else if (c == ':') {
                if (currentPartValue == 0) {
                    // invalid :: (nothing in between)
                    return false;

                } else if (currentPartNumber == 0) {
                    // flushing hours
                    localHours = currentPartValue;
                    currentPartValue = 0;
                    currentPartNumber++;

                } else if (currentPartNumber == 1) {
                    // flushing minutes
                    localMinutes = currentPartValue;
                    currentPartValue = 0;
                    currentPartNumber++;

                } else if (currentPartNumber == 2) {
                    // invalid, too many :
                    return false;
                }
            } else {
                // invalid char
                return false;
            }
        }

        if (currentPartValue > 0) {
            // pending seconds
            localSeconds = currentPartValue;
        }

        if (localHours >= 0 && localMinutes >= 0 && localSeconds >= 0) {
            hours = localHours;
            minutes = localMinutes;
            seconds = localSeconds;
            return true;
        }

        return false;
    }

    private boolean tryParseDayOfMonth(CharSequence txt, int tokenStart, int tokenEnd) {
        int len = tokenEnd - tokenStart;

        if (len != 1 && len != 2) {
            return false;
        }

        int localDayOfMonth = 0;
        for (int i = tokenStart; i < tokenEnd; i++) {
            char c = txt.charAt(i);
            if (isDigit(c)) {
                localDayOfMonth = localDayOfMonth * 10 + getNumericalValue(c);
            } else {
                // invalid
                return false;
            }
        }

        if (localDayOfMonth > 0) {
            dayOfMonth = localDayOfMonth;
            return true;
        }

        return false;
    }

    private boolean tryParseMonth(CharSequence txt, int tokenStart, int tokenEnd) {
        int len = tokenEnd - tokenStart;

        if (len == 3) {
            String tokenString = txt.subSequence(tokenStart, tokenEnd).toString();

            if (tokenString.equalsIgnoreCase("Jan")) {
                month = Calendar.JANUARY;
            } else if (tokenString.equalsIgnoreCase("Feb")) {
                month = Calendar.FEBRUARY;
            } else if (tokenString.equalsIgnoreCase("Mar")) {
                month = Calendar.MARCH;
            } else if (tokenString.equalsIgnoreCase("Apr")) {
                month = Calendar.APRIL;
            } else if (tokenString.equalsIgnoreCase("May")) {
                month = Calendar.MAY;
            } else if (tokenString.equalsIgnoreCase("Jun")) {
                month = Calendar.JUNE;
            } else if (tokenString.equalsIgnoreCase("Jul")) {
                month = Calendar.JULY;
            } else if (tokenString.equalsIgnoreCase("Aug")) {
                month = Calendar.AUGUST;
            } else if (tokenString.equalsIgnoreCase("Sep")) {
                month = Calendar.SEPTEMBER;
            } else if (tokenString.equalsIgnoreCase("Oct")) {
                month = Calendar.OCTOBER;
            } else if (tokenString.equalsIgnoreCase("Nov")) {
                month = Calendar.NOVEMBER;
            } else if (tokenString.equalsIgnoreCase("Dec")) {
                month = Calendar.DECEMBER;
            }
        }

        return month != -1;
    }

    private boolean tryParseYear(CharSequence txt, int tokenStart, int tokenEnd) {
        int len = tokenEnd - tokenStart;

        if (len != 2 && len != 4) {
            return false;
        }

        int localYear = 0;
        for (int i = tokenStart; i < tokenEnd; i++) {
            char c = txt.charAt(i);
            if (isDigit(c)) {
                localYear = localYear * 10 + getNumericalValue(c);
            } else {
                // invalid
                return false;
            }
        }

        if (localYear > 0) {
            year = localYear;
            return true;
        }

        return false;
    }

    private void parseToken(CharSequence txt, int tokenStart, int tokenEnd) {
        if (!timeFound) {
            timeFound = tryParseTime(txt, tokenStart, tokenEnd);
            if (timeFound) {
                return;
            }
        }

        if (!dayOfMonthFound) {
            dayOfMonthFound = tryParseDayOfMonth(txt, tokenStart, tokenEnd);
            if (dayOfMonthFound) {
                return;
            }
        }

        if (!monthFound) {
            monthFound = tryParseMonth(txt, tokenStart, tokenEnd);
            if (monthFound) {
                return;
            }
        }

        if (!yearFound) {
            yearFound = tryParseYear(txt, tokenStart, tokenEnd);
        }
    }

    private Date parse0(CharSequence txt, int start, int end) {
        int tokenStart = -1;

        for (int i = start; i < end; i++) {
            char c = txt.charAt(i);

            if (isDelim(c)) {
                if (tokenStart != -1) {
                    // terminate token
                    parseToken(txt, tokenStart, i);
                    tokenStart = -1;
                }
            } else {
                if (tokenStart == -1) {
                    // start new token
                    tokenStart = i;
                }
            }
        }

        if (tokenStart != -1) {
            // terminate trailing token
            parseToken(txt, tokenStart, txt.length());
        }

        if (!timeFound || !dayOfMonthFound || !monthFound || !yearFound) {
            // missing field
            return null;
        }

         if (dayOfMonth < 1 || dayOfMonth > 31 || hours > 23 || minutes > 59 || seconds > 59) {
            // invalid values
            return null;
        }

        if (year >= 70 && year <= 99) {
            year += 1900;
        } else if (year >= 0 && year < 70) {
            year += 2000;
        } else if (year < 1601) {
            // invalid value
            return null;
        }

        cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        cal.set(Calendar.MONTH, month);
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.HOUR_OF_DAY, hours);
        cal.set(Calendar.MINUTE, minutes);
        cal.set(Calendar.SECOND, seconds);
        return cal.getTime();
    }

    private String format0(Date date) {
        append0(date, sb);
        return sb.toString();
    }

    private StringBuilder append0(Date date, StringBuilder sb) {
        cal.setTime(date);

        sb.append(DAY_OF_WEEK_TO_SHORT_NAME[cal.get(Calendar.DAY_OF_WEEK) - 1]).append(", ");
        sb.append(cal.get(Calendar.DAY_OF_MONTH)).append(' ');
        sb.append(CALENDAR_MONTH_TO_SHORT_NAME[cal.get(Calendar.MONTH)]).append(' ');
        sb.append(cal.get(Calendar.YEAR)).append(' ');
        appendZeroLeftPadded(cal.get(Calendar.HOUR_OF_DAY), sb).append(':');
        appendZeroLeftPadded(cal.get(Calendar.MINUTE), sb).append(':');
        return appendZeroLeftPadded(cal.get(Calendar.SECOND), sb).append(" GMT");
    }

    private static StringBuilder appendZeroLeftPadded(int value, StringBuilder sb) {
        if (value < 10) {
            sb.append('0');
        }
        return sb.append(value);
    }
}
