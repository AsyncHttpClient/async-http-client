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

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;

import java.nio.charset.CharacterCodingException;

public class Utf8ByteBufDecoder {

    private static final FastThreadLocal<Utf8ByteBufDecoder> DECODERS = new FastThreadLocal<Utf8ByteBufDecoder>() {
        protected Utf8ByteBufDecoder initialValue() {
            return new Utf8ByteBufDecoder();
        };
    };
    
    public static Utf8ByteBufDecoder getCachedDecoder() {
        Utf8ByteBufDecoder cached = DECODERS.get();
        cached.reset();
        return cached;
    }

    private static final byte[] TYPES = new byte[] {//
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,/**/
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,/**/
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,/**/
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,/**/
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,/**/
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,/**/
    8, 8, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,/**/
    10, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, 11, 6, 6, 6, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8 /**/
    };

    private static final byte[] STATES = new byte[] {//
    0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,/**/
    12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 24, 12, 12,/**/
    12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12,/**/
    12, 12, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12,/**/
    12, 36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12 //
    };

    private static final int UTF8_ACCEPT = 0;
    private static final int UTF8_REJECT = 12;

    private StringBuilder sb = new StringBuilder();
    private int state = UTF8_ACCEPT;
    private int codePoint = 0;

    private void write(byte b) throws CharacterCodingException {
        int t = TYPES[b & 0xFF];

        codePoint = state != UTF8_ACCEPT ? (b & 0x3f) | (codePoint << 6) : (0xff >> t) & b;
        state = STATES[state + t];

        if (state == UTF8_ACCEPT) {
            if (codePoint < Character.MIN_HIGH_SURROGATE) {
                sb.append((char) codePoint);
            } else {
                appendCodePointChars();
            }
        } else if (state == UTF8_REJECT) {
            throw new CharacterCodingException();
        }
    }

    private void appendCodePointChars() {
        if (Character.isBmpCodePoint(codePoint)) {
            sb.append((char) codePoint);

        } else if (Character.isValidCodePoint(codePoint)) {
            char charIndexPlus1 = Character.lowSurrogate(codePoint);
            char charIndex = Character.highSurrogate(codePoint);
            sb.append(charIndex).append(charIndexPlus1);

        } else {
            throw new IllegalArgumentException();
        }
    }

    public void reset() {
        sb.setLength(0);
        state = UTF8_ACCEPT;
        codePoint = 0;
    }

    public String decode(Iterable<ByteBuf> bufs) throws CharacterCodingException {

        for (ByteBuf buf : bufs) {
            buf.forEachByte(value -> {
                write(value);
                return true;
            });
        }

        if (state == UTF8_ACCEPT) {
            return sb.toString();
        } else {
            throw new CharacterCodingException();
        }
    }
}
