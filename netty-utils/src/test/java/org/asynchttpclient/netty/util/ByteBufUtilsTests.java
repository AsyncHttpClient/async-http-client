/*
 * Copyright (c) 2019 AsyncHttpClient Project. All rights reserved.
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
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ByteBufUtilsTests {

    public static final char[] EXPECTED = {};

    @Test
    public void testByteBuf2BytesEmptyByteBuf() {
        ByteBuf buf = Unpooled.buffer();

        try {
            assertArrayEquals(new byte[]{}, ByteBufUtils.byteBuf2Bytes(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    public void testByteBuf2BytesNotEmptyByteBuf() {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(new byte[]{'f', 'o', 'o'});

        try {
            assertArrayEquals(new byte[]{'f', 'o', 'o'}, ByteBufUtils.byteBuf2Bytes(byteBuf));
        } finally {
            byteBuf.release();
        }
    }

    @Test
    public void testByteBuf2String() {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(new byte[]{'f', 'o', 'o'});
        Charset charset = StandardCharsets.US_ASCII;

        try {
            assertEquals(ByteBufUtils.byteBuf2String(charset, byteBuf), "foo");
        } finally {
            byteBuf.release();
        }
    }

    @Test
    public void testByteBuf2StringWithByteBufArray() {
        ByteBuf byteBuf1 = Unpooled.wrappedBuffer(new byte[]{'f'});
        ByteBuf byteBuf2 = Unpooled.wrappedBuffer(new byte[]{'o', 'o'});

        try {
            assertEquals(ByteBufUtils.byteBuf2String(StandardCharsets.ISO_8859_1, byteBuf1, byteBuf2), "foo");
        } finally {
            byteBuf1.release();
            byteBuf2.release();
        }
    }

    @Test
    public void testByteBuf2Chars() {
        ByteBuf byteBuf1 = Unpooled.wrappedBuffer(new byte[]{});
        ByteBuf byteBuf2 = Unpooled.wrappedBuffer(new byte[]{'o'});

        try {
            assertArrayEquals(EXPECTED, ByteBufUtils.byteBuf2Chars(StandardCharsets.US_ASCII, byteBuf1));
            assertArrayEquals(EXPECTED, ByteBufUtils.byteBuf2Chars(StandardCharsets.ISO_8859_1, byteBuf1));
            assertArrayEquals(new char[]{'o'}, ByteBufUtils.byteBuf2Chars(StandardCharsets.ISO_8859_1, byteBuf2));
        } finally {
            byteBuf1.release();
            byteBuf2.release();
        }
    }

    @Test
    public void testByteBuf2CharsWithByteBufArray() {
        ByteBuf byteBuf1 = Unpooled.wrappedBuffer(new byte[]{'f', 'o'});
        ByteBuf byteBuf2 = Unpooled.wrappedBuffer(new byte[]{'%', '*'});

        try {
            assertArrayEquals(new char[]{'f', 'o', '%', '*'}, ByteBufUtils.byteBuf2Chars(StandardCharsets.US_ASCII, byteBuf1, byteBuf2));
            assertArrayEquals(new char[]{'f', 'o', '%', '*'}, ByteBufUtils.byteBuf2Chars(StandardCharsets.ISO_8859_1, byteBuf1, byteBuf2));
        } finally {
            byteBuf1.release();
            byteBuf2.release();
        }
    }

    @Test
    public void testByteBuf2CharsWithEmptyByteBufArray() {
        ByteBuf byteBuf1 = Unpooled.wrappedBuffer(new byte[]{});
        ByteBuf byteBuf2 = Unpooled.wrappedBuffer(new byte[]{'o'});

        try {
           assertArrayEquals(new char[]{'o'}, ByteBufUtils.byteBuf2Chars(StandardCharsets.ISO_8859_1, byteBuf1, byteBuf2));
        } finally {
            byteBuf1.release();
            byteBuf2.release();
        }
    }
}
