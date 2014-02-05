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
package org.asynchttpclient.org.jboss.netty.handler.codec.http;

import java.util.Collection;

import org.asynchttpclient.Cookie;

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

    public static String encodeClientSide(Collection<Cookie> cookies) {
        StringBuilder sb = new StringBuilder();

        for (Cookie cookie : cookies) {
            addUnquoted(sb, cookie.getName(), cookie.getRawValue());
        }

        if (sb.length() > 0) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }

    private static void addUnquoted(StringBuilder sb, String name, String val) {

        if (val == null) {
            val = "";
        }

        sb.append(name);
        sb.append((char) HttpConstants.EQUALS);
        sb.append(val);
        sb.append((char) HttpConstants.SEMICOLON);
        sb.append((char) HttpConstants.SP);
    }
}
