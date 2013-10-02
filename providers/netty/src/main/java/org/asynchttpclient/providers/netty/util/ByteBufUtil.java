/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.util;

import io.netty.buffer.ByteBuf;

import java.util.List;

public class ByteBufUtil {

    public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

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

    public static byte[] byteBufs2Bytes(List<ByteBuf> bufs) {

        if (bufs.isEmpty()) {
            return EMPTY_BYTE_ARRAY;

        } else if (bufs.size() == 1) {
            return byteBuf2Bytes(bufs.get(0));

        } else {
            int totalSize = 0;
            for (ByteBuf buf : bufs) {
                totalSize += buf.readableBytes();
            }

            byte[] bytes = new byte[totalSize];
            int offset = 0;
            for (ByteBuf buf : bufs) {
                int readable = buf.readableBytes();
                buf.getBytes(buf.readerIndex(), bytes, offset, readable);
                offset += readable;
            }
            return bytes;
        }
    }
}
