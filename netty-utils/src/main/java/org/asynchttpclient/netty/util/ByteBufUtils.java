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
package org.asynchttpclient.netty.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.netty.util.Utf8ByteBufCharsetDecoder.decodeUtf8;
import static org.asynchttpclient.netty.util.Utf8ByteBufCharsetDecoder.decodeUtf8Chars;

public final class ByteBufUtils {

    private static final char[] EMPTY_CHARS = new char[0];
    private static final ThreadLocal<CharBuffer> CHAR_BUFFERS = ThreadLocal.withInitial(() -> CharBuffer.allocate(1024));

    private ByteBufUtils() {
    }

    public static byte[] byteBuf2Bytes(ByteBuf buf) {
        int readable = buf.readableBytes();
        int readerIndex = buf.readerIndex();
        if (buf.hasArray()) {
            byte[] array = buf.array();
            if (buf.arrayOffset() == 0 && readerIndex == 0 && array.length == readable) {
                return array;
            }
        }
        byte[] array = new byte[readable];
        buf.getBytes(readerIndex, array);
        return array;
    }

    public static String byteBuf2String(Charset charset, ByteBuf buf) {
        return isUtf8OrUsAscii(charset) ? decodeUtf8(buf) : buf.toString(charset);
    }

    public static String byteBuf2String(Charset charset, ByteBuf... bufs) {
        return isUtf8OrUsAscii(charset) ? decodeUtf8(bufs) : byteBuf2String0(charset, bufs);
    }

    public static char[] byteBuf2Chars(Charset charset, ByteBuf buf) {
        return isUtf8OrUsAscii(charset) ? decodeUtf8Chars(buf) : decodeChars(buf, charset);
    }

    public static char[] byteBuf2Chars(Charset charset, ByteBuf... bufs) {
        return isUtf8OrUsAscii(charset) ? decodeUtf8Chars(bufs) : byteBuf2Chars0(charset, bufs);
    }

    private static boolean isUtf8OrUsAscii(Charset charset) {
        return charset.equals(UTF_8) || charset.equals(US_ASCII);
    }

    private static char[] decodeChars(ByteBuf src, Charset charset) {
        int readerIndex = src.readerIndex();
        int len = src.readableBytes();

        if (len == 0) {
            return EMPTY_CHARS;
        }
        final CharsetDecoder decoder = CharsetUtil.decoder(charset);
        final int maxLength = (int) ((double) len * decoder.maxCharsPerByte());
        CharBuffer dst = CHAR_BUFFERS.get();
        if (dst.length() < maxLength) {
            dst = CharBuffer.allocate(maxLength);
            CHAR_BUFFERS.set(dst);
        } else {
            dst.clear();
        }
        if (src.nioBufferCount() == 1) {
            // Use internalNioBuffer(...) to reduce object creation.
            decode(decoder, src.internalNioBuffer(readerIndex, len), dst);
        } else {
            // We use a heap buffer as CharsetDecoder is most likely able to use a fast-path if src and dst buffers
            // are both backed by a byte array.
            ByteBuf buffer = src.alloc().heapBuffer(len);
            try {
                buffer.writeBytes(src, readerIndex, len);
                // Use internalNioBuffer(...) to reduce object creation.
                decode(decoder, buffer.internalNioBuffer(buffer.readerIndex(), len), dst);
            } finally {
                // Release the temporary buffer again.
                buffer.release();
            }
        }
        dst.flip();
        return toCharArray(dst);
    }

    static String byteBuf2String0(Charset charset, ByteBuf... bufs) {
        if (bufs.length == 1) {
            return bufs[0].toString(charset);
        }
        ByteBuf composite = composite(bufs);
        try {
            return composite.toString(charset);
        } finally {
            composite.release();
        }
    }

    static char[] byteBuf2Chars0(Charset charset, ByteBuf... bufs) {
        if (bufs.length == 1) {
            return decodeChars(bufs[0], charset);
        }
        ByteBuf composite = composite(bufs);
        try {
            return decodeChars(composite, charset);
        } finally {
            composite.release();
        }
    }

    private static ByteBuf composite(ByteBuf[] bufs) {
        for (ByteBuf buf : bufs) {
            buf.retain();
        }
        return Unpooled.wrappedBuffer(bufs);
    }

    private static void decode(CharsetDecoder decoder, ByteBuffer src, CharBuffer dst) {
        try {
            CoderResult cr = decoder.decode(src, dst, true);
            if (!cr.isUnderflow()) {
                cr.throwException();
            }
            cr = decoder.flush(dst);
            if (!cr.isUnderflow()) {
                cr.throwException();
            }
        } catch (CharacterCodingException x) {
            throw new IllegalStateException(x);
        }
    }

    static char[] toCharArray(CharBuffer charBuffer) {
        char[] chars = new char[charBuffer.remaining()];
        charBuffer.get(chars);
        return chars;
    }
}
