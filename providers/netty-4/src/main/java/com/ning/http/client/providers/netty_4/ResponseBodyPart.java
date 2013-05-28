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
package com.ning.http.client.providers.netty_4;

import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.HttpResponseBodyPart;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;

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

    private final HttpContent chunk;
    private final FullHttpResponse response;
    private final AtomicReference<byte[]> bytes = new AtomicReference<byte[]>(null);
    private final boolean isLast;
    private boolean closeConnection = false;

    /**
     * Constructor used for non-chunked GET requests and HEAD requests.
     */
    public ResponseBodyPart(URI uri, FullHttpResponse response, AsyncHttpProvider provider, boolean last) {
        this(uri, response, provider, null, last);
    }

    public ResponseBodyPart(URI uri, FullHttpResponse response, AsyncHttpProvider provider, HttpContent chunk, boolean last) {
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

        ByteBuf b = getChannelBuffer();
        byte[] rb = b.nioBuffer().array();
        bytes.set(rb);
        return rb;
    }

    @Override
    public InputStream readBodyPartBytes() {
        return new ByteArrayInputStream(getBodyPartBytes());
    }

    @Override
    public int length() {
        ByteBuf b = (chunk != null) ? chunk.data() : response.data();
        return b.readableBytes();
    }
    
    @Override
    public int writeTo(OutputStream outputStream) throws IOException {
        ByteBuf b = getChannelBuffer();
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

    public ByteBuf getChannelBuffer() {
        return chunk != null ? chunk.data() : response.data();
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

    protected HttpContent chunk() {
        return chunk;
    }
}
