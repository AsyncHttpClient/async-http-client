/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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

import org.asynchttpclient.Body;
import org.asynchttpclient.BodyGenerator;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.grizzly.FeedableBodyGenerator;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;

import java.io.IOException;

public final class BodyGeneratorBodyHandler implements BodyHandler {

    // -------------------------------------------- Methods from BodyHandler


    public boolean handlesBodyType(final Request request) {
        return (request.getBodyGenerator() != null);
    }

    @SuppressWarnings({"unchecked"})
    public boolean doHandle(final FilterChainContext ctx,
                         final Request request,
                         final HttpRequestPacket requestPacket)
    throws IOException {

        final BodyGenerator generator = request.getBodyGenerator();
        final Body bodyLocal = generator.createBody();
        final long len = bodyLocal.getContentLength();
        if (len >= 0) {
            requestPacket.setContentLengthLong(len);
        } else {
            requestPacket.setChunked(true);
        }

        final MemoryManager mm = ctx.getMemoryManager();
        boolean last = false;

        while (!last) {
            Buffer buffer = mm.allocate(MAX_CHUNK_SIZE);
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
                    // pass the context to bodyLocal to be able to
                    // continue body transferring once more data is available
                    if (generator instanceof FeedableBodyGenerator) {
                        ((FeedableBodyGenerator) generator).initializeAsynchronousTransfer(ctx, requestPacket);
                        return false;
                    } else {
                        throw new IllegalStateException("BodyGenerator unexpectedly returned 0 bytes available");
                    }
                }
            }

            final HttpContent content =
                    requestPacket.httpContentBuilder().content(buffer).
                            last(last).build();
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
        }

        return true;
    }

} // END BodyGeneratorBodyHandler
