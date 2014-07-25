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
package com.ning.http.client.providers.netty.response;

import static com.ning.http.client.providers.netty.util.ChannelBufferUtils.channelBuffer2bytes;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;

import com.ning.http.client.HttpResponseBodyPart;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A callback class used when an HTTP response body is received.
 */
public class NettyResponseBodyPart extends HttpResponseBodyPart {

    private final ChannelBuffer content;
    private volatile byte[] bytes;
    private final int length;

    public NettyResponseBodyPart(HttpResponse response, boolean last) {
        this(response, null, last);
    }

    public NettyResponseBodyPart(HttpResponse response, HttpChunk chunk, boolean last) {
        super(last);
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
}
