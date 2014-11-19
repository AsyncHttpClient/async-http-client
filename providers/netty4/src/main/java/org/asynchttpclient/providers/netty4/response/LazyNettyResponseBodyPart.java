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
package org.asynchttpclient.providers.netty4.response;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A callback class used when an HTTP response body is received.
 */
public class LazyNettyResponseBodyPart extends NettyResponseBodyPart {

    private static final String ERROR_MESSAGE = "This implementation is intended for one to directly read from the underlying ByteBuf and release after usage. Not for the fainted heart!";

    private final ByteBuf buf;

    public LazyNettyResponseBodyPart(ByteBuf buf, boolean last) {
        super(last);
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
