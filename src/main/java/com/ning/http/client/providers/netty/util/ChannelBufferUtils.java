/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package com.ning.http.client.providers.netty.util;

import org.jboss.netty.buffer.ChannelBuffer;

public class ChannelBufferUtils {

    public static byte[] channelBuffer2bytes(ChannelBuffer b) {
        int readable = b.readableBytes();
        int readerIndex = b.readerIndex();
        if (b.hasArray()) {
            byte[] array = b.array();
            if (b.arrayOffset() == 0 && readerIndex == 0 && array.length == readable) {
                return array;
            }
        }
        byte[] array = new byte[readable];
        b.getBytes(readerIndex, array);
        return array;
    }
}
