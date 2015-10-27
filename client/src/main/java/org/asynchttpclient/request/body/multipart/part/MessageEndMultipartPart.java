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
package org.asynchttpclient.request.body.multipart.part;

import static org.asynchttpclient.request.body.multipart.Part.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

import org.asynchttpclient.request.body.multipart.FileLikePart;

public class MessageEndMultipartPart extends MultipartPart<FileLikePart> {

    private final ByteBuf buffer;

    public MessageEndMultipartPart(byte[] boundary) {
        super(null, boundary);
        buffer = ByteBufAllocator.DEFAULT.buffer((int) length());
        buffer.writeBytes(EXTRA_BYTES).writeBytes(boundary).writeBytes(EXTRA_BYTES).writeBytes(CRLF_BYTES);
        state = MultipartState.PRE_CONTENT;
    }

    @Override
    public long transferTo(ByteBuf target) throws IOException {
        return transfer(buffer, target, MultipartState.DONE);
    }

    @Override
    public long transferTo(WritableByteChannel target) throws IOException {
        slowTarget = false;
        return transfer(buffer, target, MultipartState.DONE);
    }

    @Override
    protected ByteBuf computePreContentBytes() {
        return Unpooled.EMPTY_BUFFER;
    }

    @Override
    protected ByteBuf computePostContentBytes() {
        return Unpooled.EMPTY_BUFFER;
    }

    @Override
    protected long getContentLength() {
        return EXTRA_BYTES.length + boundary.length + EXTRA_BYTES.length + CRLF_BYTES.length;
    }

    @Override
    protected long transferContentTo(ByteBuf target) throws IOException {
        throw new UnsupportedOperationException("Not supposed to be called");
    }

    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        throw new UnsupportedOperationException("Not supposed to be called");
    }

    @Override
    public void close() {
        buffer.release();
    }
}
