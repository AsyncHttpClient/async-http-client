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
package org.asynchttpclient.providers.netty4;

import org.asynchttpclient.Body;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedInput;

import java.nio.ByteBuffer;

/**
 * Adapts a {@link Body} to Netty's {@link ChunkedInput}.
 */
class BodyChunkedInput implements ChunkedInput<ByteBuf> {

    private static final int DEFAULT_CHUNK_SIZE = 8 * 1024;

    private final Body body;
    private final int contentLength;
    private final int chunkSize;

    private boolean endOfInput;

    public BodyChunkedInput(Body body) {
        if (body == null) {
            throw new IllegalArgumentException("no body specified");
        }
        this.body = body;
        contentLength = (int) body.getContentLength();
        if (contentLength <= 0)
            chunkSize = DEFAULT_CHUNK_SIZE;
        else
            chunkSize = Math.min(contentLength, DEFAULT_CHUNK_SIZE);
    }

    @Override
    public ByteBuf readChunk(ChannelHandlerContext ctx) throws Exception {
        if (endOfInput) {
            return null;
        } else {
            ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
            long r = body.read(buffer);
            if (r < 0L) {
                endOfInput = true;
                return null;
            } else {
                endOfInput = r == contentLength || r < chunkSize && contentLength > 0;
                buffer.flip();
                return Unpooled.wrappedBuffer(buffer);
            }
        }
    }

    @Override
    public boolean isEndOfInput() throws Exception {
        return endOfInput;
    }

    @Override
    public void close() throws Exception {
        body.close();
    }
}
