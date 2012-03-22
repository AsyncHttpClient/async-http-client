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
package com.ning.http.client.generators;

import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A {@link BodyGenerator} backed by a byte array.
 */
public class ByteArrayBodyGenerator implements BodyGenerator {

    private final byte[] bytes;

    public ByteArrayBodyGenerator(byte[] bytes) {
        this.bytes = bytes;
    }

    protected final class ByteBody implements Body {
        private boolean eof = false;
        private int lastPosition = 0;

        public long getContentLength() {
            return bytes.length;
        }

        public long read(ByteBuffer byteBuffer) throws IOException {

            if (eof) {
                return -1;
            }

            final int remaining = bytes.length - lastPosition;
            if (remaining <= byteBuffer.capacity()) {
                byteBuffer.put(bytes, lastPosition, remaining);
                eof = true;
                return remaining;
            } else {
                byteBuffer.put(bytes, lastPosition, byteBuffer.capacity());
                lastPosition = lastPosition + byteBuffer.capacity();
                return byteBuffer.capacity();
            }
        }

        public void close() throws IOException {
            lastPosition = 0;
            eof = false;
        }
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public Body createBody() throws IOException {
        return new ByteBody();
    }
}
