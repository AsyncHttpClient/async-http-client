/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.response;

import static org.asynchttpclient.netty.util.ChannelBufferUtils.channelBuffer2bytes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.asynchttpclient.response.HttpResponseBodyPart;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * A callback class used when an HTTP response body is received.
 */
public class NettyResponseBodyPart extends HttpResponseBodyPart {

    private final boolean last;
    private final ChannelBuffer content;
    private volatile byte[] bytes;
    private final int length;
    private boolean closeConnection;

    public NettyResponseBodyPart(HttpResponse response, boolean last) {
        this(response, null, last);
    }

    public NettyResponseBodyPart(HttpResponse response, HttpChunk chunk, boolean last) {
        this.last = last;
        content = chunk != null ? chunk.getContent() : response.getContent();
        length = content.readableBytes();
    }

    /**
     * Return the response body's part bytes received.
     *
     * @return the response body's part bytes received.
     */
    public byte[] getBodyPartBytes() {
        if (bytes == null)
            bytes = channelBuffer2bytes(content);
        return bytes;
    }

    public int writeTo(OutputStream outputStream) throws IOException {
        ChannelBuffer b = getChannelBuffer();
        int read = b.readableBytes();
        int index = b.readerIndex();
        if (read > 0) {
            b.readBytes(outputStream, read);
        }
        b.readerIndex(index);
        return read;
    }

    public ChannelBuffer getChannelBuffer() {
        return content;
    }

    @Override
    public ByteBuffer getBodyByteBuffer() {
        return content.toByteBuffer();
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public InputStream readBodyPartBytes() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public boolean isLast() {
        return last;
    }

    @Override
    public void markUnderlyingConnectionAsToBeClosed() {
        closeConnection = true;
    }

    @Override
    public boolean isUnderlyingConnectionToBeClosed() {
        return closeConnection;
    }
}
