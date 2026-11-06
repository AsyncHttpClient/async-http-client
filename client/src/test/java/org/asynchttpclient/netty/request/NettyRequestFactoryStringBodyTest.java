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
package org.asynchttpclient.netty.request;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code setBody(String)} body must go on the wire as exactly the bytes
 * {@code charset.encode(CharBuffer.wrap(body))} produces, including the replacement of malformed
 * and unmappable input and the byte order mark of charsets that write one.
 */
public class NettyRequestFactoryStringBodyTest {

    private static final String[] BODIES = {
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

    private final NettyRequestFactory factory = new NettyRequestFactory(new DefaultAsyncHttpClientConfig.Builder().build());

    @Test
    public void bodyBytesMatchCharsetEncodeForEdgeCaseCharsets() {
        Charset[] charsets = {
                StandardCharsets.UTF_8,
                StandardCharsets.ISO_8859_1,
                StandardCharsets.US_ASCII,
                StandardCharsets.UTF_16,
                StandardCharsets.UTF_16BE,
                StandardCharsets.UTF_16LE,
                Charset.forName("UTF-32"),
        };
        for (Charset charset : charsets) {
            for (String body : BODIES) {
                assertWireBytes(body, charset);
            }
        }
    }

    @Test
    public void bodyBytesMatchCharsetEncodeForEveryEncodingCharset() {
        List<String> mismatches = new ArrayList<>();
        for (Charset charset : Charset.availableCharsets().values()) {
            if (!charset.canEncode()) {
                continue;
            }
            for (String body : BODIES) {
                byte[] expected = charsetEncode(body, charset);
                if (!Arrays.equals(expected, wireBytes(body, charset))) {
                    mismatches.add(charset.name() + " / " + escape(body));
                }
            }
        }
        assertTrue(mismatches.isEmpty(), "body bytes differ from Charset.encode for " + mismatches);
    }

    @Test
    public void utf16BodyKeepsByteOrderMarkAndReplacesLoneSurrogate() {
        // BOM FE FF, 'a', then the UTF-16 replacement FF FD for the unpaired high surrogate
        assertArrayEquals(new byte[]{(byte) 0xfe, (byte) 0xff, 0x00, 0x61, (byte) 0xff, (byte) 0xfd},
                wireBytes("a\ud800", StandardCharsets.UTF_16));
    }

    @Test
    public void unpairedSurrogatesBecomeQuestionMarks() {
        assertArrayEquals(new byte[]{'a', '?', 'b'}, wireBytes("a\ud800b", StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{'?', 'a'}, wireBytes("\udc00a", StandardCharsets.US_ASCII));
        // a valid pair is one unmappable character in a single byte charset
        assertArrayEquals(new byte[]{'x', '?', 'y'}, wireBytes("x\ud83d\ude00y", StandardCharsets.ISO_8859_1));
    }

    private void assertWireBytes(String body, Charset charset) {
        assertArrayEquals(charsetEncode(body, charset), wireBytes(body, charset),
                () -> charset.name() + " / " + escape(body));
    }

    private byte[] wireBytes(String body, Charset charset) {
        Request request = new RequestBuilder("POST").setUrl("http://localhost/").setBody(body).setCharset(charset).build();
        HttpRequest httpRequest = factory.newNettyRequest(request, false, null, null, null).getHttpRequest();
        ByteBuf content = assertInstanceOf(DefaultFullHttpRequest.class, httpRequest).content();
        byte[] bytes = ByteBufUtil.getBytes(content);
        assertEquals(String.valueOf(bytes.length), httpRequest.headers().get(CONTENT_LENGTH));
        return bytes;
    }

    private static byte[] charsetEncode(String body, Charset charset) {
        ByteBuffer bb = charset.encode(CharBuffer.wrap(body));
        byte[] bytes = new byte[bb.remaining()];
        bb.get(bytes);
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
