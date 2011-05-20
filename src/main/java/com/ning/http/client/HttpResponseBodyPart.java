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
package com.ning.http.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;

/**
 * A callback class used when an HTTP response body is received.
 */
public abstract class HttpResponseBodyPart extends HttpContent {

    public HttpResponseBodyPart(URI uri, AsyncHttpProvider provider) {
        super(uri, provider);
    }

    /**
     * Return the response body's part bytes received.
     * @return the response body's part bytes received.
     */
    abstract public byte[] getBodyPartBytes();

    /**
     * Write the available bytes to the {@link java.io.OutputStream}
     * @param outputStream
     * @throws IOException
     * @return The number of bytes written
     */
    abstract public int writeTo(OutputStream outputStream) throws IOException;

    /**
     * Return a {@link ByteBuffer} that wraps the actual bytes read from the response's chunk. The {@link ByteBuffer}
     * capacity is equal to the number of bytes available.
     * 
     * @return {@link ByteBuffer}
     */
    abstract public ByteBuffer getBodyByteBuffer();
}
