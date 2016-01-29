/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.util;

import java.util.BitSet;

/**
 * Convenience class that encapsulates details of "percent encoding"
 * (as per RFC-3986, see [http://www.ietf.org/rfc/rfc3986.txt]).
 */
public final class Utf8UrlEncoder {

    /**
     * Encoding table used for figuring out ascii characters that must be escaped
     * (all non-Ascii characters need to be encoded anyway)
     */
    public final static BitSet RFC3986_UNRESERVED_CHARS = new BitSet(256);
    public final static BitSet RFC3986_RESERVED_CHARS = new BitSet(256);
    public final static BitSet RFC3986_SUBDELIM_CHARS = new BitSet(256);
    public final static BitSet RFC3986_PCHARS = new BitSet(256);
    public final static BitSet BUILT_PATH_UNTOUCHED_CHARS = new BitSet(256);
    public final static BitSet BUILT_QUERY_UNTOUCHED_CHARS = new BitSet(256);
    // http://www.w3.org/TR/html5/forms.html#application/x-www-form-urlencoded-encoding-algorithm
    public final static BitSet FORM_URL_ENCODED_SAFE_CHARS = new BitSet(256);

    static {
        for (int i = 'a'; i <= 'z'; ++i) {
            RFC3986_UNRESERVED_CHARS.set(i);
            FORM_URL_ENCODED_SAFE_CHARS.set(i);
        }
        for (int i = 'A'; i <= 'Z'; ++i) {
            RFC3986_UNRESERVED_CHARS.set(i);
            FORM_URL_ENCODED_SAFE_CHARS.set(i);
        }
        for (int i = '0'; i <= '9'; ++i) {
            RFC3986_UNRESERVED_CHARS.set(i);
            FORM_URL_ENCODED_SAFE_CHARS.set(i);
        }
        RFC3986_UNRESERVED_CHARS.set('-');
        RFC3986_UNRESERVED_CHARS.set('.');
        RFC3986_UNRESERVED_CHARS.set('_');
        RFC3986_UNRESERVED_CHARS.set('~');

        RFC3986_SUBDELIM_CHARS.set('!');
        RFC3986_SUBDELIM_CHARS.set('$');
        RFC3986_SUBDELIM_CHARS.set('&');
        RFC3986_SUBDELIM_CHARS.set('\'');
        RFC3986_SUBDELIM_CHARS.set('(');
        RFC3986_SUBDELIM_CHARS.set(')');
        RFC3986_SUBDELIM_CHARS.set('*');
        RFC3986_SUBDELIM_CHARS.set('+');
        RFC3986_SUBDELIM_CHARS.set(',');
        RFC3986_SUBDELIM_CHARS.set(';');
        RFC3986_SUBDELIM_CHARS.set('=');
        
        FORM_URL_ENCODED_SAFE_CHARS.set('-');
        FORM_URL_ENCODED_SAFE_CHARS.set('.');
        FORM_URL_ENCODED_SAFE_CHARS.set('_');
        FORM_URL_ENCODED_SAFE_CHARS.set('*');

        RFC3986_RESERVED_CHARS.set('!');
        RFC3986_RESERVED_CHARS.set('*');
        RFC3986_RESERVED_CHARS.set('\'');
        RFC3986_RESERVED_CHARS.set('(');
        RFC3986_RESERVED_CHARS.set(')');
        RFC3986_RESERVED_CHARS.set(';');
        RFC3986_RESERVED_CHARS.set(':');
        RFC3986_RESERVED_CHARS.set('@');
        RFC3986_RESERVED_CHARS.set('&');
        RFC3986_RESERVED_CHARS.set('=');
        RFC3986_RESERVED_CHARS.set('+');
        RFC3986_RESERVED_CHARS.set('$');
        RFC3986_RESERVED_CHARS.set(',');
        RFC3986_RESERVED_CHARS.set('/');
        RFC3986_RESERVED_CHARS.set('?');
        RFC3986_RESERVED_CHARS.set('#');
        RFC3986_RESERVED_CHARS.set('[');
        RFC3986_RESERVED_CHARS.set(']');

        RFC3986_PCHARS.or(RFC3986_UNRESERVED_CHARS);
        RFC3986_PCHARS.or(RFC3986_SUBDELIM_CHARS);
        RFC3986_PCHARS.set(':');
        RFC3986_PCHARS.set('@');

        BUILT_PATH_UNTOUCHED_CHARS.or(RFC3986_PCHARS);
        BUILT_PATH_UNTOUCHED_CHARS.set('%');
        BUILT_PATH_UNTOUCHED_CHARS.set('/');

        BUILT_QUERY_UNTOUCHED_CHARS.or(RFC3986_PCHARS);
        BUILT_QUERY_UNTOUCHED_CHARS.set('%');
        BUILT_QUERY_UNTOUCHED_CHARS.set('/');
        BUILT_QUERY_UNTOUCHED_CHARS.set('?');
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Utf8UrlEncoder() {
    }

    public static String encodePath(String input) {
        StringBuilder sb = lazyAppendEncoded(null, input, BUILT_PATH_UNTOUCHED_CHARS, false);
        return sb == null? input : sb.toString();
    }

    public static StringBuilder encodeAndAppendQuery(StringBuilder sb, String query) {
        return appendEncoded(sb, query, BUILT_QUERY_UNTOUCHED_CHARS, false);
    }

    public static String encodeQueryElement(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 6);
        encodeAndAppendQueryElement(sb, input);
        return sb.toString();
    }

