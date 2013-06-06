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

import org.asynchttpclient.Request;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.MemoryManager;

import java.io.IOException;
import java.io.InputStream;

import static org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider.LOGGER;

public final class StreamDataBodyHandler implements BodyHandler {


    // -------------------------------------------- Methods from BodyHandler


    public boolean handlesBodyType(final Request request) {
        return (request.getStreamData() != null);
    }

    @SuppressWarnings({"unchecked"})
    public boolean doHandle(final FilterChainContext ctx,
                         final Request request,
                         final HttpRequestPacket requestPacket)
    throws IOException {

        final MemoryManager mm = ctx.getMemoryManager();
        Buffer buffer = mm.allocate(512);
        final byte[] b = new byte[512];
        int read;
        final InputStream in = request.getStreamData();
        try {
            in.reset();
        } catch (IOException ioe) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(ioe.toString(), ioe);
            }
        }
        if (in.markSupported()) {
            in.mark(0);
        }

        while ((read = in.read(b)) != -1) {
            if (read > buffer.remaining()) {
                buffer = mm.reallocate(buffer, buffer.capacity() + 512);
            }
            buffer.put(b, 0, read);
        }
        buffer.trim();
        if (buffer.hasRemaining()) {
            final HttpContent content = requestPacket.httpContentBuilder().content(buffer).build();
            buffer.allowBufferDispose(false);
            content.setLast(true);
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
        }

        return true;
    }

} // END StreamDataBodyHandler
