/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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

import java.util.BitSet;
import java.util.Date;

public class CookieUtil {

    private static final BitSet VALID_COOKIE_VALUE_OCTETS = validCookieValueOctets();

    private static final BitSet VALID_COOKIE_NAME_OCTETS = validCookieNameOctets(VALID_COOKIE_VALUE_OCTETS);

    // US-ASCII characters excluding CTLs, whitespace, DQUOTE, comma, semicolon, and backslash
    private static BitSet validCookieValueOctets() {

        BitSet bits = new BitSet(8);
        for (int i = 35; i < 127; i++) {
            // US-ASCII characters excluding CTLs (%x00-1F / %x7F)
            bits.set(i);
        }
        bits.set('"', false);  // exclude DQUOTE = %x22
        bits.set(',', false);  // exclude comma = %x2C
        bits.set(';', false);  // exclude semicolon = %x3B
        bits.set('\\', false); // exclude backslash = %x5C
        return bits;
    }

    //    token          = 1*<any CHAR except CTLs or separators>
    //    separators     = "(" | ")" | "<" | ">" | "@"
    //                   | "," | ";" | ":" | "\" | <">
    //                   | "/" | "[" | "]" | "?" | "="
    //                   | "{" | "}" | SP | HT
    private static BitSet validCookieNameOctets(BitSet validCookieValueOctets) {
        BitSet bits = new BitSet(8);
        bits.or(validCookieValueOctets);
        bits.set('(', false);
        bits.set(')', false);
        bits.set('<', false);
        bits.set('>', false);
        bits.set('@', false);
        bits.set(':', false);
        bits.set('/', false);
        bits.set('[', false);
        bits.set(']', false);
        bits.set('?', false);
        bits.set('=', false);
        bits.set('{', false);
        bits.set('}', false);
        bits.set(' ', false);
        bits.set('\t', false);
        return bits;
    }
    
    static int firstInvalidCookieNameOctet(CharSequence cs) {
        return firstInvalidOctet(cs, VALID_COOKIE_NAME_OCTETS);
    }

    static int firstInvalidCookieValueOctet(CharSequence cs) {
        return firstInvalidOctet(cs, VALID_COOKIE_VALUE_OCTETS);
    }

    static int firstInvalidOctet(CharSequence cs, BitSet bits) {

        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (!bits.get(c)) {
                return i;
            }
        }
        return -1;
    }

    static CharSequence unwrapValue(CharSequence cs) {
        final int len = cs.length();
        if (len > 0 && cs.charAt(0) == '"') {
            if (len >= 2 && cs.charAt(len - 1) == '"') {
                // properly balanced
                return len == 2 ? "" : cs.subSequence(1, len - 1);
            } else {
                return null;
            }
        }
        return cs;
    }

    static long computeExpires(String expires) {
        if (expires != null) {
            Date expiresDate = DateParser.parse(expires);
            if (expiresDate != null)
                return expiresDate.getTime();
        }
        
        return Long.MIN_VALUE;
    }
    
    private CookieUtil() {
        // Unused
    }
}
