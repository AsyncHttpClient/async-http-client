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

import static org.asynchttpclient.util.Assertions.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.CharBuffer;

import static org.asynchttpclient.cookie.CookieUtil.*;

public class CookieDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(CookieDecoder.class);
    
    /**
     * Decodes the specified HTTP header value into {@link Cookie}.
     * 
     * @param header the Set-Cookie header
     * @return the decoded {@link Cookie}
     */
    public static Cookie decode(String header) {

        assertNotNull(header, "header");

        final int headerLen = header.length();

        if (headerLen == 0) {
            return null;
        }

        CookieBuilder cookieBuilder = null;

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

            int nameBegin = i;
            int nameEnd = i;
            int valueStart = -1;
            int valueEnd = -1;

            if (i != headerLen) {
                keyValLoop: for (;;) {

                    char curChar = header.charAt(i);
                    if (curChar == ';') {
                        // NAME; (no value till ';')
                        nameEnd = i;
                        valueStart = valueEnd = -1;
                        break keyValLoop;

                    } else if (curChar == '=') {
                        // NAME=VALUE
                        nameEnd = i;
                        i++;
                        if (i == headerLen) {
                            // NAME= (empty value, i.e. nothing after '=')
                            valueStart = valueEnd = 0;
                            break keyValLoop;
                        }

                        valueStart = i;
                        // NAME=VALUE;
                        int semiPos = header.indexOf(';', i);
                        valueEnd = i = semiPos > 0 ? semiPos : headerLen;
                        break keyValLoop;
                    } else {
                        i++;
                    }

                    if (i == headerLen) {
                        // NAME (no value till the end of string)
                        nameEnd = headerLen;
                        valueStart = valueEnd = -1;
                        break;
                    }
                }
            }

            if (valueEnd > 0 && header.charAt(valueEnd - 1) == ',') {
                // old multiple cookies separator, skipping it
                valueEnd--;
            }

            if (cookieBuilder == null) {
                // cookie name-value pair
                if (nameBegin == -1 || nameBegin == nameEnd) {
                    LOGGER.debug("Skipping cookie with null name");
                    return null;
                }

                if (valueStart == -1) {
                    LOGGER.debug("Skipping cookie with null value");
                    return null;
                }

                CharSequence wrappedValue = CharBuffer.wrap(header, valueStart, valueEnd);
                CharSequence unwrappedValue = unwrapValue(wrappedValue);
                if (unwrappedValue == null) {
                    LOGGER.debug("Skipping cookie because starting quotes are not properly balanced in '{}'", unwrappedValue);
                    return null;
                }

                final String name = header.substring(nameBegin, nameEnd);

                final boolean wrap = unwrappedValue.length() != valueEnd - valueStart;

                cookieBuilder = new CookieBuilder(name, unwrappedValue.toString(), wrap, header);

            } else {
                // cookie attribute
                cookieBuilder.appendAttribute(nameBegin, nameEnd, valueStart, valueEnd);
            }
        }
        return cookieBuilder.cookie();
    }

    private static class CookieBuilder {

        private static final String PATH = "Path";

        private static final String EXPIRES = "Expires";

        private static final String MAX_AGE = "Max-Age";

        private static final String DOMAIN = "Domain";

        private static final String SECURE = "Secure";

        private static final String HTTPONLY = "HTTPOnly";

        private final String name;
        private final String value;
        private final boolean wrap;
        private final String header;
        private String domain;
        private String path;
        private long maxAge = Long.MIN_VALUE;
        private int expiresStart;
        private int expiresEnd;
        private boolean secure;
        private boolean httpOnly;

        public CookieBuilder(String name, String value, boolean wrap, String header) {
            this.name = name;
            this.value = value;
            this.wrap = wrap;
            this.header = header;
        }

        public Cookie cookie() {
            return new Cookie(name, value, wrap, domain, path, mergeMaxAgeAndExpires(), secure, httpOnly);
        }

        private long mergeMaxAgeAndExpires() {
            // max age has precedence over expires
            if (maxAge != Long.MIN_VALUE) {
                return maxAge;
            } else {
                String expires = computeValue(expiresStart, expiresEnd);
                if (expires != null) {
                    long expiresMillis = computeExpires(expires);
                    if (expiresMillis != Long.MIN_VALUE) {
                        long maxAgeMillis = expiresMillis - System.currentTimeMillis();
                        return maxAgeMillis / 1000 + (maxAgeMillis % 1000 != 0 ? 1 : 0);
                    }
                }
            }
            return Long.MIN_VALUE;
        }
        
        /**
         * Parse and store a key-value pair. First one is considered to be the
         * cookie name/value. Unknown attribute names are silently discarded.
         *
         * @param keyStart
         *            where the key starts in the header
         * @param keyEnd
         *            where the key ends in the header
         * @param valueStart
         *            where the value starts in the header
         * @param valueEnd
         *            where the value ends in the header
         */
        public void appendAttribute(int keyStart, int keyEnd, int valueStart, int valueEnd) {
            setCookieAttribute(keyStart, keyEnd, valueStart, valueEnd);
        }

        private void setCookieAttribute(int keyStart, int keyEnd, int valueStart, int valueEnd) {

            int length = keyEnd - keyStart;

            if (length == 4) {
                parse4(keyStart, valueStart, valueEnd);
            } else if (length == 6) {
                parse6(keyStart, valueStart, valueEnd);
            } else if (length == 7) {
                parse7(keyStart, valueStart, valueEnd);
            } else if (length == 8) {
                parse8(keyStart, valueStart, valueEnd);
            }
        }

        private void parse4(int nameStart, int valueStart, int valueEnd) {
            if (header.regionMatches(true, nameStart, PATH, 0, 4)) {
                path = computeValue(valueStart, valueEnd);
            }
        }

        private void parse6(int nameStart, int valueStart, int valueEnd) {
            if (header.regionMatches(true, nameStart, DOMAIN, 0, 5)) {
                domain = computeValue(valueStart, valueEnd);
            } else if (header.regionMatches(true, nameStart, SECURE, 0, 5)) {
                secure = true;
            }
        }

        private void parse7(int nameStart, int valueStart, int valueEnd) {
            if (header.regionMatches(true, nameStart, EXPIRES, 0, 7)) {
                expiresStart = valueStart;
                expiresEnd = valueEnd;
            } else if (header.regionMatches(true, nameStart, MAX_AGE, 0, 7)) {
                try {
                    maxAge = Math.max(Integer.valueOf(computeValue(valueStart, valueEnd)), 0);
                } catch (NumberFormatException e1) {
                    // ignore failure to parse -> treat as session cookie
                }
            }
        }

        private void parse8(int nameStart, int valueStart, int valueEnd) {
            if (header.regionMatches(true, nameStart, HTTPONLY, 0, 8)) {
                httpOnly = true;
            }
        }

        private String computeValue(int valueStart, int valueEnd) {
            if (valueStart == -1 || valueStart == valueEnd) {
                return null;
            } else {
                while (valueStart < valueEnd && header.charAt(valueStart) <= ' ') {
                    valueStart++;
                }
                while (valueStart < valueEnd && (header.charAt(valueEnd - 1) <= ' ')) {
                    valueEnd--;
                }
                return valueStart == valueEnd ? null : header.substring(valueStart, valueEnd);
            }
        }
    }
}
