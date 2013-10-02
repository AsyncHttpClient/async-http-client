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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A callback class used when an HTTP response body is received.
 */
public class LazyResponseBodyPart extends ResponseBodyPart {

    private static final String ERROR_MESSAGE = "This implementation is intended for one to directly read from the underlying ByteBuf and release after usage. Not for the fainted heart!";

    private final ByteBuf buf;

    public LazyResponseBodyPart(ByteBuf buf, boolean last) {
        super(last);
        buf.retain();
        this.buf = buf;
    }

    public ByteBuf getBuf() {
        return buf;
    }

    /**
     * Return the response body's part bytes received.
     * 
     * @return the response body's part bytes received.
     */
    @Override
    public byte[] getBodyPartBytes() {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public InputStream readBodyPartBytes() {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public int length() {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public int writeTo(OutputStream outputStream) throws IOException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public ByteBuffer getBodyByteBuffer() {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
}
