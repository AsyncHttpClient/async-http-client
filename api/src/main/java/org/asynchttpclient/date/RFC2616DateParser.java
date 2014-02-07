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

import org.asynchttpclient.date.RFC2616Date.Builder;

/**
 * A parser for <a href="http://tools.ietf.org/html/rfc2616#section-3.3">RFC2616
 * Date format</a>.
 * 
 * @author slandelle
 */
public class RFC2616DateParser {

    private final String string;
    private final int offset;
    private final int length;

    /**
     * @param string a string that will be fully parsed
     */
    public RFC2616DateParser(String string) {
        this(string, 0, string.length());
    }

    /**
     * @param string the string to be parsed
     * @param offset the offset where to start parsing
     * @param length the number of chars to parse
     */
    public RFC2616DateParser(String string, int offset, int length) {

        if (string.length() + offset < length)
            throw new IllegalArgumentException("String length doesn't match offset and length");

        this.string = string;
        this.offset = offset;
        this.length = length;
    }

    private static class Tokens {
        public final int[] starts;
        public final int[] ends;
        public final int length;

        public Tokens(int[] starts, int[] ends, int length) {
            this.starts = starts;
            this.ends = ends;
            this.length = length;
        }
    }

    private Tokens tokenize() {

        int[] starts = new int[8];
        int[] ends = new int[8];
        boolean inToken = false;
        int tokenCount = 0;

        int end = offset + length;
        for (int i = offset; i < end; i++) {

            char c = string.charAt(i);
            if (c == ' ' || c == ',' || c == '-' || c == ':') {
                if (inToken) {
                    ends[tokenCount++] = i;
                    inToken = false;
                }
            } else if (!inToken) {
                starts[tokenCount] = i;
                inToken = true;
            }
        }

        // finish lastToken
        if (inToken = true)
            ends[tokenCount++] = end;

        return new Tokens(starts, ends, tokenCount);
    }

    /**
     * @param validate if validation is to be enabled of non-critical elements,
     *            such as day of week and timezone
     * @return null is the string is not a valid RFC2616 date
     */
    public RFC2616Date parse() {

        Tokens tokens = tokenize();

        if (tokens.length != 7 && tokens.length != 8)
            return null;

        // 1st token is ignored: ignore day of week
        // 8th token is ignored: supposed to always be GMT

        if (isDigit(string.charAt(tokens.starts[1])))
            return buildDate(tokens);
        else
            return buildANSICDate(tokens);
    }

    private RFC2616Date buildDate(Tokens tokens) {

        // Sun, 06 Nov 1994 08:49:37 GMT

        Builder dateBuilder = new Builder();

        if (isValidDayOfMonth(tokens.starts[1], tokens.ends[1], dateBuilder) && //
                isValidMonth(tokens.starts[2], tokens.ends[2], dateBuilder) && //
                isValidYear(tokens.starts[3], tokens.ends[3], dateBuilder) && //
                isValidHour(tokens.starts[4], tokens.ends[4], dateBuilder) && //
                isValidMinuteSecond(tokens.starts[5], tokens.ends[5], dateBuilder, true) && //
                isValidMinuteSecond(tokens.starts[6], tokens.ends[6], dateBuilder, false)) {
            return dateBuilder.build();
        }

        return null;
    }

    private RFC2616Date buildANSICDate(Tokens tokens) {

        // Sun Nov 6 08:49:37 1994

        Builder dateBuilder = new Builder();

        if (isValidMonth(tokens.starts[1], tokens.ends[1], dateBuilder) && //
                isValidDayOfMonth(tokens.starts[2], tokens.ends[2], dateBuilder) && //
                isValidHour(tokens.starts[3], tokens.ends[3], dateBuilder) && //
                isValidMinuteSecond(tokens.starts[4], tokens.ends[4], dateBuilder, true) && //
                isValidMinuteSecond(tokens.starts[5], tokens.ends[5], dateBuilder, false) && //
                isValidYear(tokens.starts[6], tokens.ends[6], dateBuilder)) {
            return dateBuilder.build();
        }

        return null;
    }

    private boolean isValid1DigitDayOfMonth(char c0, Builder dateBuilder) {
        if (isDigit(c0)) {
            dateBuilder.setDayOfMonth(getNumericValue(c0));
            return true;
        }
        return false;
    }

