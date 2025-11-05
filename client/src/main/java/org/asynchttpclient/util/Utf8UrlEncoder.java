/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.util;

import java.util.BitSet;

/**
 * UTF-8 URL encoder that follows RFC 3986 specifications.
 * <p>
 * This class provides methods to encode various URI components (path, query, form elements)
 * according to RFC 3986 and HTML5 form encoding specifications. It efficiently handles
 * UTF-8 multi-byte characters and uses predefined character sets to determine which
 * characters should be percent-encoded.
 * </p>
 * <p>
 * The encoder supports different encoding modes for different URI components:
 * </p>
 * <ul>
 *   <li>Path encoding - for URI paths</li>
 *   <li>Query encoding - for URI query strings</li>
 *   <li>Form encoding - for application/x-www-form-urlencoded data</li>
 * </ul>
 */
public final class Utf8UrlEncoder {

  // see http://tools.ietf.org/html/rfc3986#section-3.4
  // ALPHA / DIGIT / "-" / "." / "_" / "~"
  private static final BitSet RFC3986_UNRESERVED_CHARS = new BitSet();
  // gen-delims = ":" / "/" / "?" / "#" / "[" / "]" / "@"
  private static final BitSet RFC3986_GENDELIM_CHARS = new BitSet();
  // "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
  private static final BitSet RFC3986_SUBDELIM_CHARS = new BitSet();
  // gen-delims / sub-delims
  private static final BitSet RFC3986_RESERVED_CHARS = new BitSet();
  // unreserved / pct-encoded / sub-delims / ":" / "@"
  private static final BitSet RFC3986_PCHARS = new BitSet();
  private static final BitSet BUILT_PATH_UNTOUCHED_CHARS = new BitSet();
  private static final BitSet BUILT_QUERY_UNTOUCHED_CHARS = new BitSet();
  // http://www.w3.org/TR/html5/forms.html#application/x-www-form-urlencoded-encoding-algorithm
  private static final BitSet FORM_URL_ENCODED_SAFE_CHARS = new BitSet();
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  static {
    for (int i = 'a'; i <= 'z'; ++i) {
      RFC3986_UNRESERVED_CHARS.set(i);
    }
    for (int i = 'A'; i <= 'Z'; ++i) {
      RFC3986_UNRESERVED_CHARS.set(i);
    }
    for (int i = '0'; i <= '9'; ++i) {
      RFC3986_UNRESERVED_CHARS.set(i);
    }
    RFC3986_UNRESERVED_CHARS.set('-');
    RFC3986_UNRESERVED_CHARS.set('.');
    RFC3986_UNRESERVED_CHARS.set('_');
    RFC3986_UNRESERVED_CHARS.set('~');
  }

  static {
    RFC3986_GENDELIM_CHARS.set(':');
    RFC3986_GENDELIM_CHARS.set('/');
    RFC3986_GENDELIM_CHARS.set('?');
    RFC3986_GENDELIM_CHARS.set('#');
    RFC3986_GENDELIM_CHARS.set('[');
    RFC3986_GENDELIM_CHARS.set(']');
    RFC3986_GENDELIM_CHARS.set('@');
  }

  static {
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
  }

  static {
    RFC3986_RESERVED_CHARS.or(RFC3986_GENDELIM_CHARS);
    RFC3986_RESERVED_CHARS.or(RFC3986_SUBDELIM_CHARS);
  }

  static {
    RFC3986_PCHARS.or(RFC3986_UNRESERVED_CHARS);
    RFC3986_PCHARS.or(RFC3986_SUBDELIM_CHARS);
    RFC3986_PCHARS.set(':');
    RFC3986_PCHARS.set('@');
  }

  static {
    BUILT_PATH_UNTOUCHED_CHARS.or(RFC3986_PCHARS);
    BUILT_PATH_UNTOUCHED_CHARS.set('%');
    BUILT_PATH_UNTOUCHED_CHARS.set('/');
  }

  static {
    BUILT_QUERY_UNTOUCHED_CHARS.or(RFC3986_PCHARS);
    BUILT_QUERY_UNTOUCHED_CHARS.set('%');
    BUILT_QUERY_UNTOUCHED_CHARS.set('/');
    BUILT_QUERY_UNTOUCHED_CHARS.set('?');
  }

