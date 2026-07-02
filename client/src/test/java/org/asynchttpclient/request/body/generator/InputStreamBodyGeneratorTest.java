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
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.BodyState;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link InputStreamBodyGenerator}'s {@link Body#transferTo(ByteBuf)}, which now reads straight from
 * the stream into the target buffer (no per-call staging {@code byte[]} and no extra copy). The whole stream
 * must still be transferred byte-for-byte, CONTINUE while data remains and STOP at EOF.
 */
public class InputStreamBodyGeneratorTest {

    private static final int CHUNK_SIZE = 1024 * 8;

    @Test
    public void streamsAllBytesAcrossMultipleReads() throws IOException {
        final byte[] src = new byte[3 * CHUNK_SIZE + 42];
        new Random().nextBytes(src);

        Body body = new InputStreamBodyGenerator(new ByteArrayInputStream(src)).createBody();
        ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try {
            BodyState state;
            while ((state = body.transferTo(chunkBuffer)) != BodyState.STOP) {
                assertEquals(BodyState.CONTINUE, state, "a stream with data left must report CONTINUE");
                byte[] b = new byte[chunkBuffer.readableBytes()];
                chunkBuffer.readBytes(b);
                collected.write(b);
                chunkBuffer.clear();
            }
            assertArrayEquals(src, collected.toByteArray(), "the whole stream must be transferred unchanged");
        } finally {
            chunkBuffer.release();
            body.close();
        }
    }

    @Test
    public void singleReadDrainsASmallStream() throws IOException {
        final byte[] src = new byte[CHUNK_SIZE - 100]; // fits under writableBytes - 10, so one read drains it
        new Random().nextBytes(src);

        Body body = new InputStreamBodyGenerator(new ByteArrayInputStream(src)).createBody();
        ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);
        try {
            assertEquals(BodyState.CONTINUE, body.transferTo(chunkBuffer));
            assertEquals(src.length, chunkBuffer.readableBytes(), "one read should drain a small stream");
            chunkBuffer.clear();
            assertEquals(BodyState.STOP, body.transferTo(chunkBuffer), "body at EOF");
        } finally {
            chunkBuffer.release();
            body.close();
        }
    }

    @Test
    public void emptyStreamStopsImmediately() throws IOException {
        Body body = new InputStreamBodyGenerator(new ByteArrayInputStream(new byte[0])).createBody();
        ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);
        try {
            assertEquals(BodyState.STOP, body.transferTo(chunkBuffer), "an empty stream must STOP immediately");
            assertEquals(0, chunkBuffer.readableBytes(), "nothing should be written for an empty stream");
        } finally {
            chunkBuffer.release();
            body.close();
        }
    }
}
