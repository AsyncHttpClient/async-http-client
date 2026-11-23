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

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.request.body.Body;

import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link BodyGenerator} which use an {@link InputStream} for reading bytes, without having to read the entire stream in memory.
 * <br>
 * NOTE: The {@link InputStream} must support the {@link InputStream#mark} and {@link InputStream#reset()} operation. If not, mechanisms like authentication, redirect, or
 * resumable download will not work.
 */
public final class InputStreamBodyGenerator implements BodyGenerator {

    private final InputStream inputStream;
    private final long contentLength;

    public InputStreamBodyGenerator(InputStream inputStream) {
        this(inputStream, -1L);
    }

    public InputStreamBodyGenerator(InputStream inputStream, long contentLength) {
        this.inputStream = inputStream;
        this.contentLength = contentLength;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public long getContentLength() {
        return contentLength;
    }

    @Override
    public Body createBody() {
        return new InputStreamBody(inputStream, contentLength);
    }

    private static class InputStreamBody implements Body {

        private final InputStream inputStream;
        private final long contentLength;
        private long transferred;

        private InputStreamBody(InputStream inputStream, long contentLength) {
            this.inputStream = inputStream;
            this.contentLength = contentLength;
        }

        @Override
        public long getContentLength() {
            return contentLength;
        }

        @Override
        public BodyState transferTo(ByteBuf target) throws IOException {
            // Read straight from the stream into the target buffer instead of staging through a per-call byte[].
            // For heap target buffers this drops both the staging array and the copy; for direct buffers Netty
            // still stages through a temporary heap array internally (InputStream can only read into a byte[]),
            // so there the win is smaller.
            int length = target.writableBytes();
            if (contentLength >= 0) {
                // Bytes past the declared length would go out after the end of the body, and a read for the end of
                // a socket-backed stream would block until it ends.
                if (transferred == contentLength) {
                    return BodyState.STOP;
                }
                length = (int) Math.min(length, contentLength - transferred);
            }
            int read = target.writeBytes(inputStream, length);
            if (read > 0) {
                transferred += read;
                return BodyState.CONTINUE;
            }
            if (read < 0 && contentLength >= 0) {
                throw new IOException("InputStream body ended after " + transferred + " of its " + contentLength
                        + " declared bytes");
            }
            return BodyState.STOP;
        }

        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }
}

