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
 *
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
    private boolean eof = false;
    private int lastPosition = 0;

    public ByteArrayBodyGenerator(byte[] bytes) {
        this.bytes = bytes;
    }

    protected final class ByteBody implements Body {

        public long getContentLength() {
            return bytes.length;
        }

        public long read(ByteBuffer byteBuffer) throws IOException {
            
            if (eof) {
                return -1;
            }

            if (bytes.length - lastPosition <= byteBuffer.capacity()) {
                byteBuffer.put(bytes, lastPosition, bytes.length);
                eof = true;
            } else {
                byteBuffer.put(bytes, lastPosition, byteBuffer.capacity());
                lastPosition = bytes.length - byteBuffer.capacity();
            }
            return bytes.length;
        }

        public void close() throws IOException {
            lastPosition = 0;
            eof = false;
        }
    }

    public Body createBody() throws IOException {
        return new ByteBody();
    }
}
