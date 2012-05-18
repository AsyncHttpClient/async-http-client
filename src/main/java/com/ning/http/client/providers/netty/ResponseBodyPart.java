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
package com.ning.http.client.providers.netty;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.HttpResponseBodyPart;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A callback class used when an HTTP response body is received.
 */
public class ResponseBodyPart extends HttpResponseBodyPart {
    // Empty arrays are immutable, can freely reuse
    private final static byte[] NO_BYTES = new byte[0];

    private final HttpChunk chunk;
    private final HttpResponse response;
    private final AtomicReference<byte[]> bytes = new AtomicReference<byte[]>(null);
    private final boolean isLast;
    private boolean closeConnection = false;

    /**
     * Constructor used for non-chunked GET requests and HEAD requests.
     */
    public ResponseBodyPart(URI uri, HttpResponse response, AsyncHttpProvider provider, boolean last) {
        this(uri, response, provider, null, last);
    }

    public ResponseBodyPart(URI uri, HttpResponse response, AsyncHttpProvider provider, HttpChunk chunk, boolean last) {
        super(uri, provider);
        this.chunk = chunk;
        this.response = response;
        isLast = last;
    }
    
    /**
     * Return the response body's part bytes received.
     *
     * @return the response body's part bytes received.
     */
    @Override
    public byte[] getBodyPartBytes() {
        byte[] bp = bytes.get();
        if (bp != null) {
            return bp;
        }

        ChannelBuffer b = (chunk != null) ? chunk.getContent() : response.getContent();
        int available = b.readableBytes();

        final byte[] rb = (available == 0) ? NO_BYTES : new byte[available];
        b.getBytes(b.readerIndex(), rb, 0, available);
        return rb;
    }

    @Override
    public InputStream readBodyPartBytes() {
        return new ByteArrayInputStream(getBodyPartBytes());
    }

    @Override
    public int length() {
        ChannelBuffer b = (chunk != null) ? chunk.getContent() : response.getContent();
        return b.readableBytes();
    }
    
    @Override
    public int writeTo(OutputStream outputStream) throws IOException {
        ChannelBuffer b = (chunk != null) ? chunk.getContent() : response.getContent();
        int available = b.readableBytes();
        if (available > 0) {
            b.getBytes(b.readerIndex(), outputStream, available);
        }
        return available;
    }

    @Override
    public ByteBuffer getBodyByteBuffer() {
        return ByteBuffer.wrap(getBodyPartBytes());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLast() {
        return isLast;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void markUnderlyingConnectionAsClosed() {
        closeConnection = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean closeUnderlyingConnection() {
        return closeConnection;
    }

    protected HttpChunk chunk() {
        return chunk;
    }
}
