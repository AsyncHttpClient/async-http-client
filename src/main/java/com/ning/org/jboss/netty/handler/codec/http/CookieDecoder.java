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
/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package com.ning.org.jboss.netty.handler.codec.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import com.ning.org.jboss.netty.util.internal.StringUtil;
import com.ning.http.client.Cookie;
import com.ning.http.util.AsyncHttpProviderUtils;

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

    /**
     * Creates a new decoder.
     */
    private CookieDecoder() {
    }

    /**
     * Decodes the specified HTTP header value into {@link Cookie}s.
     * 
     * @return the decoded {@link Cookie}s
     */
    public static Set<Cookie> decode(String header) {
        List<String> names = new ArrayList<String>(8);
        List<String> values = new ArrayList<String>(8);
        List<String> rawValues = new ArrayList<String>(8);
        extractKeyValuePairs(header, names, values, rawValues);

        if (names.isEmpty()) {
            return Collections.emptySet();
        }

        int i;
        int version = 0;

        // $Version is the only attribute that can appear before the actual
        // cookie name-value pair.
        if (names.get(0).equalsIgnoreCase(CookieHeaderNames.VERSION)) {
            try {
                version = Integer.parseInt(values.get(0));
            } catch (NumberFormatException e) {
                // Ignore.
            }
            i = 1;
        } else {
            i = 0;
        }

        if (names.size() <= i) {
            // There's a version attribute, but nothing more.
            return Collections.emptySet();
        }

        Set<Cookie> cookies = new HashSet<Cookie>();
        for (; i < names.size(); i++) {
            String name = names.get(i);
            String value = values.get(i);
            String rawValue = rawValues.get(i);
            if (value == null) {
                value = "";
            }
            if (rawValue == null) {
                rawValue = "";
            }

            String cookieName = name;
            String cookieValue = value;
            String cookieRawValue = rawValue;
            boolean discard = false;
            boolean secure = false;
            boolean httpOnly = false;
            String comment = null;
            String commentURL = null;
            String domain = null;
            String path = null;
            int maxAge = -1;
            List<Integer> ports = Collections.emptyList();

            for (int j = i + 1; j < names.size(); j++, i++) {
                name = names.get(j);
                value = values.get(j);

                if (CookieHeaderNames.DISCARD.equalsIgnoreCase(name)) {
                    discard = true;
                } else if (CookieHeaderNames.SECURE.equalsIgnoreCase(name)) {
                    secure = true;
                } else if (CookieHeaderNames.HTTPONLY.equalsIgnoreCase(name)) {
                    httpOnly = true;
                } else if (CookieHeaderNames.COMMENT.equalsIgnoreCase(name)) {
                    comment = value;
                } else if (CookieHeaderNames.COMMENTURL.equalsIgnoreCase(name)) {
                    commentURL = value;
                } else if (CookieHeaderNames.DOMAIN.equalsIgnoreCase(name)) {
                    domain = value;
                } else if (CookieHeaderNames.PATH.equalsIgnoreCase(name)) {
                    path = value;
                } else if (CookieHeaderNames.EXPIRES.equalsIgnoreCase(name)) {
                    try {
                        maxAge = AsyncHttpProviderUtils.convertExpireField(value);
                    } catch (Exception e) {
                        // original behavior, is this correct at all (expires field with max-age semantics)?
                        try {
                            maxAge = Math.max(Integer.valueOf(value), 0);
                        } catch (NumberFormatException e1) {
                            // ignore failure to parse -> treat as session cookie
                        }
                    }
                } else if (CookieHeaderNames.MAX_AGE.equalsIgnoreCase(name)) {
                    maxAge = Integer.parseInt(value);
                } else if (CookieHeaderNames.VERSION.equalsIgnoreCase(name)) {
                    version = Integer.parseInt(value);
                } else if (CookieHeaderNames.PORT.equalsIgnoreCase(name)) {
                    String[] portList = StringUtil.split(value, COMMA);
                    ports = new ArrayList<Integer>(2);
                    for (String s1 : portList) {
                        try {
                            ports.add(Integer.valueOf(s1));
                        } catch (NumberFormatException e) {
                            // Ignore.
                        }
                    }
                } else {
                    break;
                }
            }

            Cookie c = new Cookie(domain, cookieName, cookieValue, cookieRawValue, path, maxAge, secure, version, httpOnly, discard, comment, commentURL, ports);
            cookies.add(c);
        }

        return cookies;
    }

    private static void extractKeyValuePairs(final String header, final List<String> names, final List<String> values, final List<String> rawValues) {

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

            if (i == headerLen) {
                name = null;
                value = null;
                rawValue = null;
            } else {
                int newNameStart = i;
                keyValLoop: for (;;) {
                    switch (header.charAt(i)) {
                    case ';':
                        // NAME; (no value till ';')
                        name = header.substring(newNameStart, i);
                        value = null;
                        rawValue = null;
                        break keyValLoop;
                    case '=':
                        // NAME=VALUE
                        name = header.substring(newNameStart, i);
                        i++;
                        if (i == headerLen) {
                            // NAME= (empty value, i.e. nothing after '=')
                            value = "";
                            rawValue = "";
                            break keyValLoop;
                        }

                        int newValueStart = i;
                        char c = header.charAt(i);
                        if (c == '"' || c == '\'') {
                            // NAME="VALUE" or NAME='VALUE'
                            StringBuilder newValueBuf = new StringBuilder(header.length() - i);
                            StringBuilder newRawValueBuf = new StringBuilder(header.length() - i);
                            newRawValueBuf.append(c);
                            final char q = c;
                            boolean hadBackslash = false;
                            i++;
                            for (;;) {
                                if (i == headerLen) {
                                    value = newValueBuf.toString();
                                    rawValue = newRawValueBuf.toString();
                                    break keyValLoop;
                                }
                                if (hadBackslash) {
                                    hadBackslash = false;
                                    c = header.charAt(i++);
                                    newRawValueBuf.append(c);
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
                                    newRawValueBuf.append(c);
                                    if (c == q) {
                                        value = newValueBuf.toString();
                                        rawValue = newRawValueBuf.toString();
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

            names.add(name);
            values.add(value);
            rawValues.add(rawValue);
        }
    }
}
