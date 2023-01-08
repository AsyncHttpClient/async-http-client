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
package org.asynchttpclient.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.asynchttpclient.HttpResponseBodyPart;

import java.nio.ByteBuffer;

/**
 * A callback class used when an HTTP response body is received.
 * Bytes are eagerly fetched from the ByteBuf
 */
public class EagerResponseBodyPart extends HttpResponseBodyPart {

    private final byte[] bytes;

    public EagerResponseBodyPart(ByteBuf buf, boolean last) {
        super(last);
        bytes = ByteBufUtil.getBytes(buf);
    }

    /**
     * Return the response body's part bytes received.
     *
     * @return the response body's part bytes received.
     */
    @Override
    public byte[] getBodyPartBytes() {
        return bytes;
    }

    @Override
    public int length() {
        return bytes.length;
    }

    @Override
    public ByteBuffer getBodyByteBuffer() {
        return ByteBuffer.wrap(bytes);
    }
}