    public static StringBuilder encodeAndAppendQueryElement(StringBuilder sb, CharSequence input) {
        return appendEncoded(sb, input, RFC3986_UNRESERVED_CHARS, false);
    }

    public static StringBuilder encodeAndAppendFormElement(StringBuilder sb, CharSequence input) {
        return appendEncoded(sb, input, FORM_URL_ENCODED_SAFE_CHARS, true);
    }

    private static StringBuilder lazyInitStringBuilder(CharSequence input, int firstNonUsAsciiPosition) {
        StringBuilder sb = new StringBuilder(input.length() + 6);
        for (int i = 0; i < firstNonUsAsciiPosition; i++) {
            sb.append(input.charAt(i));
        }
        return sb;
    }
    
    private static StringBuilder lazyAppendEncoded(StringBuilder sb, CharSequence input, BitSet dontNeedEncoding, boolean encodeSpaceAsPlus) {
        int c;
        for (int i = 0; i < input.length(); i+= Character.charCount(c)) {
            c = Character.codePointAt(input, i);
            if (c <= 127) {
                if (dontNeedEncoding.get(c)) {
                    if (sb != null) {
                        sb.append((char) c);
                    }
                } else {
                    if (sb == null) {
                        sb = lazyInitStringBuilder(input, i);
                    }
                    appendSingleByteEncoded(sb, c, encodeSpaceAsPlus);
                }
            } else {
                if (sb == null) {
                    sb = lazyInitStringBuilder(input, i);
                }
                appendMultiByteEncoded(sb, c);
            }
        }
        return sb;
    }
    
    private static StringBuilder appendEncoded(StringBuilder sb, CharSequence input, BitSet dontNeedEncoding, boolean encodeSpaceAsPlus) {
        int c;
        for (int i = 0; i < input.length(); i+= Character.charCount(c)) {
            c = Character.codePointAt(input, i);
            if (c <= 127) {
                if (dontNeedEncoding.get(c)) {
                    sb.append((char) c);
                } else {
                    appendSingleByteEncoded(sb, c, encodeSpaceAsPlus);
                }
            } else {
                appendMultiByteEncoded(sb, c);
            }
        }
        return sb;
    }

    private final static void appendSingleByteEncoded(StringBuilder sb, int value, boolean encodeSpaceAsPlus) {

        if (value == ' ' && encodeSpaceAsPlus) {
            sb.append('+');
            return;
        }

        sb.append('%');
        sb.append(HEX[value >> 4]);
        sb.append(HEX[value & 0xF]);
    }

    private final static void appendMultiByteEncoded(StringBuilder sb, int value) {
        if (value < 0x800) {
            appendSingleByteEncoded(sb, (0xc0 | (value >> 6)), false);
            appendSingleByteEncoded(sb, (0x80 | (value & 0x3f)), false);
        } else if (value < 0x10000) {
            appendSingleByteEncoded(sb, (0xe0 | (value >> 12)), false);
            appendSingleByteEncoded(sb, (0x80 | ((value >> 6) & 0x3f)), false);
            appendSingleByteEncoded(sb, (0x80 | (value & 0x3f)), false);
        } else {
            appendSingleByteEncoded(sb, (0xf0 | (value >> 18)), false);
            appendSingleByteEncoded(sb, (0x80 | (value >> 12) & 0x3f), false);
            appendSingleByteEncoded(sb, (0x80 | (value >> 6) & 0x3f), false);
            appendSingleByteEncoded(sb, (0x80 | (value & 0x3f)), false);
        }
    }
}
