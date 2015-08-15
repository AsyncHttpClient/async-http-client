/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package com.ning.http.client.async.netty;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.ning.http.client.Body;
import com.ning.http.client.providers.netty.request.body.FeedableBodyGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FeedableBodyGeneratorTest {

    private FeedableBodyGenerator feedableBodyGenerator;
    private TestFeedListener listener;

    @BeforeMethod
    public void setUp() throws Exception {
        feedableBodyGenerator = new FeedableBodyGenerator();
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
    public void readingBytesReturnsFedContentWithEmptyLastBufferWhenChunkBoundariesEnabled() throws Exception {
        feedableBodyGenerator.writeChunkBoundaries();
        byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);
        feedableBodyGenerator.feed(ByteBuffer.wrap(content), false);
        feedableBodyGenerator.feed(ByteBuffer.allocate(0), true);
        Body body = feedableBodyGenerator.createBody();
        assertEquals(readFromBody(body), "7\r\nTest123\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(readFromBody(body), "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(body.read(ByteBuffer.allocate(1)), -1);

    }

    @Test(groups = "standalone")
    public void readingBytesReturnsFedContentWithFilledLastBufferWhenChunkBoundariesEnabled() throws Exception {
        feedableBodyGenerator.writeChunkBoundaries();
        byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);
        feedableBodyGenerator.feed(ByteBuffer.wrap(content), true);
        Body body = feedableBodyGenerator.createBody();
        assertEquals(readFromBody(body), "7\r\nTest123\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(readFromBody(body), "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(body.read(ByteBuffer.allocate(1)), -1);

    }

    @Test(groups = "standalone")
    public void readingBytesReturnsFedContentWithoutChunkBoundariesWhenNotEnabled() throws Exception {
        byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);
        feedableBodyGenerator.feed(ByteBuffer.wrap(content), true);
        Body body = feedableBodyGenerator.createBody();
        assertEquals(readFromBody(body), "Test123".getBytes(StandardCharsets.US_ASCII));
        assertEquals(body.read(ByteBuffer.allocate(1)), -1);
    }

    private byte[] readFromBody(Body body) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(512);
        body.read(byteBuffer);
        byteBuffer.flip();
        byte[] readBytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(readBytes);
        return readBytes;
    }

    private static class TestFeedListener implements FeedableBodyGenerator.FeedListener {

        private int calls;

        @Override
        public void onContentAdded() {
            calls++;
        }

        public int getCalls() {
            return calls;
        }
    }
}
