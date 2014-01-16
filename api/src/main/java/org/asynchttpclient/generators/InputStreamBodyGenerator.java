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

package org.asynchttpclient.generators;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.asynchttpclient.Body;
import org.asynchttpclient.BodyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BodyGenerator} which use an {@link InputStream} for reading bytes, without having to read the entire stream in memory.
 * <p/>
 * NOTE: The {@link InputStream} must support the {@link InputStream#mark} and {@link java.io.InputStream#reset()} operation. If not, mechanisms like authentication, redirect, or
 * resumable download will not works.
 */
public class InputStreamBodyGenerator implements BodyGenerator {

    private final InputStream inputStream;

    public InputStreamBodyGenerator(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Body createBody() throws IOException {
        return new InputStreamBody(inputStream);
    }

    private static class InputStreamBody implements Body {

        private static final Logger LOGGER = LoggerFactory.getLogger(InputStreamBody.class);

        private final InputStream inputStream;
        private byte[] chunk;

        private InputStreamBody(InputStream inputStream) {
            this.inputStream = inputStream;
            if (inputStream.markSupported()) {
                inputStream.mark(0);
            } else {
                LOGGER.info("inputStream.markSupported() not supported. Some features will not work.");
            }
        }

        public long getContentLength() {
            return -1L;
        }

        public long read(ByteBuffer buffer) throws IOException {

            // To be safe.
            chunk = new byte[buffer.remaining() - 10];

            int read = -1;
            try {
                read = inputStream.read(chunk);
            } catch (IOException ex) {
                LOGGER.warn("Unable to read", ex);
            }

            if (read > 0) {
                buffer.put(chunk, 0, read);
            } else {
                if (inputStream.markSupported()) {
                    inputStream.reset();
                }
            }
            return read;
        }

        public void close() throws IOException {
            inputStream.close();
        }
    }
}
