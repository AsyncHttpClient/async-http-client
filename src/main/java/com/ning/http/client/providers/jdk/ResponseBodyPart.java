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
package com.ning.http.client.providers.jdk;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.HttpResponseBodyPart;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A callback class used when an HTTP response body is received.
 */
public class ResponseBodyPart extends HttpResponseBodyPart {

    private final AtomicReference<byte[]> bytes = new AtomicReference(null);
    private final byte[] chunk;

    public ResponseBodyPart(URI uri, byte[] chunk, AsyncHttpProvider<HttpURLConnection> provider) {
        super(uri, provider);
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
}