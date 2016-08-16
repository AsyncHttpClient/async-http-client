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

import io.netty.buffer.ByteBuf;

import java.io.UTFDataFormatException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class ByteBufUtils {

    private ByteBufUtils() {
    }

    public static String byteBuf2Utf8String(ByteBuf buf) throws CharacterCodingException {
        return Utf8ByteBufDecoder.getCachedDecoder().decode(Collections.singleton(buf));
    }

    public static String byteBuf2UsAsciiString(ByteBuf buf) throws CharacterCodingException {
        return UsAsciiByteBufDecoder.getCachedDecoder().decode(Collections.singleton(buf));
    }

    public static String byteBuf2String(ByteBuf buf, Charset charset) throws UTFDataFormatException, IndexOutOfBoundsException, CharacterCodingException {

        if (charset.equals(StandardCharsets.US_ASCII)) {
            return byteBuf2UsAsciiString(buf);
        } else if (charset.equals(StandardCharsets.UTF_8)) {
            return byteBuf2Utf8String(buf);
        } else {
            return buf.toString(charset);
        }
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
