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

import java.util.Collection;

import com.ning.http.client.Cookie;

/**
 * Encodes {@link Cookie}s into an HTTP header value.  This encoder can encode
 * the HTTP cookie version 0, 1, and 2.
 * <p>
 * This encoder is stateful.  It maintains an internal data structure that
 * holds the {@link Cookie}s added by the {@link #addCookie(String, String)}
 * method.  Once {@link #encode()} is called, all added {@link Cookie}s are
 * encoded into an HTTP header value and all {@link Cookie}s in the internal
 * data structure are removed so that the encoder can start over.
 * <pre>
 * // Client-side example
 * {@link HttpRequest} req = ...;
 * {@link CookieEncoder} encoder = new {@link CookieEncoder}(false);
 * encoder.addCookie("JSESSIONID", "1234");
 * res.setHeader("Cookie", encoder.encode());
 *
 * // Server-side example
 * {@link HttpResponse} res = ...;
 * {@link CookieEncoder} encoder = new {@link CookieEncoder}(true);
 * encoder.addCookie("JSESSIONID", "1234");
 * res.setHeader("Set-Cookie", encoder.encode());
 * </pre>
 *
 * @see CookieDecoder
 *
 * @apiviz.stereotype utility
 * @apiviz.has        org.jboss.netty.handler.codec.http.Cookie oneway - - encodes
 */
// This fork brings support for RFC6265, that's used if the Cookie has a raw value
public final class CookieEncoder {

    private CookieEncoder() {
    }
    
    public static String encodeClientSide(Collection<Cookie> cookies, boolean useRFC6265Style) {
        StringBuilder sb = new StringBuilder();

        for (Cookie cookie: cookies) {
            if (useRFC6265Style)
                encodeRFC6265Style(sb, cookie);
            else
                encodeRFC2965Style(sb, cookie);
        }

        if (sb.length() > 0) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }
    
    private static void encodeRFC6265Style(StringBuilder sb, Cookie cookie) {
        addUnquoted(sb, cookie.getName(), cookie.getRawValue());
    }
    
    private static void encodeRFC2965Style(StringBuilder sb, Cookie cookie) {
        if (cookie.getVersion() >= 1) {
            add(sb, '$' + CookieHeaderNames.VERSION, 1);
        }

        add(sb, cookie.getName(), cookie.getValue());

        if (cookie.getPath() != null) {
            add(sb, '$' + CookieHeaderNames.PATH, cookie.getPath());
        }

        if (cookie.getDomain() != null) {
            add(sb, '$' + CookieHeaderNames.DOMAIN, cookie.getDomain());
        }

        if (cookie.getVersion() >= 1) {
            if (!cookie.getPorts().isEmpty()) {
                sb.append('$');
                sb.append(CookieHeaderNames.PORT);
                sb.append((char) HttpConstants.EQUALS);
                sb.append((char) HttpConstants.DOUBLE_QUOTE);
                for (int port: cookie.getPorts()) {
                    sb.append(port);
                    sb.append((char) HttpConstants.COMMA);
                }
                sb.setCharAt(sb.length() - 1, (char) HttpConstants.DOUBLE_QUOTE);
                sb.append((char) HttpConstants.SEMICOLON);
                sb.append((char) HttpConstants.SP);
            }
        }
    }

    private static void add(StringBuilder sb, String name, String val) {
        if (val == null) {
            addQuoted(sb, name, "");
            return;
        }

        for (int i = 0; i < val.length(); i ++) {
            char c = val.charAt(i);
            switch (c) {
            case '\t': case ' ': case '"': case '(':  case ')': case ',':
            case '/':  case ':': case ';': case '<':  case '=': case '>':
            case '?':  case '@': case '[': case '\\': case ']':
            case '{':  case '}':
                addQuoted(sb, name, val);
                return;
            }
        }

        addUnquoted(sb, name, val);
    }

    private static void addUnquoted(StringBuilder sb, String name, String val) {
        sb.append(name);
        sb.append((char) HttpConstants.EQUALS);
        sb.append(val);
        sb.append((char) HttpConstants.SEMICOLON);
        sb.append((char) HttpConstants.SP);
    }

    private static void addQuoted(StringBuilder sb, String name, String val) {
        if (val == null) {
            val = "";
        }

        sb.append(name);
        sb.append((char) HttpConstants.EQUALS);
        sb.append((char) HttpConstants.DOUBLE_QUOTE);
        sb.append(val.replace("\\", "\\\\").replace("\"", "\\\""));
        sb.append((char) HttpConstants.DOUBLE_QUOTE);
        sb.append((char) HttpConstants.SEMICOLON);
        sb.append((char) HttpConstants.SP);
    }

    private static void add(StringBuilder sb, String name, int val) {
        sb.append(name);
        sb.append((char) HttpConstants.EQUALS);
        sb.append(val);
        sb.append((char) HttpConstants.SEMICOLON);
        sb.append((char) HttpConstants.SP);
    }
}
