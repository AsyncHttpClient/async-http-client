/*
 * Copyright (c) 2013-2014 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly.bodyhandler;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.Body;
import org.asynchttpclient.Request;
import org.asynchttpclient.multipart.MultipartBody;
import org.asynchttpclient.multipart.MultipartUtils;
import org.asynchttpclient.multipart.Part;
import org.asynchttpclient.providers.grizzly.FeedableBodyGenerator;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;

import java.io.IOException;
import java.util.List;

public final class PartsBodyHandler extends BodyHandler {

    // -------------------------------------------- Methods from BodyHandler

    public boolean handlesBodyType(final Request request) {
        return isNonEmpty(request.getParts());
    }

    public boolean doHandle(final FilterChainContext ctx, final Request request, final HttpRequestPacket requestPacket) throws IOException {

        final List<Part> parts = request.getParts();
        final MultipartBody multipartBody = MultipartUtils.newMultipartBody(parts, request.getHeaders());
        requestPacket.setContentLengthLong(multipartBody.getContentLength());
        requestPacket.setContentType(multipartBody.getContentType());
        if (GrizzlyAsyncHttpProvider.LOGGER.isDebugEnabled()) {
            GrizzlyAsyncHttpProvider.LOGGER.debug("REQUEST(modified): contentLength={}, contentType={}",
                    new Object[] { requestPacket.getContentLength(), requestPacket.getContentType() });
        }

        final FeedableBodyGenerator generator = new FeedableBodyGenerator() {
            @Override
            public Body createBody() throws IOException {
                return multipartBody;
            }
        };
        generator.setFeeder(new FeedableBodyGenerator.BaseFeeder(generator) {
            @Override
            public void flush() throws IOException {
                final Body bodyLocal = feedableBodyGenerator.createBody();
                try {
                    final MemoryManager mm = ctx.getMemoryManager();
                    boolean last = false;
                    while (!last) {
                        Buffer buffer = mm.allocate(BodyHandler.MAX_CHUNK_SIZE);
                        buffer.allowBufferDispose(true);
                        final long readBytes = bodyLocal.read(buffer.toByteBuffer());
                        if (readBytes > 0) {
                            buffer.position((int) readBytes);
                            buffer.trim();
                        } else {
                            buffer.dispose();
                            if (readBytes < 0) {
                                last = true;
                                buffer = Buffers.EMPTY_BUFFER;
                            } else {
                                throw new IllegalStateException("MultipartBody unexpectedly returned 0 bytes available");
                            }
                        }
                        feed(buffer, last);
                    }
                } finally {
                    if (bodyLocal != null) {
                        try {
                            bodyLocal.close();
                        } catch (IOException ignore) {
                        }
                    }
                }
            }
        });
        generator.initializeAsynchronousTransfer(ctx, requestPacket);
        return false;
    }
} // END PartsBodyHandler
