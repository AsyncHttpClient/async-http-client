/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
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

package com.ning.http.client.providers.grizzly;

import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.HttpResponseBodyPart;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.http.HttpContent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProvider.ConnectionManager.*;

/**
 * {@link HttpResponseBodyPart} implementation using the Grizzly 2.0 HTTP client
 * codec.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyResponseBodyPart extends HttpResponseBodyPart {

    private final HttpContent content;
    private final Connection connection;
    private final AtomicReference<byte[]> contentBytes =
            new AtomicReference<byte[]>();


    // ------------------------------------------------------------ Constructors


    public GrizzlyResponseBodyPart(final HttpContent content,
                                   final URI uri,
                                   final Connection connection,
                                   final AsyncHttpProvider provider) {
        super(uri, provider);
        this.content = content;
        this.connection = connection;

    }


    // --------------------------------------- Methods from HttpResponseBodyPart


    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getBodyPartBytes() {

        byte[] bytes = contentBytes.get();
        if (bytes != null) {
            return bytes;
        }
        final Buffer b = content.getContent();
        final int origPos = b.position();
        bytes = new byte[b.remaining()];
        b.get(bytes);
        b.flip();
        b.position(origPos);
        contentBytes.compareAndSet(null, bytes);
        return bytes;

    }


    /**
     * {@inheritDoc}
     */
    @Override
    public int writeTo(OutputStream outputStream) throws IOException {

        final byte[] bytes = getBodyPartBytes();
        outputStream.write(getBodyPartBytes());
        return bytes.length;

    }


    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuffer getBodyByteBuffer() {

        return ByteBuffer.wrap(getBodyPartBytes());

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLast() {
        return content.isLast();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void markUnderlyingConnectionAsClosed() {
        markConnectionAsDoNotCache(connection);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean closeUnderlyingConnection() {
        return !isConnectionCacheable(connection);
    }


    // ----------------------------------------------- Package Protected Methods


    Buffer getBodyBuffer() {

        return content.getContent();

    }

}
