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
import io.netty.buffer.Unpooled;
import org.testng.annotations.Test;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.*;
import static org.testng.Assert.*;

public class Utf8ByteBufCharsetDecoderTest {

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

  @Test
  public void byteBufs2StringShouldBeAbleToDealWithBrokenCharsTheSameWayAsJavaImpl() throws Exception {
    String inputString = "foo 加特林岩石 bar";
    byte[] inputBytes = inputString.getBytes(UTF_8);

    int droppedBytes = 1;

    for (int i = 1; i < inputBytes.length - 1 - droppedBytes; i++) {
      byte[] part1 = Arrays.copyOfRange(inputBytes, 0, i);
      byte[] part2 = Arrays.copyOfRange(inputBytes, i + droppedBytes, inputBytes.length);
      byte[] merged = new byte[part1.length + part2.length];
      System.arraycopy(part1, 0, merged, 0, part1.length);
      System.arraycopy(part2, 0, merged, part1.length, part2.length);

      ByteBuf buf1 = Unpooled.wrappedBuffer(part1);
      ByteBuf buf2 = Unpooled.wrappedBuffer(part2);
      try {
        String s = ByteBufUtils.byteBuf2String(UTF_8, buf1, buf2);
        String javaString = new String(merged, UTF_8);
        assertNotEquals(s, inputString);
        assertEquals(s, javaString);
      } finally {
        buf1.release();
        buf2.release();
      }
    }
  }
}
