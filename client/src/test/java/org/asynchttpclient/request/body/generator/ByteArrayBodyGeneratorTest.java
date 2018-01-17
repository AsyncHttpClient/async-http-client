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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.BodyState;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Random;

import static org.testng.Assert.assertEquals;

/**
 * @author Bryan Davis bpd@keynetics.com
 */
public class ByteArrayBodyGeneratorTest {

  private final Random random = new Random();
  private final int chunkSize = 1024 * 8;

  @Test
  public void testSingleRead() throws IOException {
    final int srcArraySize = chunkSize - 1;
    final byte[] srcArray = new byte[srcArraySize];
    random.nextBytes(srcArray);

    final ByteArrayBodyGenerator babGen = new ByteArrayBodyGenerator(srcArray);
    final Body body = babGen.createBody();

    final ByteBuf chunkBuffer = Unpooled.buffer(chunkSize);

    try {
      // should take 1 read to get through the srcArray
      body.transferTo(chunkBuffer);
      assertEquals(chunkBuffer.readableBytes(), srcArraySize, "bytes read");
      chunkBuffer.clear();

      assertEquals(body.transferTo(chunkBuffer), BodyState.STOP, "body at EOF");
    } finally {
      chunkBuffer.release();
    }
  }

  @Test
  public void testMultipleReads() throws IOException {
    final int srcArraySize = (3 * chunkSize) + 42;
    final byte[] srcArray = new byte[srcArraySize];
    random.nextBytes(srcArray);

    final ByteArrayBodyGenerator babGen = new ByteArrayBodyGenerator(srcArray);
    final Body body = babGen.createBody();

    final ByteBuf chunkBuffer = Unpooled.buffer(chunkSize);

    try {
      int reads = 0;
      int bytesRead = 0;
      while (body.transferTo(chunkBuffer) != BodyState.STOP) {
        reads += 1;
        bytesRead += chunkBuffer.readableBytes();
        chunkBuffer.clear();
      }
      assertEquals(reads, 4, "reads to drain generator");
      assertEquals(bytesRead, srcArraySize, "bytes read");
    } finally {
      chunkBuffer.release();
    }
  }
}
