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

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

import org.asynchttpclient.util.StringUtils;

public final class CookieEncoder {

    /**
     * Sort cookies into decreasing order of path length, breaking ties by sorting into increasing chronological order of creation time, as recommended by RFC 6265.
     */
    private static final Comparator<Cookie> COOKIE_COMPARATOR = new Comparator<Cookie>() {
        @Override
        public int compare(Cookie c1, Cookie c2) {
            String path1 = c1.getPath();
            String path2 = c2.getPath();
            // Cookies with unspecified path default to the path of the request. We don't
            // know the request path here, but we assume that the length of an unspecified
            // path is longer than any specified path (i.e. pathless cookies come first),
            // because setting cookies with a path longer than the request path is of
            // limited use.
            int len1 = path1 == null ? Integer.MAX_VALUE : path1.length();
            int len2 = path2 == null ? Integer.MAX_VALUE : path2.length();
            int diff = len2 - len1;
            if (diff != 0) {
                return diff;
            }
            // Rely on Java's sort stability to retain creation order in cases where
            // cookies have same path length.
            return -1;
        }
    };

    private CookieEncoder() {
    }

    public static String encode(Collection<Cookie> cookies) {
        StringBuilder sb = StringUtils.stringBuilder();

        if (cookies.isEmpty()) {
            return "";

        } else if (cookies.size() == 1) {
            Cookie cookie = cookies.iterator().next();
            if (cookie != null) {
                add(sb, cookie.getName(), cookie.getValue(), cookie.isWrap());
            }

        } else {
            Cookie[] cookiesSorted = cookies.toArray(new Cookie[cookies.size()]);
            Arrays.sort(cookiesSorted, COOKIE_COMPARATOR);
            for (Cookie cookie : cookiesSorted) {
                if (cookie != null) {
                    add(sb, cookie.getName(), cookie.getValue(), cookie.isWrap());
                }
            }
        }

        if (sb.length() > 0) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }

    private static void add(StringBuilder sb, String name, String val, boolean wrap) {

        if (val == null) {
            val = "";
        }

        sb.append(name);
        sb.append('=');
        if (wrap)
            sb.append('"').append(val).append('"');
        else
            sb.append(val);
        sb.append(';');
        sb.append(' ');
    }
}
