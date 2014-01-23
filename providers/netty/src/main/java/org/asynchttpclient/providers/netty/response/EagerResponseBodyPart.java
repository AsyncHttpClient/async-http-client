/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.response;

import io.netty.buffer.ByteBuf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.asynchttpclient.providers.netty.util.ByteBufUtil;

/**
 * A callback class used when an HTTP response body is received.
 * Bytes are eagerly fetched from the ByteBuf
 */
public class EagerResponseBodyPart extends NettyResponseBodyPart {

    private final byte[] bytes;

    public EagerResponseBodyPart(ByteBuf buf, boolean last) {
        super(last);
        bytes = ByteBufUtil.byteBuf2Bytes(buf);
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
