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
package org.asynchttpclient.util;

public final class Utf8UrlDecoder {

    private Utf8UrlDecoder() {
    }

    private static StringBuilder initSb(StringBuilder sb, String s, int i, int offset, int length) {
        if (sb != null) {
            return sb;
        } else {
            int initialSbLength = length > 500 ? length / 2 : length;
            return new StringBuilder(initialSbLength).append(s, offset, i);
        }
    }

    private static int hexaDigit(char c) {
        return Character.digit(c, 16);
    }

    public static CharSequence decode(String s) {
        return decode(s, 0, s.length());
    }
    
    public static CharSequence decode(final String s, final int offset, final int length) {

        StringBuilder sb = null;
        int i = offset;
        int end = length + offset;

        while (i < end) {
            char c = s.charAt(i);
            if (c == '+') {
                sb = initSb(sb, s, i, offset, length);
                sb.append(' ');
                i++;

            } else if (c == '%') {
                if (end - i < 3) // We expect 3 chars. 0 based i vs. 1 based length!
                    throw new IllegalArgumentException("UTF8UrlDecoder: Incomplete trailing escape (%) pattern");

                int x, y;
                if ((x = hexaDigit(s.charAt(i + 1))) == -1 || (y = hexaDigit(s.charAt(i + 2))) == -1)
                    throw new IllegalArgumentException("UTF8UrlDecoder: Malformed");

                sb = initSb(sb, s, i, offset, length);
                sb.append((char) (x * 16 + y));
                i += 3;
            } else {
                if (sb != null)
                    sb.append(c);
                i++;
            }
        }

        return sb != null ? sb.toString() : new StringCharSequence(s, offset, length);
    }
}
