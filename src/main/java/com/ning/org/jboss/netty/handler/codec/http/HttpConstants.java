package com.ning.org.jboss.netty.handler.codec.http;

import java.nio.charset.Charset;

public final class HttpConstants {

    /**
     * Horizontal space
     */
    public static final byte SP = 32;

    /**
     * Horizontal tab
     */
    public static final byte HT = 9;

    /**
     * Carriage return
     */
    public static final byte CR = 13;

    /**
     * Equals '='
     */
    public static final byte EQUALS = 61;

    /**
     * Line feed character
     */
    public static final byte LF = 10;

    /**
     * Colon ':'
     */
    public static final byte COLON = 58;

    /**
     * Semicolon ';'
     */
    public static final byte SEMICOLON = 59;

    /**
     * Comma ','
     */
    public static final byte COMMA = 44;

    /**
     * Double quote '"'
     */
    public static final byte DOUBLE_QUOTE = '"';

    /**
     * Default character set (UTF-8)
     */
    public static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    private HttpConstants() {
        // Unused
    }
}
