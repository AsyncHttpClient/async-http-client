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
package com.ning.http.client.providers.jdk;

import com.ning.http.client.HttpResponseBodyPart;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A callback class used when an HTTP response body is received.
 */
public class ResponseBodyPart extends HttpResponseBodyPart {

    private final byte[] chunk;

    public ResponseBodyPart(byte[] chunk, boolean last) {
        super(last);
        this.chunk = chunk;
    }

    /**
     * Return the response body's part bytes received.
     *
     * @return the response body's part bytes received.
     */
    public byte[] getBodyPartBytes() {
        return chunk;
    }

    @Override
    public int writeTo(OutputStream outputStream) throws IOException {
        outputStream.write(chunk);
        return chunk.length;
    }

    @Override
    public ByteBuffer getBodyByteBuffer() {
        return ByteBuffer.wrap(chunk);
    }

    @Override
    public int length() {
        return chunk != null? chunk.length: 0;
    }
}