    private boolean isValid2DigitsDayOfMonth(char c0, char c1, Builder dateBuilder) {
        if (isDigit(c0) && isDigit(c1)) {
            int i0 = getNumericValue(c0);
            int i1 = getNumericValue(c1);
            int day = i0 * 10 + i1;
            if (day <= 31) {
                dateBuilder.setDayOfMonth(day);
                return true;
            }
        }
        return false;
    }

    private boolean isValidDayOfMonth(int start, int end, Builder dateBuilder) {

        int tokenLength = end - start;

        if (tokenLength == 1) {
            char c0 = string.charAt(start);
            return isValid1DigitDayOfMonth(c0, dateBuilder);

        } else if (tokenLength == 2) {
            char c0 = string.charAt(start);
            char c1 = string.charAt(start + 1);
            return isValid2DigitsDayOfMonth(c0, c1, dateBuilder);
        }
        return false;
    }

    private boolean isValidJanuaryJuneJuly(char c0, char c1, char c2, Builder dateBuilder) {
        if (c0 == 'J' || c0 == 'j')
            if (c1 == 'a' || c1 == 'A') {
                if (c2 == 'n' || c2 == 'N') {
                    dateBuilder.setJanuary();
                    return true;
                }
            } else if (c1 == 'u' || c1 == 'U') {
                if (c2 == 'n' || c2 == 'N') {
                    dateBuilder.setJune();
                    return true;
                } else if (c2 == 'l' || c2 == 'L') {
                    dateBuilder.setJuly();
                    return true;
                }
            }
        return false;
    }

    private boolean isValidFebruary(char c0, char c1, char c2, Builder dateBuilder) {
        if ((c0 == 'F' || c0 == 'f') && (c1 == 'e' || c1 == 'E') && (c2 == 'b' || c2 == 'B')) {
            dateBuilder.setFebruary();
            return true;
        }
        return false;
    }

    private boolean isValidMarchMay(char c0, char c1, char c2, Builder dateBuilder) {
        if ((c0 == 'M' || c0 == 'm') && (c1 == 'a' || c1 == 'A')) {
            if (c2 == 'r' || c2 == 'R') {
                dateBuilder.setMarch();
                return true;
            } else if (c2 == 'y' || c2 == 'M') {
                dateBuilder.setMay();
                return true;
            }
        }
        return false;
    }

    private boolean isValidAprilAugust(char c0, char c1, char c2, Builder dateBuilder) {
        if (c0 == 'A' || c0 == 'a')
            if ((c1 == 'p' || c1 == 'P') && (c2 == 'r' || c2 == 'R')) {
                dateBuilder.setApril();
                return true;
            } else if ((c1 == 'u' || c1 == 'U') && (c2 == 'g' || c2 == 'G')) {
                dateBuilder.setAugust();
                return true;
            }
        return false;
    }

    private boolean isValidSeptember(char c0, char c1, char c2, Builder dateBuilder) {
        if ((c0 == 'S' || c0 == 's') && (c1 == 'e' || c1 == 'E') && (c2 == 'p' || c2 == 'P')) {
            dateBuilder.setSeptember();
            return true;
        }
        return false;
    }

    private boolean isValidOctober(char c0, char c1, char c2, Builder dateBuilder) {
        if ((c0 == 'O' || c0 == 'o') && (c1 == 'c' || c1 == 'C') && (c2 == 't' || c2 == 'T')) {
            dateBuilder.setOctobre();
            return true;
        }
        return false;
    }

    private boolean isValidNovember(char c0, char c1, char c2, Builder dateBuilder) {
        if ((c0 == 'N' || c0 == 'n') && (c1 == 'o' || c1 == 'O') && (c2 == 'v' || c2 == 'V')) {
            dateBuilder.setNovembre();
            return true;
        }
        return false;
    }

    private boolean isValidDecember(char c0, char c1, char c2, Builder dateBuilder) {
        if (c0 == 'D' || c0 == 'd')
            if (c1 == 'e' || c1 == 'E') {
                if (c2 == 'c' || c2 == 'C') {
                    dateBuilder.setDecember();
                    return true;
                }
            }
        return false;
    }

    private boolean isValidMonth(int start, int end, Builder dateBuilder) {

        if (end - start != 3)
            return false;

        char c0 = string.charAt(start);
        char c1 = string.charAt(start + 1);
        char c2 = string.charAt(start + 2);

        return isValidJanuaryJuneJuly(c0, c1, c2, dateBuilder) || //
                isValidFebruary(c0, c1, c2, dateBuilder) || //
                isValidMarchMay(c0, c1, c2, dateBuilder) || //
                isValidAprilAugust(c0, c1, c2, dateBuilder) || //
                isValidSeptember(c0, c1, c2, dateBuilder) || //
                isValidOctober(c0, c1, c2, dateBuilder) || //
                isValidNovember(c0, c1, c2, dateBuilder) || //
                isValidDecember(c0, c1, c2, dateBuilder);
    }

