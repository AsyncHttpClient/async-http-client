/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.BodyState;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FeedableBodyGeneratorTest {

    private UnboundedQueueFeedableBodyGenerator feedableBodyGenerator;
    private TestFeedListener listener;

    @BeforeEach
    public void setUp() {
        feedableBodyGenerator = new UnboundedQueueFeedableBodyGenerator();
        listener = new TestFeedListener();
        feedableBodyGenerator.setListener(listener);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void feedNotifiesListener() throws Exception {
        feedableBodyGenerator.feed(Unpooled.EMPTY_BUFFER, false);
        feedableBodyGenerator.feed(Unpooled.EMPTY_BUFFER, true);
        assertEquals(2, listener.getCalls());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void readingBytesReturnsFedContentWithoutChunkBoundaries() throws Exception {
        byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);

        ByteBuf source = Unpooled.wrappedBuffer(content);
        ByteBuf target = Unpooled.buffer(1);

        try {
            feedableBodyGenerator.feed(source, true);
            Body body = feedableBodyGenerator.createBody();
            assertArrayEquals("Test123".getBytes(StandardCharsets.US_ASCII), readFromBody(body));
            assertEquals(body.transferTo(target), BodyState.STOP);
        } finally {
            source.release();
            target.release();
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void returnZeroToSuspendStreamWhenNothingIsInQueue() throws Exception {
        byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);

        ByteBuf source = Unpooled.wrappedBuffer(content);
        ByteBuf target = Unpooled.buffer(1);

        try {
            feedableBodyGenerator.feed(source, false);

            Body body = feedableBodyGenerator.createBody();
            assertArrayEquals("Test123".getBytes(StandardCharsets.US_ASCII), readFromBody(body));
            assertEquals(body.transferTo(target), BodyState.SUSPEND);
        } finally {
            source.release();
            target.release();
        }
    }

    private static byte[] readFromBody(Body body) throws IOException {
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
