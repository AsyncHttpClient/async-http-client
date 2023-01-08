/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart.part;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.request.body.multipart.ByteArrayPart;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

public class ByteArrayMultipartPart extends FileLikeMultipartPart<ByteArrayPart> {

    private final ByteBuf contentBuffer;

    public ByteArrayMultipartPart(ByteArrayPart part, byte[] boundary) {
        super(part, boundary);
        contentBuffer = Unpooled.wrappedBuffer(part.getBytes());
    }

    @Override
    protected long getContentLength() {
        return part.getBytes().length;
    }

    @Override
    protected long transferContentTo(ByteBuf target) {
        return transfer(contentBuffer, target, MultipartState.POST_CONTENT);
    }

    @Override
    protected long transferContentTo(WritableByteChannel target) throws IOException {
        return transfer(contentBuffer, target, MultipartState.POST_CONTENT);
    }

    @Override
    public void close() {
        super.close();
        contentBuffer.release();
    }
}
