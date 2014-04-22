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
package com.ning.http.util;

/**
 * Convenience class that encapsulates details of "percent encoding"
 * (as per RFC-3986, see [http://www.ietf.org/rfc/rfc3986.txt]).
 */
public class UTF8UrlEncoder {
    private static final boolean encodeSpaceUsingPlus = System.getProperty("com.com.ning.http.util.UTF8UrlEncoder.encodeSpaceUsingPlus") == null ? false : true;

    /**
     * gen-delims  = ":" / "/" / "?" / "#" / "[" / "]" / "@"
     */
    private final static boolean[] GEN_DELIMS = new boolean[128];

    static {
        GEN_DELIMS[':'] = true;
        GEN_DELIMS['/'] = true;
        GEN_DELIMS['?'] = true;
        GEN_DELIMS['#'] = true;
        GEN_DELIMS['['] = true;
        GEN_DELIMS[']'] = true;
        GEN_DELIMS['@'] = true;
    }

    /**
     * sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
     / "*" / "+" / "," / ";" / "="
     */
    private final static boolean[] SUB_DELIMS = new boolean[128];

    static {
        SUB_DELIMS['!'] = true;
        SUB_DELIMS['$'] = true;
        SUB_DELIMS['&'] = true;
        SUB_DELIMS['\''] = true;
        SUB_DELIMS['('] = true;
        SUB_DELIMS[')'] = true;
        SUB_DELIMS['*'] = true;
        SUB_DELIMS['+'] = true;
        SUB_DELIMS[','] = true;
        SUB_DELIMS[';'] = true;
        SUB_DELIMS['='] = true;
    }

    /**
     * reserved      = gen-delims / sub-delims
     */
    private final static boolean[] RESERVED = new boolean[128];

    static {
        for (int i = 0; i < 128; i++) {
            RESERVED[i] = GEN_DELIMS[i] || SUB_DELIMS[i];
        }
    }

    private final static boolean[] ALPHA = new boolean[128];
    static {
        for (int i = 'a'; i <= 'z'; ++i) {
            ALPHA[i] = true;
        }
        for (int i = 'A'; i <= 'Z'; ++i) {
            ALPHA[i] = true;
        }
    }

    private final static boolean[] DIGIT = new boolean[128];

    static {
        for (int i = '0'; i <= '9'; ++i) {
            DIGIT[i] = true;
        }
    }

    /**
     * unreserved    = ALPHA / DIGIT / "-" / "." / "_" / "~"
     */
    private final static boolean[] UNRESERVED = new boolean[128];
    static {
        for (int i = 0; i < 128; i++) {
            UNRESERVED[i] = ALPHA[i] || DIGIT[i];
        }
        UNRESERVED['-'] = true;
        UNRESERVED['.'] = true;
        UNRESERVED['_'] = true;
        UNRESERVED['~'] = true;
    }

    /**
     *  scheme      = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
     */
    private final static boolean[] SCHEME = new boolean[128];
    static {
        for(int i=0; i < 128; i++) {
            SCHEME[i] = ALPHA[i] || DIGIT[i];
        }
        SCHEME['+'] = true;
        SCHEME['-'] = true;
        SCHEME['.'] = true;
    }

    /**
     *  userinfo    = *( unreserved / pct-encoded / sub-delims / ":" )
     */
    private final static boolean[] USERINFO = new boolean[128];
    static {
        for(int i=0; i < 128; i++) {
            USERINFO[i] = UNRESERVED[i] || SUB_DELIMS[i];
        }
        USERINFO[':'] = true;
    }

    /**
     * host        = IP-literal / IPv4address / reg-name
     * reg-name    = *( unreserved / pct-encoded / sub-delims )
     */
    private final static boolean[] HOSTPORT = new boolean[128];
    static {
        for(int i=0; i < 128; i++) {
            HOSTPORT[i] = UNRESERVED[i] || SUB_DELIMS[i];
        }
        HOSTPORT[':'] = true;
    }

    /**
     * pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
     */
    private final static boolean[] PCHAR = new boolean[128];
    static {
        for(int i=0; i < 128; i++) {
            PCHAR[i] = UNRESERVED[i] || SUB_DELIMS[i];
        }
        PCHAR[':'] = true;
        PCHAR['@'] = true;
    }

    /**
     * query       = *( pchar / "/" / "?" )
     */
    private final static boolean[] QUERY = new boolean[128];
    static {
        System.arraycopy(PCHAR, 0, QUERY, 0, 128);
        QUERY['/'] = true;
        QUERY['?'] = true;
    }

    /**
     * used to encode individual name and value parts of query
     * =& are characters used to separate name and value pairs
     * + needs to be encoded to differentiate from encoded spaces
     */
    private final static boolean[] QUERY_PARAM = new boolean[128];
    static {
        System.arraycopy(QUERY, 0, QUERY_PARAM, 0, 128);
        QUERY_PARAM['='] = false;
        QUERY_PARAM['+'] = false;
        QUERY_PARAM['&'] = false;
    }

