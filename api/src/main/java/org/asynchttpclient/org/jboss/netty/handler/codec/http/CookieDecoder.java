/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.org.jboss.netty.handler.codec.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.asynchttpclient.Cookie;
import org.asynchttpclient.Cookie.CookieBuilder;
import org.asynchttpclient.org.jboss.netty.util.internal.StringUtil;
import org.asynchttpclient.util.AsyncHttpProviderUtils;

/**
 * Decodes an HTTP header value into {@link Cookie}s. This decoder can decode the HTTP cookie version 0, 1, and 2.
 * 
 * <pre>
 * {@link HttpRequest} req = ...;
 * String value = req.getHeader("Cookie");
 * Set&lt;{@link Cookie}&gt; cookies = new {@link CookieDecoder}().decode(value);
 * </pre>
 * 
 * @see CookieEncoder
 * 
 * @apiviz.stereotype utility
 * @apiviz.has org.jboss.netty.handler.codec.http.Cookie oneway - - decodes
 */
public class CookieDecoder {

    private static final char COMMA = ',';

    private static class CookiesBuilder {

        private int i;
        private int version = 0;
        private CookieBuilder cookieBuilder;
        private List<Cookie> cookies = new ArrayList<Cookie>();

        public void addKeyValuePair(String name, String value, String rawValue) {

            // $Version is the only attribute that can appear before the actual
            // cookie name-value pair.
            if (i == 0 && name.equalsIgnoreCase(CookieHeaderNames.VERSION)) {
                try {
                    version = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    // Ignore.
                }

            } else if (cookieBuilder == null) {
                cookieBuilder = new CookieBuilder(name, value, rawValue);
                if (version != 0)
                    cookieBuilder.setVersion(version);

            } else if (setCookieAttribute(name, value)) {
                cookies.add(cookieBuilder.build());
                cookieBuilder = null;
            }
        }

        public List<Cookie> build() {
            cookies.add(cookieBuilder.build());
            return cookies;
        }

        private boolean setCookieAttribute(String name, String value) {

            if (CookieHeaderNames.DISCARD.equalsIgnoreCase(name)) {
                cookieBuilder.setDiscard(true);

            } else if (CookieHeaderNames.SECURE.equalsIgnoreCase(name)) {
                cookieBuilder.setSecure(true);

            } else if (CookieHeaderNames.HTTPONLY.equalsIgnoreCase(name)) {
                cookieBuilder.setHttpOnly(true);

            } else if (CookieHeaderNames.COMMENT.equalsIgnoreCase(name)) {
                cookieBuilder.setComment(value);

            } else if (CookieHeaderNames.COMMENTURL.equalsIgnoreCase(name)) {
                cookieBuilder.setCommentUrl(value);

            } else if (CookieHeaderNames.DOMAIN.equalsIgnoreCase(name)) {
                cookieBuilder.setDomain(value);

            } else if (CookieHeaderNames.PATH.equalsIgnoreCase(name)) {
                cookieBuilder.setPath(value);

            } else if (CookieHeaderNames.EXPIRES.equalsIgnoreCase(name)) {
                setCookieExpires(cookieBuilder, value);

            } else if (CookieHeaderNames.MAX_AGE.equalsIgnoreCase(name)) {
                cookieBuilder.setMaxAge(Integer.parseInt(value));

            } else if (CookieHeaderNames.VERSION.equalsIgnoreCase(name)) {
                cookieBuilder.setVersion(Integer.parseInt(value));

            } else if (CookieHeaderNames.PORT.equalsIgnoreCase(name)) {
                setCookiePorts(cookieBuilder, value);

            } else {
                return true;
            }

            return false;
        }

        private void setCookieExpires(CookieBuilder cookieBuilder, String value) {
            try {
                cookieBuilder.setMaxAge(AsyncHttpProviderUtils.convertExpireField(value));
            } catch (Exception e) {
                // original behavior, is this correct at all (expires field with max-age semantics)?
                try {
                    cookieBuilder.setMaxAge(Math.max(Integer.valueOf(value), 0));
                } catch (NumberFormatException e1) {
                    // ignore failure to parse -> treat as session cookie
                }
            }
        }

        private void setCookiePorts(CookieBuilder cookieBuilder, String value) {
            String[] portList = StringUtil.split(value, COMMA);
            Set<Integer> ports = new TreeSet<Integer>();
            for (String s1 : portList) {
                try {
                    ports.add(Integer.valueOf(s1));
                } catch (NumberFormatException e) {
                    // Ignore.
                }
            }
            cookieBuilder.setPorts(ports);
        }
    }

    private CookieDecoder() {
    }

    /**
     * Decodes the specified HTTP header value into {@link Cookie}s.
     * 
     * @return the decoded {@link Cookie}s
     */
    public static List<Cookie> decode(String header) {

        if (header.isEmpty())
            return Collections.emptyList();

        CookiesBuilder cookiesBuilder = new CookiesBuilder();

        final int headerLen = header.length();
        loop: for (int i = 0;;) {

            // Skip spaces and separators.
            for (;;) {
                if (i == headerLen) {
                    break loop;
                }
                switch (header.charAt(i)) {
                case '\t':
                case '\n':
                case 0x0b:
                case '\f':
                case '\r':
                case ' ':
                case ',':
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

            String name;
            String value;
            String rawValue;
            int names = 0;

            if (i == headerLen) {
                name = null;
                value = rawValue = null;
            } else {
                int newNameStart = i;
                keyValLoop: for (;;) {
                    switch (header.charAt(i)) {
                    case ';':
                        // NAME; (no value till ';')
                        name = header.substring(newNameStart, i);
                        names++;
                        value = rawValue = null;
                        break keyValLoop;
                    case '=':
                        // NAME=VALUE
                        name = header.substring(newNameStart, i);
                        names++;
                        i++;
                        if (i == headerLen) {
                            // NAME= (empty value, i.e. nothing after '=')
                            value = rawValue = "";
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
                                    // only need to compute raw value for cookie value which is at most in 2nd position
                                    rawValue = names <= 1 ? header.substring(rawValueStart, rawValueEnd) : null;
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
                                        // only need to compute raw value for cookie value which is at most in 2nd position
                                        rawValue = names <= 1 ? header.substring(rawValueStart, rawValueEnd) : null;
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
                        name = header.substring(newNameStart);
                        value = rawValue = null;
                        break;
                    }
                }
            }

            cookiesBuilder.addKeyValuePair(name, value, rawValue);
        }
        return cookiesBuilder.build();
    }
}
