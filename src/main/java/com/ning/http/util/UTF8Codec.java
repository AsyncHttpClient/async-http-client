package com.ning.http.util;

import java.io.UnsupportedEncodingException;

/**
 * Wrapper class for more convenient (and possibly more efficient in future)
 * UTF-8 encoding and decoding.
 */
public class UTF8Codec
{
    private final static String ENCODING_UTF8 = "UTF-8";
    
    // When we move to JDK 1.6+, we can do this:
    /*
    import java.nio.charset.Charset;

    private final static Charset utf8;
    static {
        utf8 = Charset.forName("UTF-8");
    }

    public static byte[] toUTF8(String input) {
        return input.getBytes(utf8);
    }

    public static String fromUTF8(byte[] input) {
        return fromUTF8(input, 0, input.length);
    }
    
    public static String fromUTF8(byte[] input, int offset, int len) {
        return new String(input, offset, len, utf8);
    }
    */

    // But until then (with 1.5)
    public static byte[] toUTF8(String input) {
        try {
            return input.getBytes(ENCODING_UTF8);
        } catch (UnsupportedEncodingException e) { // never happens, but since it's declared...
            throw new IllegalStateException();
        }
    }

    public static String fromUTF8(byte[] input) {
        return fromUTF8(input, 0, input.length);
    }
    
    public static String fromUTF8(byte[] input, int offset, int len) {
        try {
            return new String(input, offset, len, ENCODING_UTF8);
        } catch (UnsupportedEncodingException e) { // never happens
            throw new IllegalStateException();
        }
    }
}
