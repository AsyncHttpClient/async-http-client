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
package org.asynchttpclient.providers.netty_4;

import org.asynchttpclient.Body;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.stream.ChunkedInput;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Adapts a {@link Body} to Netty's {@link ChunkedInput}.
 */
class BodyChunkedInput
        implements ChunkedInput<ByteBuf> {

    private final Body body;

    private final int chunkSize = 1024 * 8;

    private ByteBuffer nextChunk;

    private static final ByteBuffer EOF = ByteBuffer.allocate(0);

    private boolean endOfInput = false;

    public BodyChunkedInput(Body body) {
        if (body == null) {
            throw new IllegalArgumentException("no body specified");
        }
        this.body = body;
    }

    private ByteBuffer peekNextChunk()
            throws IOException {

        if (nextChunk == null) {
            ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
            long length = body.read(buffer);
            if (length < 0) {
                // Negative means this is finished
                buffer.flip();
                nextChunk = buffer;
                endOfInput = true;
            } else if (length == 0) {
                // Zero means we didn't get anything this time, but may get next time
                buffer.flip();
                nextChunk = null;
            } else {
                buffer.flip();
                nextChunk = buffer;
            }
        }
        return nextChunk;
    }

    /**
     * Having no next chunk does not necessarily means end of input, other chunks may arrive later
     */
    public boolean hasNextChunk() throws Exception {
        return peekNextChunk() != null;
    }

    @Override
    public boolean readChunk(ByteBuf b) throws Exception {
        ByteBuffer buffer = peekNextChunk();
        if (buffer == null || buffer == EOF) {
            return false;
        }
        nextChunk = null;

        b.writeBytes(buffer);
        return true;
    }

    public boolean isEndOfInput() throws Exception {
        return endOfInput;
    }

    public void close() throws Exception {
        body.close();
    }

}
