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
package com.ning.http.util;

public final class UTF8UrlDecoder {

    private UTF8UrlDecoder() {
    }

    private static StringBuilder initSb(StringBuilder sb, int initialSbLength, String s, int i) {
        return sb != null ? sb : new StringBuilder(initialSbLength).append(s, 0, i);
    }

    private static int hexaDigit(char c) {
        return Character.digit(c, 0x10);
    }

    public static String decode(String s) {

        final int numChars = s.length();
        final int initialSbLength = numChars > 500 ? numChars / 2 : numChars;
        StringBuilder sb = null;
        int i = 0;

        while (i < numChars) {
            char c = s.charAt(i);
            if (c == '+') {
                sb = initSb(sb, initialSbLength, s, i);
                sb.append(' ');
                i++;

            } else if (c == '%') {
                if (numChars - i < 3) // We expect 3 chars. 0 based i vs. 1 based length!
                    throw new IllegalArgumentException("UTF8UrlDecoder: Incomplete trailing escape (%) pattern");

                int x, y;
                if ((x = hexaDigit(s.charAt(i+1))) == -1 || (y = hexaDigit(s.charAt(i+2))) == -1)
                    throw new IllegalArgumentException("UTF8UrlDecoder: Malformed");

                sb = initSb(sb, initialSbLength, s, i);
                byte b = (byte)((x << 4) + y);
                char cc = (char)(b);
                sb.append(cc);
                i+=3;
            } else {
                if (sb != null)
                    sb.append(c);
                i++;
            }
        }

        return sb != null ? sb.toString() : s;
    }
}
