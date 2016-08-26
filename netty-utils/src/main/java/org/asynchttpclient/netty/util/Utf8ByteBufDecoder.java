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
package org.asynchttpclient.netty.util;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;

import java.nio.charset.CharacterCodingException;

public class Utf8ByteBufDecoder extends Utf8Decoder {

    private static final FastThreadLocal<Utf8ByteBufDecoder> POOL = new FastThreadLocal<Utf8ByteBufDecoder>() {
        protected Utf8ByteBufDecoder initialValue() {
            return new Utf8ByteBufDecoder();
        };
    };

    public static Utf8ByteBufDecoder pooled() {
        Utf8ByteBufDecoder decoder = POOL.get();
        decoder.reset();
        return decoder;
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
