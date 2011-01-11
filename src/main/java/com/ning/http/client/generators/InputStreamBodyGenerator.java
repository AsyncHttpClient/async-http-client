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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * A {@link BodyGenerator} which use an {@link InputStream} for reading bytes, without having to read the entire
 * stream in memory.
 *
 * NOTE: The {@link InputStream} must support the {@link InputStream#mark} and {@link java.io.InputStream#reset()} operation.
 * If not, mechanisms like authentication, redirect, or resumable download will not works.
 */
public class InputStreamBodyGenerator implements BodyGenerator {

    private final InputStream inputStream;
    private byte[] chunk;
    private boolean eof = false;
    private final static Logger logger = LoggerFactory.getLogger(InputStreamBodyGenerator.class);

    public InputStreamBodyGenerator(InputStream inputStream) {
        this.inputStream = inputStream;

        if (inputStream.markSupported()) {
            inputStream.mark(0);
        } else {
            logger.warn("inputStream.markSupported() not supported. Some features will not works");
        }
    }

    public Body createBody() throws IOException {
        return new ISBody();
    }

    protected class ISBody implements Body {

        public long getContentLength() {
            return -1;
        }

        public long read(ByteBuffer buffer) throws IOException {

            // To be safe.
            chunk = new byte[buffer.capacity() - 5];

            int read = -1;

            try {
                read = inputStream.read(chunk);
            } catch (IOException ex) {
                logger.warn("Unable to read", ex);
            }

            if (read == -1) {
                // Since we are chuncked, we must output extra bytes before considering the input stream closed.
                if (!eof) {
                    eof = true;
                    buffer.put("0".getBytes("UTF-8"));
                    buffer.put("\n".getBytes("UTF-8"));
                    return buffer.position();
                } else {
                    inputStream.reset();                    
                    eof = false;
                }
                return -1;
            }

            /**
             * Netty 3.2.3 doesn't support chunking encoding properly, so we chunk encoding ourself.
             */
            buffer.put(Integer.toHexString(read).getBytes("UTF-8"));
            buffer.put("\n".getBytes("UTF-8"));
            buffer.put(chunk, 0, read);
            return read;
        }

        public void close() throws IOException {
            inputStream.close();
        }
    }
}
