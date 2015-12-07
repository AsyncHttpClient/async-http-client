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

import static org.testng.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.BodyState;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class FeedableBodyGeneratorTest {

    private UnboundedQueueFeedableBodyGenerator feedableBodyGenerator;
    private TestFeedListener listener;

    @BeforeMethod
    public void setUp() throws Exception {
        feedableBodyGenerator = new UnboundedQueueFeedableBodyGenerator();
        listener = new TestFeedListener();
        feedableBodyGenerator.setListener(listener);
    }

    @Test(groups = "standalone")
    public void feedNotifiesListener() throws Exception {
        feedableBodyGenerator.feed(ByteBuffer.allocate(0), false);
        feedableBodyGenerator.feed(ByteBuffer.allocate(0), true);
        assertEquals(listener.getCalls(), 2);
    }

    @Test(groups = "standalone")
    public void readingBytesReturnsFedContentWithoutChunkBoundaries() throws Exception {
        byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);
        feedableBodyGenerator.feed(ByteBuffer.wrap(content), true);
        Body body = feedableBodyGenerator.createBody();
        assertEquals(readFromBody(body), "Test123".getBytes(StandardCharsets.US_ASCII));
        assertEquals(body.transferTo(Unpooled.buffer(1)), BodyState.STOP);
    }


    @Test(groups = "standalone")
    public void returnZeroToSuspendStreamWhenNothingIsInQueue() throws Exception {
        byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);
        feedableBodyGenerator.feed(ByteBuffer.wrap(content), false);

        Body body = feedableBodyGenerator.createBody();
        assertEquals(readFromBody(body), "Test123".getBytes(StandardCharsets.US_ASCII));
        assertEquals(body.transferTo(Unpooled.buffer(1)), BodyState.SUSPEND);
    }

    private byte[] readFromBody(Body body) throws IOException {
        ByteBuf byteBuf = Unpooled.buffer(512);
        body.transferTo(byteBuf);
        byte[] readBytes = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(readBytes);
        return readBytes;
    }

    private static class TestFeedListener implements FeedListener {

        private int calls;

        @Override
        public void onContentAdded() {
            calls++;
        }

        @Override
        public void onError(Throwable t) {}

        public int getCalls() {
            return calls;
        }
    }
}
