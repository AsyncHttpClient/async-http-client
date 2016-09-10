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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.testng.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.testng.annotations.Test;

public class ByteBufUtilsTest {

    @Test
    public void testByteBuf2BytesHasBackingArray() {
        byte[] inputBytes = "testdata".getBytes(US_ASCII);
        ByteBuf inputBuf = Unpooled.wrappedBuffer(inputBytes);
        byte[] output = ByteBufUtils.byteBuf2Bytes(inputBuf);
        assertEquals(output, inputBytes);
    }

    @Test
    public void testByteBuf2BytesNoBackingArray() {
        byte[] inputBytes = "testdata".getBytes(US_ASCII);
        ByteBuf inputBuf = Unpooled.directBuffer();
        inputBuf.writeBytes(inputBytes);
        byte[] output = ByteBufUtils.byteBuf2Bytes(inputBuf);
        assertEquals(output, inputBytes);
    }
}
