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

public final class StringUtils {

    private StringUtils() {
    }

    public static ByteBuffer charSequence2ByteBuffer(CharSequence cs, Charset charset) {
        return charset.encode(CharBuffer.wrap(cs));
    }

    public static byte[] byteBuffer2ByteArray(ByteBuffer bb) {
        byte[] rawBase = new byte[bb.remaining()];
        bb.get(rawBase);
        return rawBase;
    }

    public static byte[] charSequence2Bytes(CharSequence sb, Charset charset) {
        ByteBuffer bb = charSequence2ByteBuffer(sb, charset);
        return byteBuffer2ByteArray(bb);
    }

    public static String toHexString(byte[] data) {
        StringBuilder buffer = StringBuilderPool.DEFAULT.stringBuilder();
        for (byte aData : data) {
            buffer.append(Integer.toHexString((aData & 0xf0) >>> 4));
            buffer.append(Integer.toHexString(aData & 0x0f));
        }
        return buffer.toString();
    }

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