  static {
    for (int i = 'a'; i <= 'z'; ++i) {
      FORM_URL_ENCODED_SAFE_CHARS.set(i);
    }
    for (int i = 'A'; i <= 'Z'; ++i) {
      FORM_URL_ENCODED_SAFE_CHARS.set(i);
    }
    for (int i = '0'; i <= '9'; ++i) {
      FORM_URL_ENCODED_SAFE_CHARS.set(i);
    }

    FORM_URL_ENCODED_SAFE_CHARS.set('-');
    FORM_URL_ENCODED_SAFE_CHARS.set('.');
    FORM_URL_ENCODED_SAFE_CHARS.set('_');
    FORM_URL_ENCODED_SAFE_CHARS.set('*');
  }

  private Utf8UrlEncoder() {
  }

  /**
   * Encodes a URI path according to RFC 3986.
   * <p>
   * This method percent-encodes characters that are not allowed in URI paths,
   * while leaving valid path characters (including '/' separators) unencoded.
   * If no encoding is needed, returns the original input string.
   * </p>
   *
   * @param input the path to encode
   * @return the encoded path
   */
  public static String encodePath(String input) {
    StringBuilder sb = lazyAppendEncoded(null, input, BUILT_PATH_UNTOUCHED_CHARS, false);
    return sb == null ? input : sb.toString();
  }

  /**
   * Encodes and appends a query string to a StringBuilder.
   * <p>
   * This method encodes the query string according to RFC 3986, preserving
   * valid query characters including '?' and '&'.
   * </p>
   *
   * @param sb    the StringBuilder to append to
   * @param query the query string to encode
   * @return the StringBuilder with the encoded query appended
   */
  public static StringBuilder encodeAndAppendQuery(StringBuilder sb, String query) {
    return appendEncoded(sb, query, BUILT_QUERY_UNTOUCHED_CHARS, false);
  }

  /**
   * Encodes a query parameter element (name or value).
   * <p>
   * This method encodes the input according to form URL encoding rules,
   * percent-encoding special characters but not spaces (which are encoded as '+').
   * </p>
   *
   * @param input the query element to encode
   * @return the encoded query element
   */
  public static String encodeQueryElement(String input) {
    StringBuilder sb = new StringBuilder(input.length() + 6);
    encodeAndAppendQueryElement(sb, input);
    return sb.toString();
  }

  /**
   * Encodes and appends a query parameter element to a StringBuilder.
   *
   * @param sb    the StringBuilder to append to
   * @param input the query element to encode
   * @return the StringBuilder with the encoded element appended
   */
  public static StringBuilder encodeAndAppendQueryElement(StringBuilder sb, CharSequence input) {
    return appendEncoded(sb, input, FORM_URL_ENCODED_SAFE_CHARS, false);
  }

  /**
   * Encodes and appends a form parameter element to a StringBuilder.
   * <p>
   * This method uses HTML5 form encoding rules where spaces are encoded as '+'.
   * </p>
   *
   * @param sb    the StringBuilder to append to
   * @param input the form element to encode
   * @return the StringBuilder with the encoded element appended
   */
  public static StringBuilder encodeAndAppendFormElement(StringBuilder sb, CharSequence input) {
    return appendEncoded(sb, input, FORM_URL_ENCODED_SAFE_CHARS, true);
  }

  /**
   * Percent-encodes a query element using only RFC 3986 unreserved characters.
   * <p>
   * This is a stricter encoding that encodes all characters except alphanumerics
   * and the unreserved set (- . _ ~).
   * </p>
   *
   * @param input the string to encode
   * @return the percent-encoded string, or null if input is null
   */
  public static String percentEncodeQueryElement(String input) {
    if (input == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(input.length() + 6);
    encodeAndAppendPercentEncoded(sb, input);
    return sb.toString();
  }

  /**
   * Encodes and appends a percent-encoded string to a StringBuilder.
   *
   * @param sb    the StringBuilder to append to
   * @param input the string to encode
   * @return the StringBuilder with the encoded string appended
   */
  public static StringBuilder encodeAndAppendPercentEncoded(StringBuilder sb, CharSequence input) {
    return appendEncoded(sb, input, RFC3986_UNRESERVED_CHARS, false);
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
    for (int i = 0; i < input.length(); i += Character.charCount(c)) {
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
    for (int i = 0; i < input.length(); i += Character.charCount(c)) {
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

  private static void appendSingleByteEncoded(StringBuilder sb, int value, boolean encodeSpaceAsPlus) {

    if (value == ' ' && encodeSpaceAsPlus) {
      sb.append('+');
      return;
    }

    sb.append('%');
    sb.append(HEX[value >> 4]);
    sb.append(HEX[value & 0xF]);
  }

  private static void appendMultiByteEncoded(StringBuilder sb, int value) {
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
