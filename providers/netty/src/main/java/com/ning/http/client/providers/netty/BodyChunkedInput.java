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
package com.ning.http.client.providers.netty;

import com.ning.http.client.Body;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.stream.ChunkedInput;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Adapts a {@link Body} to Netty's {@link ChunkedInput}.
 */
class BodyChunkedInput
        implements ChunkedInput {

    private final Body body;

    private final int chunkSize = 1024 * 8;

    private ByteBuffer nextChunk;

    private static final ByteBuffer EOF = ByteBuffer.allocate(0);

    public BodyChunkedInput(Body body) {
        if (body == null) {
            throw new IllegalArgumentException("no body specified");
        }
        this.body = body;
    }

    private ByteBuffer peekNextChuck()
            throws IOException {

        if (nextChunk == null) {
            ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
            if (body.read(buffer) < 0) {
                nextChunk = EOF;
            } else {
                buffer.flip();
                nextChunk = buffer;
            }
        }
        return nextChunk;
    }

    public boolean hasNextChunk()
            throws Exception {
        return !isEndOfInput();
    }

    public Object nextChunk()
            throws Exception {
        ByteBuffer buffer = peekNextChuck();
        if (buffer == EOF) {
            return null;
        }
        nextChunk = null;
        return ChannelBuffers.wrappedBuffer(buffer);
    }

    public boolean isEndOfInput()
            throws Exception {
        return peekNextChuck() == EOF;
    }

    public void close()
            throws Exception {
        body.close();
    }

}
