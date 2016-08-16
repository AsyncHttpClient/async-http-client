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

public class UsAsciiByteBufDecoder {

    private static final FastThreadLocal<UsAsciiByteBufDecoder> DECODERS = new FastThreadLocal<UsAsciiByteBufDecoder>() {
        protected UsAsciiByteBufDecoder initialValue() {
            return new UsAsciiByteBufDecoder();
        };
    };

    public static UsAsciiByteBufDecoder getCachedDecoder() {
        UsAsciiByteBufDecoder cached = DECODERS.get();
        cached.reset();
        return cached;
    }

    private StringBuilder sb = new StringBuilder();

    public void reset() {
        sb.setLength(0);
    }

    public String decode(Iterable<ByteBuf> bufs) {
        for (ByteBuf buf : bufs) {
            buf.forEachByte(b -> {
                sb.append((char) b);
                return true;
            });
        }
        return sb.toString();
    }
}