    private boolean isValid2DigitsYear(char c0, char c1, Builder dateBuilder) {
        if (isDigit(c0) && isDigit(c1)) {
            int i0 = getNumericValue(c0);
            int i1 = getNumericValue(c1);
            int year = i0 * 10 + i1;
            year = year < 70 ? year + 2000 : year + 1900;

            return setValidYear(year, dateBuilder);
        }
        return false;
    }

    private boolean isValid4DigitsYear(char c0, char c1, char c2, char c3, Builder dateBuilder) {
        if (isDigit(c0) && isDigit(c1) && isDigit(c2) && isDigit(c3)) {
            int i0 = getNumericValue(c0);
            int i1 = getNumericValue(c1);
            int i2 = getNumericValue(c2);
            int i3 = getNumericValue(c3);
            int year = i0 * 1000 + i1 * 100 + i2 * 10 + i3;

            return setValidYear(year, dateBuilder);
        }
        return false;
    }

    private boolean setValidYear(int year, Builder dateBuilder) {
        if (year >= 1601) {
            dateBuilder.setYear(year);
            return true;
        }
        return false;
    }

    private boolean isValidYear(int start, int end, Builder dateBuilder) {

        int length = end - start;

        if (length == 2) {
            char c0 = string.charAt(start);
            char c1 = string.charAt(start + 1);
            return isValid2DigitsYear(c0, c1, dateBuilder);

        } else if (length == 4) {
            char c0 = string.charAt(start);
            char c1 = string.charAt(start + 1);
            char c2 = string.charAt(start + 2);
            char c3 = string.charAt(start + 3);
            return isValid4DigitsYear(c0, c1, c2, c3, dateBuilder);
        }

        return false;
    }

    private boolean isValid1DigitHour(char c0, Builder dateBuilder) {
        if (isDigit(c0)) {
            int hour = getNumericValue(c0);
            dateBuilder.setHour(hour);
            return true;
        }
        return false;
    }

    private boolean isValid2DigitsHour(char c0, char c1, Builder dateBuilder) {
        if (isDigit(c0) && isDigit(c1)) {
            int i0 = getNumericValue(c0);
            int i1 = getNumericValue(c1);
            int hour = i0 * 10 + i1;
            if (hour <= 24) {
                dateBuilder.setHour(hour);
                return true;
            }
        }
        return false;
    }

    private boolean isValidHour(int start, int end, Builder dateBuilder) {

        int length = end - start;

        if (length == 1) {
            char c0 = string.charAt(start);
            return isValid1DigitHour(c0, dateBuilder);

        } else if (length == 2) {
            char c0 = string.charAt(start);
            char c1 = string.charAt(start + 1);
            return isValid2DigitsHour(c0, c1, dateBuilder);
        }
        return false;
    }

    private boolean isValid1DigitMinuteSecond(char c0, Builder dateBuilder, boolean minuteOrSecond) {
        if (isDigit(c0)) {
            int value = getNumericValue(c0);
            if (minuteOrSecond)
                dateBuilder.setMinute(value);
            else
                dateBuilder.setSecond(value);
            return true;
        }
        return false;
    }

    private boolean isValid2DigitsMinuteSecond(char c0, char c1, Builder dateBuilder, boolean minuteOrSecond) {
        if (isDigit(c0) && isDigit(c1)) {
            int i0 = getNumericValue(c0);
            int i1 = getNumericValue(c1);
            int value = i0 * 10 + i1;
            if (value <= 60) {
                if (minuteOrSecond)
                    dateBuilder.setMinute(value);
                else
                    dateBuilder.setSecond(value);
                return true;
            }
        }
        return false;
    }

    private boolean isValidMinuteSecond(int start, int end, Builder dateBuilder, boolean minuteOrSecond) {

        int length = end - start;

        if (length == 1) {
            char c0 = string.charAt(start);
            return isValid1DigitMinuteSecond(c0, dateBuilder, minuteOrSecond);

        } else if (length == 2) {
            char c0 = string.charAt(start);
            char c1 = string.charAt(start + 1);
            return isValid2DigitsMinuteSecond(c0, c1, dateBuilder, minuteOrSecond);
        }
        return false;
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private int getNumericValue(char c) {
        return (int) c - 48;
    }
}
