/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.providers.netty.response;

import static org.asynchttpclient.providers.netty.util.ByteBufUtils.*;

import io.netty.buffer.ByteBuf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A callback class used when an HTTP response body is received.
 * Bytes are eagerly fetched from the ByteBuf
 */
public class EagerNettyResponseBodyPart extends NettyResponseBodyPart {

    private final byte[] bytes;

    public EagerNettyResponseBodyPart(ByteBuf buf, boolean last) {
        super(last);
        bytes = byteBuf2Bytes(buf);
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
    public InputStream readBodyPartBytes() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public int length() {
        return bytes.length;
    }

    @Override
    public int writeTo(OutputStream outputStream) throws IOException {
        outputStream.write(bytes);
        return length();
    }

    @Override
    public ByteBuffer getBodyByteBuffer() {
        return ByteBuffer.wrap(bytes);
    }
}
