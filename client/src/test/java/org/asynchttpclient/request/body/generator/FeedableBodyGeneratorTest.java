/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.BodyState;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertEquals;

public class FeedableBodyGeneratorTest {

  private UnboundedQueueFeedableBodyGenerator feedableBodyGenerator;
  private TestFeedListener listener;

  @BeforeMethod
  public void setUp() {
    feedableBodyGenerator = new UnboundedQueueFeedableBodyGenerator();
    listener = new TestFeedListener();
    feedableBodyGenerator.setListener(listener);
  }

  @Test
  public void feedNotifiesListener() throws Exception {
    feedableBodyGenerator.feed(Unpooled.EMPTY_BUFFER, false);
    feedableBodyGenerator.feed(Unpooled.EMPTY_BUFFER, true);
    assertEquals(listener.getCalls(), 2);
  }

  @Test
  public void readingBytesReturnsFedContentWithoutChunkBoundaries() throws Exception {
    byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);

    ByteBuf source = Unpooled.wrappedBuffer(content);
    ByteBuf target = Unpooled.buffer(1);

    try {
      feedableBodyGenerator.feed(source, true);
      Body body = feedableBodyGenerator.createBody();
      assertEquals(readFromBody(body), "Test123".getBytes(StandardCharsets.US_ASCII));
      assertEquals(body.transferTo(target), BodyState.STOP);
    } finally {
      source.release();
      target.release();
    }
  }

  @Test
  public void returnZeroToSuspendStreamWhenNothingIsInQueue() throws Exception {
    byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);

    ByteBuf source = Unpooled.wrappedBuffer(content);
    ByteBuf target = Unpooled.buffer(1);

    try {
      feedableBodyGenerator.feed(source, false);

      Body body = feedableBodyGenerator.createBody();
      assertEquals(readFromBody(body), "Test123".getBytes(StandardCharsets.US_ASCII));
      assertEquals(body.transferTo(target), BodyState.SUSPEND);
    } finally {
      source.release();
      target.release();
    }
  }

  private byte[] readFromBody(Body body) throws IOException {
    ByteBuf byteBuf = Unpooled.buffer(512);
    try {
      body.transferTo(byteBuf);
      byte[] readBytes = new byte[byteBuf.readableBytes()];
      byteBuf.readBytes(readBytes);
      return readBytes;
    } finally {
      byteBuf.release();
    }
  }

  private static class TestFeedListener implements FeedListener {

    private int calls;

    @Override
    public void onContentAdded() {
      calls++;
    }

    @Override
    public void onError(Throwable t) {
    }

    int getCalls() {
      return calls;
    }
  }
}
