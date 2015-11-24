/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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

import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;

import java.io.UTFDataFormatException;

public class Utf8Reader {

    private static int SMALL_BUFFER_SIZE = 4096;
    private static final IndexOutOfBoundsException STRING_DECODER_INDEX_OUT_OF_BOUNDS_EXCEPTION = new IndexOutOfBoundsException("String decoder index out of bounds");

    private static final FastThreadLocal<char[]> CACHED_CHAR_BUFFERS = new FastThreadLocal<char[]>() {
        @Override
        protected char[] initialValue() throws Exception {
            return new char[SMALL_BUFFER_SIZE];
        }
    };

    public static String readUtf8(ByteBuf buf, int utflen) throws UTFDataFormatException, IndexOutOfBoundsException {

        boolean small = utflen <= SMALL_BUFFER_SIZE;
        char[] chararr = small ? CACHED_CHAR_BUFFERS.get() : new char[utflen];

        int char1, char2, char3;
        int count = 0, chararr_count = 0;

        if (buf.readableBytes() > utflen) {
            throw STRING_DECODER_INDEX_OUT_OF_BOUNDS_EXCEPTION;
        }

        if (buf instanceof AbstractByteBuf) {
            AbstractByteBuf b = (AbstractByteBuf) buf;
            int readerIndex = buf.readerIndex();

            // fast-path
            while (count < utflen) {
                char1 = b.getByte(readerIndex + count) & 0xff;
                if (char1 > 127)
                    break;
                count++;
                chararr[chararr_count++] = (char) char1;
            }

            while (count < utflen) {
                char1 = b.getByte(readerIndex + count) & 0xff;
                switch (char1 >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    /* 0xxxxxxx */
                    count++;
                    chararr[chararr_count++] = (char) char1;
                    break;
                case 12:
                case 13:
                    /* 110x xxxx 10xx xxxx */
                    count += 2;
                    if (count > utflen)
                        throw new UTFDataFormatException("malformed input: partial character at end");
                    char2 = b.getByte(readerIndex + count - 1);
                    if ((char2 & 0xC0) != 0x80)
                        throw new UTFDataFormatException("malformed input around byte " + count);
                    chararr[chararr_count++] = (char) (((char1 & 0x1F) << 6) | (char2 & 0x3F));
                    break;
                case 14:
                    /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    count += 3;
                    if (count > utflen)
                        throw new UTFDataFormatException("malformed input: partial character at end");
                    char2 = b.getByte(readerIndex + count - 2);
                    char3 = b.getByte(readerIndex + count - 1);
                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw new UTFDataFormatException("malformed input around byte " + (count - 1));
                    chararr[chararr_count++] = (char) (((char1 & 0x0F) << 12) | ((char2 & 0x3F) << 6) | ((char3 & 0x3F) << 0));
                    break;
                default:
                    /* 10xx xxxx, 1111 xxxx */
                    throw new UTFDataFormatException("malformed input around byte " + count);
                }
            }

            buf.readerIndex(buf.readerIndex() + count);

            // The number of chars produced may be less than utflen
            return new String(chararr, 0, chararr_count);

        } else {
            byte[] b = new byte[utflen];
            buf.readBytes(b);

            return new String(b);
        }
    }
}
