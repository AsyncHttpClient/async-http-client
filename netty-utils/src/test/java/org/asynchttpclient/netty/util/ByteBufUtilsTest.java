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

import static java.nio.charset.StandardCharsets.*;
import static org.testng.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.testng.annotations.Test;

public class ByteBufUtilsTest {

    @Test
    public void testByteBuf2BytesHasBackingArray() {
        byte[] inputBytes = "testdata".getBytes(US_ASCII);
        ByteBuf buf = Unpooled.wrappedBuffer(inputBytes);
        try {
            byte[] output = ByteBufUtils.byteBuf2Bytes(buf);
            assertEquals(output, inputBytes);
        } finally {
            buf.release();
        }
    }

    @Test
    public void testByteBuf2BytesNoBackingArray() {
        byte[] inputBytes = "testdata".getBytes(US_ASCII);
        ByteBuf buf = Unpooled.directBuffer();
        try {
            buf.writeBytes(inputBytes);
            byte[] output = ByteBufUtils.byteBuf2Bytes(buf);
            assertEquals(output, inputBytes);
        } finally {
            buf.release();
        }
    }

    @Test
    public void byteBufs2StringShouldBeAbleToDealWithCharsWithVariableBytesLength() throws Exception {
        String inputString = "°ä–";
        byte[] inputBytes = inputString.getBytes(UTF_8);

        for (int i = 1; i < inputBytes.length - 1; i++) {
            ByteBuf buf1 = Unpooled.wrappedBuffer(inputBytes, 0, i);
            ByteBuf buf2 = Unpooled.wrappedBuffer(inputBytes, i, inputBytes.length - i);
            try {
                String s = ByteBufUtils.byteBuf2String(UTF_8, buf1, buf2);
                assertEquals(s, inputString);
            } finally {
                buf1.release();
                buf2.release();
            }
        }
    }
}
