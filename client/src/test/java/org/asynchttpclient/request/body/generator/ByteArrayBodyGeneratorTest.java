/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.request.body.generator;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.BodyState;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Bryan Davis bpd@keynetics.com
 */
public class ByteArrayBodyGeneratorTest {

    private final Random random = new Random();
    private static final int CHUNK_SIZE = 1024 * 8;

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testSingleRead() throws IOException {
        final int srcArraySize = CHUNK_SIZE - 1;
        final byte[] srcArray = new byte[srcArraySize];
        random.nextBytes(srcArray);

        final ByteArrayBodyGenerator babGen = new ByteArrayBodyGenerator(srcArray);
        final Body body = babGen.createBody();

        final ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);

        try {
            // should take 1 read to get through the srcArray
            body.transferTo(chunkBuffer);
            assertEquals(srcArraySize, chunkBuffer.readableBytes(), "bytes read");
            chunkBuffer.clear();

            assertEquals(BodyState.STOP, body.transferTo(chunkBuffer), "body at EOF");
        } finally {
            chunkBuffer.release();
        }
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testMultipleReads() throws IOException {
        final int srcArraySize = 3 * CHUNK_SIZE + 42;
        final byte[] srcArray = new byte[srcArraySize];
        random.nextBytes(srcArray);

        final ByteArrayBodyGenerator babGen = new ByteArrayBodyGenerator(srcArray);
        final Body body = babGen.createBody();

        final ByteBuf chunkBuffer = Unpooled.buffer(CHUNK_SIZE);

        try {
            int reads = 0;
            int bytesRead = 0;
            while (body.transferTo(chunkBuffer) != BodyState.STOP) {
                reads += 1;
                bytesRead += chunkBuffer.readableBytes();
                chunkBuffer.clear();
            }
            assertEquals(4, reads, "reads to drain generator");
            assertEquals(srcArraySize, bytesRead, "bytes read");
        } finally {
            chunkBuffer.release();
        }
    }
}
