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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;

/**
 * Utility methods for string and byte conversions.
 * <p>
 * This class provides helper methods for converting between CharSequences, ByteBuffers,
 * and byte arrays, as well as encoding bytes to hexadecimal string representations.
 * </p>
 */
public final class StringUtils {

  private StringUtils() {
  }

  /**
   * Converts a CharSequence to a ByteBuffer using the specified charset.
   * <p>
   * This method encodes the character sequence into bytes according to the given charset.
   * </p>
   *
   * @param cs      the CharSequence to convert
   * @param charset the charset to use for encoding
   * @return a ByteBuffer containing the encoded bytes
   */
  public static ByteBuffer charSequence2ByteBuffer(CharSequence cs, Charset charset) {
    return charset.encode(CharBuffer.wrap(cs));
  }

  /**
   * Converts a ByteBuffer to a byte array.
   * <p>
   * This method extracts the remaining bytes from the ByteBuffer into a new byte array.
   * The ByteBuffer's position is advanced by the number of bytes read.
   * </p>
   *
   * @param bb the ByteBuffer to convert
   * @return a byte array containing the remaining bytes
   */
  public static byte[] byteBuffer2ByteArray(ByteBuffer bb) {
    byte[] rawBase = new byte[bb.remaining()];
    bb.get(rawBase);
    return rawBase;
  }

  /**
   * Converts a CharSequence to a byte array using the specified charset.
   * <p>
   * This is a convenience method that combines {@link #charSequence2ByteBuffer(CharSequence, Charset)}
   * and {@link #byteBuffer2ByteArray(ByteBuffer)}.
   * </p>
   *
   * @param sb      the CharSequence to convert
   * @param charset the charset to use for encoding
   * @return a byte array containing the encoded bytes
   */
  public static byte[] charSequence2Bytes(CharSequence sb, Charset charset) {
    ByteBuffer bb = charSequence2ByteBuffer(sb, charset);
    return byteBuffer2ByteArray(bb);
  }

  /**
   * Converts a byte array to a hexadecimal string representation.
   * <p>
   * Each byte is represented as two hexadecimal digits (lowercase).
   * </p>
   *
   * <p><b>Usage Examples:</b></p>
   * <pre>{@code
   * byte[] data = {0x1A, 0x2B, 0x3C};
   * String hex = toHexString(data); // Returns "1a2b3c"
   * }</pre>
   *
   * @param data the byte array to convert
   * @return a hexadecimal string representation
   */
  public static String toHexString(byte[] data) {
    StringBuilder buffer = StringBuilderPool.DEFAULT.stringBuilder();
    for (byte aData : data) {
      buffer.append(Integer.toHexString((aData & 0xf0) >>> 4));
      buffer.append(Integer.toHexString(aData & 0x0f));
    }
    return buffer.toString();
  }

  /**
   * Appends the base-16 (hexadecimal) representation of bytes to a StringBuilder.
   * <p>
   * Each byte is represented as two hexadecimal digits (lowercase). This method
   * modifies the provided StringBuilder in place.
   * </p>
   *
   * @param buf   the StringBuilder to append to
   * @param bytes the byte array to encode
   */
  public static void appendBase16(StringBuilder buf, byte[] bytes) {
    int base = 16;
    for (byte b : bytes) {
      int bi = 0xff & b;
      int c = '0' + (bi / base) % base;
      if (c > '9')
        c = 'a' + (c - '0' - 10);
      buf.append((char) c);
      c = '0' + bi % base;
      if (c > '9')
        c = 'a' + (c - '0' - 10);
      buf.append((char) c);
    }
  }
}
