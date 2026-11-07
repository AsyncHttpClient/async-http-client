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
package org.asynchttpclient.netty.request.body;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.asynchttpclient.request.body.Body;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BodyChunkedInputTest {

    @Test
    public void readChunkReleasesTheChunkWhenTheBodyFails() {
        IOException failure = new IOException("Stream closed");
        FailingBody body = new FailingBody(failure);
        BodyChunkedInput input = new BodyChunkedInput(body);

        IOException thrown = assertThrows(IOException.class, () -> input.readChunk(ByteBufAllocator.DEFAULT));

        assertSame(failure, thrown);
        assertNotNull(body.target);
        assertEquals(0, body.target.refCnt());
    }

    private static final class FailingBody implements Body {

        private final IOException failure;
        private ByteBuf target;

        FailingBody(IOException failure) {
            this.failure = failure;
        }

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public BodyState transferTo(ByteBuf target) throws IOException {
            this.target = target;
            // Part of a chunk is written before the source fails, as a multipart body does.
            target.writeByte('x');
            throw failure;
        }

        @Override
        public void close() {
        }
    }
}
