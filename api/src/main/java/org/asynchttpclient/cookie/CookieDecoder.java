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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.CharBuffer;

import static org.asynchttpclient.cookie.CookieUtil.*;

public class CookieDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(CookieDecoder.class);
    
    /**
     * Decodes the specified HTTP header value into {@link Cookie}.
     * 
     * @return the decoded {@link Cookie}
     */
    public static Cookie decode(String header) {

        if (header == null) {
            throw new NullPointerException("header");
        }

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
            int valueBegin = -1;
            int valueEnd = -1;

            if (i != headerLen) {
                keyValLoop: for (;;) {

                    char curChar = header.charAt(i);
                    if (curChar == ';') {
                        // NAME; (no value till ';')
                        nameEnd = i;
                        valueBegin = valueEnd = -1;
                        break keyValLoop;

                    } else if (curChar == '=') {
                        // NAME=VALUE
                        nameEnd = i;
                        i++;
                        if (i == headerLen) {
                            // NAME= (empty value, i.e. nothing after '=')
                            valueBegin = valueEnd = 0;
                            break keyValLoop;
                        }

                        valueBegin = i;
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
                        valueBegin = valueEnd = -1;
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

                if (valueBegin == -1) {
                    LOGGER.debug("Skipping cookie with null value");
                    return null;
                }

                CharSequence wrappedValue = CharBuffer.wrap(header, valueBegin, valueEnd);
                CharSequence unwrappedValue = unwrapValue(wrappedValue);
                if (unwrappedValue == null) {
                    LOGGER.debug("Skipping cookie because starting quotes are not properly balanced in '{}'", unwrappedValue);
                    return null;
                }

                final String name = header.substring(nameBegin, nameEnd);

                final boolean wrap = unwrappedValue.length() != valueEnd - valueBegin;

                cookieBuilder = new CookieBuilder(name, unwrappedValue.toString(), wrap);

            } else {
                // cookie attribute
                String attrValue = valueBegin == -1 ? null : header.substring(valueBegin, valueEnd);
                cookieBuilder.appendAttribute(header, nameBegin, nameEnd, attrValue);
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
        private String domain;
        private String path;
        private int maxAge = Integer.MIN_VALUE;
        private String expires;
        private boolean secure;
        private boolean httpOnly;

        public CookieBuilder(String name, String value, boolean wrap) {
            this.name = name;
            this.value = value;
            this.wrap = wrap;
        }

        public Cookie cookie() {
            return new Cookie(name, value, wrap, domain, path, computeExpires(expires), maxAge, secure, httpOnly);
        }

        /**
         * Parse and store a key-value pair. First one is considered to be the
         * cookie name/value. Unknown attribute names are silently discarded.
         *
         * @param header
         *            the HTTP header
         * @param keyStart
         *            where the key starts in the header
         * @param keyEnd
         *            where the key ends in the header
         * @param value
         *            the decoded value
         */
        public void appendAttribute(String header, int keyStart, int keyEnd, String value) {
            setCookieAttribute(header, keyStart, keyEnd, value);
        }

        private void setCookieAttribute(String header, int keyStart, int keyEnd, String value) {

            int length = keyEnd - keyStart;

            if (length == 4) {
                parse4(header, keyStart, value);
            } else if (length == 6) {
                parse6(header, keyStart, value);
            } else if (length == 7) {
                parse7(header, keyStart, value);
            } else if (length == 8) {
                parse8(header, keyStart, value);
            }
        }

        private void parse4(String header, int nameStart, String value) {
            if (header.regionMatches(true, nameStart, PATH, 0, 4)) {
                path = value;
            }
        }

        private void parse6(String header, int nameStart, String value) {
            if (header.regionMatches(true, nameStart, DOMAIN, 0, 5)) {
                domain = value.length() > 0 ? value.toString() : null;
            } else if (header.regionMatches(true, nameStart, SECURE, 0, 5)) {
                secure = true;
            }
        }

        private void setExpire(String value) {
            expires = value;
        }

        private void setMaxAge(String value) {
            try {
                maxAge = Math.max(Integer.valueOf(value), 0);
            } catch (NumberFormatException e1) {
                // ignore failure to parse -> treat as session cookie
            }
        }

        private void parse7(String header, int nameStart, String value) {
            if (header.regionMatches(true, nameStart, EXPIRES, 0, 7)) {
                setExpire(value);
            } else if (header.regionMatches(true, nameStart, MAX_AGE, 0, 7)) {
                setMaxAge(value);
            }
        }

        private void parse8(String header, int nameStart, String value) {

            if (header.regionMatches(true, nameStart, HTTPONLY, 0, 8)) {
                httpOnly = true;
            }
        }
    }
}
