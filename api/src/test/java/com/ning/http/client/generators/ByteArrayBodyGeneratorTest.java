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

package com.ning.http.client.generators;

import com.ning.http.client.Body;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * @author Bryan Davis bpd@keynetics.com
 */
public class ByteArrayBodyGeneratorTest {

    private final Random random = new Random();
    private final int chunkSize = 1024 * 8;

    @Test(groups = "standalone")
    public void testSingleRead() throws IOException {
        final int srcArraySize = chunkSize - 1;
        final byte[] srcArray = new byte[srcArraySize];
        random.nextBytes(srcArray);

        final ByteArrayBodyGenerator babGen =
            new ByteArrayBodyGenerator(srcArray);
        final Body body = babGen.createBody();

        final ByteBuffer chunkBuffer = ByteBuffer.allocate(chunkSize);

        // should take 1 read to get through the srcArray
        assertEquals(body.read(chunkBuffer), srcArraySize);
        assertEquals(chunkBuffer.position(), srcArraySize, "bytes read");
        chunkBuffer.clear();

        assertEquals(body.read(chunkBuffer), -1, "body at EOF");
    }

    @Test(groups = "standalone")
    public void testMultipleReads() throws IOException {
        final int srcArraySize = (3 * chunkSize) + 42;
        final byte[] srcArray = new byte[srcArraySize];
        random.nextBytes(srcArray);

        final ByteArrayBodyGenerator babGen =
            new ByteArrayBodyGenerator(srcArray);
        final Body body = babGen.createBody();

        final ByteBuffer chunkBuffer = ByteBuffer.allocate(chunkSize);

        int reads = 0;
        int bytesRead = 0;
        while (body.read(chunkBuffer) != -1) {
          reads += 1;
          bytesRead += chunkBuffer.position();
          chunkBuffer.clear();
        }
        assertEquals(reads, 4, "reads to drain generator");
        assertEquals(bytesRead, srcArraySize, "bytes read");
    }

}
