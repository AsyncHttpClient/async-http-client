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

    private static final ThreadLocal<StringBuilder> STRING_BUILDER_POOL = new ThreadLocal<StringBuilder>() {
        protected StringBuilder initialValue() {
            return new StringBuilder(512);
        }
    };

    /**
     * BEWARE: MUSN'T APPEND TO ITSELF!
     * @return a pooled StringBuilder
     */
    public static StringBuilder stringBuilder() {
        StringBuilder sb = STRING_BUILDER_POOL.get();
        sb.setLength(0);
        return sb;
    }
    
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
}
