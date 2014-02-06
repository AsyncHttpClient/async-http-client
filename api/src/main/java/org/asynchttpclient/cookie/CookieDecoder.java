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


public class CookieDecoder {

    /**
     * Decodes the specified HTTP header value into {@link Cookie}.
     * 
     * @return the decoded {@link Cookie}
     */
    public static Cookie decode(String header) {

        if (header.isEmpty())
            return null;

        CookieBuilder cookieBuilder = new CookieBuilder();

        final int headerLen = header.length();
        loop: for (int i = 0;;) {

            // Skip spaces and separators.
            for (;;) {
                if (i == headerLen) {
                    break loop;
                }
                switch (header.charAt(i)) {
                case ',':
                    // Having multiple cookies in a single Set-Cookie header is
                    // deprecated, modern browsers only parse the first one
                    break loop;

                case '\t':
                case '\n':
                case 0x0b:
                case '\f':
                case '\r':
                case ' ':
                case ';':
                    i++;
                    continue;
                }
                break;
            }

            // Skip '$'.
            for (;;) {
                if (i == headerLen) {
                    break loop;
                }
                if (header.charAt(i) == '$') {
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
                    switch (header.charAt(i)) {
                    case ';':
                        // NAME; (no value till ';')
                        newNameEnd = i;
                        value = rawValue = null;
                        first = false;
                        break keyValLoop;
                    case '=':
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
                    default:
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

            cookieBuilder.addKeyValuePair(header, newNameStart, newNameEnd, value, rawValue);
        }
        return cookieBuilder.build();
    }
}
