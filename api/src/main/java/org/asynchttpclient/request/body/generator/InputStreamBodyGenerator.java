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

package org.asynchttpclient.request.body.generator;

import org.asynchttpclient.request.body.Body;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * A {@link BodyGenerator} which use an {@link InputStream} for reading bytes, without having to read the entire stream in memory.
 * <p/>
 * NOTE: The {@link InputStream} must support the {@link InputStream#mark} and {@link java.io.InputStream#reset()} operation. If not, mechanisms like authentication, redirect, or
 * resumable download will not works.
 */
public final class InputStreamBodyGenerator implements BodyGenerator {

    private final static byte[] END_PADDING = "\r\n".getBytes();
    private final static byte[] ZERO = "0".getBytes();
    private static final Logger LOGGER = LoggerFactory.getLogger(InputStreamBody.class);
    private final InputStream inputStream;
    private boolean patchNetty3ChunkingIssue = false;

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

    private class InputStreamBody implements Body {

        private final InputStream inputStream;
        private boolean eof = false;
        private int endDataCount = 0;
        private byte[] chunk;

        private InputStreamBody(InputStream inputStream) {
            this.inputStream = inputStream;
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

            if (patchNetty3ChunkingIssue) {
                if (read == -1) {
                    // Since we are chunked, we must output extra bytes before considering the input stream closed.
                    // chunking requires to end the chunking:
                    // - A Terminating chunk of  "0\r\n".getBytes(),
                    // - Then a separate packet of "\r\n".getBytes()
                    if (!eof) {
                        endDataCount++;
                        if (endDataCount == 2)
                            eof = true;

                        if (endDataCount == 1)
                            buffer.put(ZERO);

                        buffer.put(END_PADDING);

                        return buffer.position();
                    } else {
                        eof = false;
                    }
                    return -1;
                }

                /**
                 * Netty 3.2.3 doesn't support chunking encoding properly, so we chunk encoding ourself.
                 */

                buffer.put(Integer.toHexString(read).getBytes());
                // Chunking is separated by "<bytesreads>\r\n"
                buffer.put(END_PADDING);
                buffer.put(chunk, 0, read);
                // Was missing the final chunk \r\n.
                buffer.put(END_PADDING);
            } else if (read > 0) {
                buffer.put(chunk, 0, read);
            }
            return read;
        }

        public void close() throws IOException {
            inputStream.close();
        }
    }

    /**
     * HACK: This is required because Netty has issues with chunking.
     *
     * @param patchNettyChunkingIssue
     */
    public void patchNetty3ChunkingIssue(boolean patchNetty3ChunkingIssue) {
        this.patchNetty3ChunkingIssue = patchNetty3ChunkingIssue;
    }
}
