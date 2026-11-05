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
package org.asynchttpclient.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.util.StringUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Encodes a {@code setBody(String)} request body to the {@link ByteBuf} the request is written with.
 *
 * {@link #charsetEncode} is the former path: {@code charset.encode(CharBuffer.wrap(body))}, which
 * sees a CharBuffer with no backing array and so runs the encoder's per-char buffer loop.
 * {@link #stringGetBytes} is the current path: {@code body.getBytes(charset)}, which uses the
 * String's own byte array.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StringBodyEncodeBenchmark {

    @Param({"256", "4096", "65536"})
    public int length;

    /**
     * ascii: 7-bit text; latin1: Latin-1 with accented letters; cyrillic: chars above U+00FF;
     * mixed: 7-bit text ending in one supplementary character, so the String is stored as UTF-16.
     */
    @Param({"ascii", "latin1", "cyrillic", "mixed"})
    public String text;

    private String body;

    @Setup(Level.Trial)
    public void setup() {
        String unit;
        switch (text) {
            case "ascii":
            case "mixed":
                unit = "{\"id\":12345,\"name\":\"value\"} ";
                break;
            case "latin1":
                unit = "caf\u00e9 cr\u00e8me br\u00fbl\u00e9e ";
                break;
            case "cyrillic":
                unit = "\u043f\u0440\u0438\u0432\u0435\u0442 \u043c\u0438\u0440 ";
                break;
            default:
                throw new IllegalArgumentException(text);
        }
        StringBuilder sb = new StringBuilder(length + unit.length());
        while (sb.length() < length) {
            sb.append(unit);
        }
        sb.setLength(length);
        if ("mixed".equals(text)) {
            sb.setLength(length - 2);
            sb.append("\ud83d\ude00");
        }
        body = sb.toString();
    }

    @Benchmark
    public ByteBuf charsetEncode() {
        return Unpooled.wrappedBuffer(StringUtils.charSequence2ByteBuffer(body, StandardCharsets.UTF_8));
    }

    @Benchmark
    public ByteBuf stringGetBytes() {
        return Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8));
    }
}
