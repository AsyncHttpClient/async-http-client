/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StringUtils#charSequence2Bytes} and {@link StringUtils#charSequence2ByteBuffer} must return
 * exactly the bytes {@code charset.encode(CharBuffer.wrap(cs))} produces, including the replacement
 * of malformed and unmappable input and the byte order mark of charsets that write one.
 */
public class StringUtilsTest {

    private static final String[] INPUTS = {
            "",
            "hello",
            "caf\u00e9",
            "\u20ac 100",
            "\u4e2d\u6587",
            "\u0000\u007f\u0080\u00ff\u0100",
            // supplementary character (surrogate pair)
            "x\ud83d\ude00y",
            // lone high surrogate at the end, at the start and before another high surrogate
            "a\ud800",
            "\ud800a",
            "\ud800\ud800\udc00",
            // lone low surrogate, and a low surrogate followed by a high one
            "\udc00a",
            "a\udc00\ud800b",
            "\ud83d\ude00\ud800",
            // byte order mark and replacement character as content
            "\ufeffbom",
            "\ufffd",
    };

    @Test
    public void bytesMatchCharsetEncodeForEveryEncodingCharset() {
        List<String> mismatches = new ArrayList<>();
        for (Charset charset : Charset.availableCharsets().values()) {
            if (!charset.canEncode()) {
                continue;
            }
            for (String input : INPUTS) {
                for (CharSequence cs : sequences(input)) {
                    byte[] expected = charsetEncode(cs, charset);
                    if (!Arrays.equals(expected, StringUtils.charSequence2Bytes(cs, charset))
                            || !Arrays.equals(expected, remaining(StringUtils.charSequence2ByteBuffer(cs, charset)))) {
                        mismatches.add(charset.name() + " / " + cs.getClass().getSimpleName() + " / " + escape(input));
                    }
                }
            }
        }
        assertTrue(mismatches.isEmpty(), "bytes differ from Charset.encode for " + mismatches);
    }

    @Test
    public void unpairedSurrogatesBecomeQuestionMarks() {
        assertArrayEquals(new byte[]{'a', '?', 'b'}, StringUtils.charSequence2Bytes(new StringBuilder("a\ud800b"), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{'?', 'a'}, StringUtils.charSequence2Bytes(new StringBuilder("\udc00a"), StandardCharsets.US_ASCII));
        // a valid pair is one unmappable character in a single byte charset
        assertArrayEquals(new byte[]{'x', '?', 'y'},
                remaining(StringUtils.charSequence2ByteBuffer(new StringBuilder("x\ud83d\ude00y"), StandardCharsets.ISO_8859_1)));
    }

    private static List<CharSequence> sequences(String input) {
        // a recycled StringBuilder keeps a UTF-16 coder after it once held a char above U+00FF
        StringBuilder inflated = new StringBuilder("\u0100");
        inflated.setLength(0);
        inflated.append(input);
        return Arrays.asList(input, new StringBuilder(input), inflated, CharBuffer.wrap(input));
    }

    private static byte[] charsetEncode(CharSequence cs, Charset charset) {
        return remaining(charset.encode(CharBuffer.wrap(cs)));
    }

    private static byte[] remaining(ByteBuffer bb) {
        byte[] bytes = new byte[bb.remaining()];
        bb.duplicate().get(bytes);
        return bytes;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append(String.format("U+%04X ", (int) s.charAt(i)));
        }
        return sb.toString().trim();
    }
}
