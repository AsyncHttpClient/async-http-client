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

import org.asynchttpclient.Request;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.Charsets;

import java.io.IOException;

public final class StringBodyHandler extends BodyHandler {
    private final GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider;

    public StringBodyHandler(GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider) {
        this.grizzlyAsyncHttpProvider = grizzlyAsyncHttpProvider;
    }

    // -------------------------------------------- Methods from BodyHandler

    public boolean handlesBodyType(final Request request) {
        return (request.getStringData() != null);
    }

    @SuppressWarnings({ "unchecked" })
    public boolean doHandle(final FilterChainContext ctx, final Request request, final HttpRequestPacket requestPacket) throws IOException {

        String charset = request.getBodyEncoding();
        if (charset == null) {
            charset = Charsets.ASCII_CHARSET.name();
        }
        final byte[] data = request.getStringData().getBytes(charset);
        final MemoryManager mm = ctx.getMemoryManager();
        final Buffer gBuffer = Buffers.wrap(mm, data);
        if (requestPacket.getContentLength() == -1) {
            if (!grizzlyAsyncHttpProvider.getClientConfig().isCompressionEnforced()) {
                requestPacket.setContentLengthLong(data.length);
            }
        }
        final HttpContent content = requestPacket.httpContentBuilder().content(gBuffer).build();
        content.setLast(true);
        ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
        return true;
    }
} // END StringBodyHandler