    /**
     * path          = path-abempty    ; begins with "/" or is empty
     / path-absolute   ; begins with "/" but not "//"
     / path-noscheme   ; begins with a non-colon segment
     / path-rootless   ; begins with a segment
     / path-empty      ; zero characters

     path-abempty  = *( "/" segment )
     path-absolute = "/" [ segment-nz *( "/" segment ) ]
     path-noscheme = segment-nz-nc *( "/" segment )
     path-rootless = segment-nz *( "/" segment )
     path-empty    = 0<pchar>

     segment       = *pchar
     segment-nz    = 1*pchar
     segment-nz-nc = 1*( unreserved / pct-encoded / sub-delims / "@" )
     ; non-zero-length segment without any colon ":"

     pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
     */
    private final static boolean[] PATH = new boolean[128];
    static {
        System.arraycopy(PCHAR, 0, PATH, 0, 128);
        PATH['/'] = true;
    }

    private final static char[] HEX = "0123456789ABCDEF".toCharArray();

    private UTF8UrlEncoder() {
    }

    public static String encode(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 16);
        appendEncoded(sb, input);
        return sb.toString();
    }

    public static StringBuilder encodeAppendScheme(StringBuilder sb, String scheme) {
        return appendEncoded(sb, scheme, SCHEME, false);
    }

    public static StringBuilder encodeAppendAuthority(StringBuilder sb, String authority) {
        final int atIndex = authority.lastIndexOf('@');
        if (atIndex <= 0) {
            return appendEncoded(sb, authority, HOSTPORT, false);
        }

        appendEncoded(sb, authority.substring(0, atIndex), USERINFO, false);
        sb.append('@');
        return appendEncoded(sb, authority.substring(atIndex+1, authority.length()), HOSTPORT, false);
    }

    public static StringBuilder encodeAppendQueryPart(StringBuilder sb, String queryPart) {
        return appendEncoded(sb, queryPart, QUERY, true);
    }

    public static StringBuilder encodeAppendQueryParamPart(StringBuilder sb, String queryPart) {
        return appendEncoded(sb, queryPart, QUERY_PARAM, true);
    }

    public static StringBuilder encodeAppendPath(StringBuilder sb, String path) {
        return appendEncoded(sb, path, PATH, false);
    }

    public static StringBuilder appendEncoded(StringBuilder sb, String input) {
        return appendEncoded(sb, input, UNRESERVED, UTF8UrlEncoder.encodeSpaceUsingPlus);
    }


    private static StringBuilder appendEncoded(StringBuilder sb, String input, final boolean[] safe, boolean encodeSpaceUsingPlus) {

        for (int c, i = 0, len = input.length(); i < len; i+= Character.charCount(c)) {
            c = input.codePointAt(i);
            if (c <= 127) {
                if (safe[c]) {
                    sb.append((char) c);
                } else {
                    appendSingleByteEncoded(sb, c, encodeSpaceUsingPlus);
                }
            } else {
                appendMultiByteEncoded(sb, c, encodeSpaceUsingPlus);
            }
        }
        return sb;
    }


    private static void appendSingleByteEncoded(StringBuilder sb, int value, boolean encodeSpaceUsingPlus) {

        if (encodeSpaceUsingPlus && value == 32) {
            sb.append('+');
            return;
        }

        sb.append('%');
        sb.append(HEX[value >> 4]);
        sb.append(HEX[value & 0xF]);
    }

    private static void appendMultiByteEncoded(StringBuilder sb, int value, boolean encodeSpaceUsingPlus) {
        if (value < 0x800) {
            appendSingleByteEncoded(sb, (0xc0 | (value >> 6)), encodeSpaceUsingPlus);
            appendSingleByteEncoded(sb, (0x80 | (value & 0x3f)), encodeSpaceUsingPlus);
        } else if (value < 0x10000) {
            appendSingleByteEncoded(sb, (0xe0 | (value >> 12)), encodeSpaceUsingPlus);
            appendSingleByteEncoded(sb, (0x80 | ((value >> 6) & 0x3f)), encodeSpaceUsingPlus);
            appendSingleByteEncoded(sb, (0x80 | (value & 0x3f)), encodeSpaceUsingPlus);
        } else {
            appendSingleByteEncoded(sb, (0xf0 | (value >> 18)), encodeSpaceUsingPlus);
            appendSingleByteEncoded(sb, (0x80 | (value >> 12) & 0x3f), encodeSpaceUsingPlus);
            appendSingleByteEncoded(sb, (0x80 | (value >> 6) & 0x3f), encodeSpaceUsingPlus);
            appendSingleByteEncoded(sb, (0x80 | (value & 0x3f)), encodeSpaceUsingPlus);
        }
    }

}
