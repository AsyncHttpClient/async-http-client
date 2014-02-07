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

import org.asynchttpclient.date.CalendarTimeConverter;
import org.asynchttpclient.date.TimeConverter;

public class CookieDecoder {

    public static final TimeConverter DEFAULT_TIME_CONVERTER = new CalendarTimeConverter();

    public static Cookie decode(String header) {
        return decode(header, DEFAULT_TIME_CONVERTER);
    }

    /**
     * Decodes the specified HTTP header value into {@link Cookie}.
     * 
     * @return the decoded {@link Cookie}
     */
    public static Cookie decode(String header, TimeConverter timeConverter) {

        if (timeConverter == null)
            timeConverter = DEFAULT_TIME_CONVERTER;

        if (header.isEmpty())
            return null;

        KeyValuePairsParser pairsParser = new KeyValuePairsParser(timeConverter);

        final int headerLen = header.length();
        loop: for (int i = 0;;) {

            // Skip spaces and separators.
            for (;;) {
                if (i == headerLen) {
                    break loop;
                }
                char c = header.charAt(i);
                if (c == ',') {
                    // Having multiple cookies in a single Set-Cookie header is
                    // deprecated, modern browsers only parse the first one
                    break loop;

                } else if (c == '\t' || c == '\n' || c == 0x0b || c == '\f' || c == '\r' || c == ' ' || c == ';') {
                    i++;
                    continue;
                }
                break;
            }

            int newNameStart = i;
            int newNameEnd = i;
            String value;
            String rawValue;
            boolean first = true;

            if (i == headerLen) {
                value = rawValue = null;
            } else {
                keyValLoop: for (;;) {

                    char curChar = header.charAt(i);
                    if (curChar == ';') {
                        // NAME; (no value till ';')
                        newNameEnd = i;
                        value = rawValue = null;
                        first = false;
                        break keyValLoop;
                    } else if (curChar == '=') {
                        // NAME=VALUE
                        newNameEnd = i;
                        i++;
                        if (i == headerLen) {
                            // NAME= (empty value, i.e. nothing after '=')
                            value = rawValue = "";
                            first = false;
                            break keyValLoop;
                        }

                        int newValueStart = i;
                        char c = header.charAt(i);
                        if (c == '"' || c == '\'') {
                            // NAME="VALUE" or NAME='VALUE'
                            StringBuilder newValueBuf = new StringBuilder(header.length() - i);

                            int rawValueStart = i;
                            int rawValueEnd = i;

                            final char q = c;
                            boolean hadBackslash = false;
                            i++;
                            for (;;) {
                                if (i == headerLen) {
                                    value = newValueBuf.toString();
                                    // only need to compute raw value for cookie
                                    // value which is at most in 2nd position
                                    rawValue = first ? header.substring(rawValueStart, rawValueEnd) : null;
                                    first = false;
                                    break keyValLoop;
                                }
                                if (hadBackslash) {
                                    hadBackslash = false;
                                    c = header.charAt(i++);
                                    rawValueEnd = i;
                                    switch (c) {
                                    case '\\':
                                    case '"':
                                    case '\'':
                                        // Escape last backslash.
                                        newValueBuf.setCharAt(newValueBuf.length() - 1, c);
                                        break;
                                    default:
                                        // Do not escape last backslash.
                                        newValueBuf.append(c);
                                    }
                                } else {
                                    c = header.charAt(i++);
                                    rawValueEnd = i;
                                    if (c == q) {
                                        value = newValueBuf.toString();
                                        // only need to compute raw value for
                                        // cookie value which is at most in 2nd
                                        // position
                                        rawValue = first ? header.substring(rawValueStart, rawValueEnd) : null;
                                        first = false;
                                        break keyValLoop;
                                    }
                                    newValueBuf.append(c);
                                    if (c == '\\') {
                                        hadBackslash = true;
                                    }
                                }
                            }
                        } else {
                            // NAME=VALUE;
                            int semiPos = header.indexOf(';', i);
                            if (semiPos > 0) {
                                value = rawValue = header.substring(newValueStart, semiPos);
                                i = semiPos;
                            } else {
                                value = rawValue = header.substring(newValueStart);
                                i = headerLen;
                            }
                        }
                        break keyValLoop;
                    } else {
                        i++;
                    }

                    if (i == headerLen) {
                        // NAME (no value till the end of string)
                        newNameEnd = headerLen;
                        first = false;
                        value = rawValue = null;
                        break;
                    }
                }
            }

            pairsParser.parseKeyValuePair(header, newNameStart, newNameEnd, value, rawValue);
        }
        return pairsParser.cookie();
    }
}
