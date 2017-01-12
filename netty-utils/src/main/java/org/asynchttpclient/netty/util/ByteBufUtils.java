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

import static java.nio.charset.StandardCharsets.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;

public final class ByteBufUtils {

    private ByteBufUtils() {
    }

    public static boolean isUtf8OrUsAscii(Charset charset) {
        return charset.equals(UTF_8) || charset.equals(US_ASCII);
    }

    public static String byteBuf2StringDefault(Charset charset, ByteBuf... bufs) {

        if (bufs.length == 1) {
            return bufs[0].toString(charset);
        }

        for (ByteBuf buf : bufs) {
            buf.retain();
        }

        ByteBuf composite = Unpooled.wrappedBuffer(bufs);

        try {
            return composite.toString(charset);
        } finally {
            composite.release();
        }
    }

    public static String byteBuf2String(Charset charset, ByteBuf buf) throws CharacterCodingException {
        return isUtf8OrUsAscii(charset) ? Utf8ByteBufCharsetDecoder.decodeUtf8(buf) : buf.toString(charset);
    }

    public static String byteBuf2String(Charset charset, ByteBuf... bufs) throws CharacterCodingException {
        return isUtf8OrUsAscii(charset) ? Utf8ByteBufCharsetDecoder.decodeUtf8(bufs) : byteBuf2StringDefault(charset, bufs);
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
}
