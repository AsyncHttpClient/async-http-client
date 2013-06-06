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
import org.asynchttpclient.multipart.MultipartRequestEntity;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.BufferOutputStream;

import java.io.IOException;

import static org.asynchttpclient.util.MiscUtil.isNonEmpty;

public final class PartsBodyHandler implements BodyHandler {

    // -------------------------------------------- Methods from BodyHandler


    public boolean handlesBodyType(final Request request) {
        return isNonEmpty(request.getParts());
    }

    @SuppressWarnings({"unchecked"})
    public boolean doHandle(final FilterChainContext ctx,
                         final Request request,
                         final HttpRequestPacket requestPacket)
    throws IOException {

        MultipartRequestEntity mre =
                AsyncHttpProviderUtils.createMultipartRequestEntity(
                        request.getParts(),
                        request.getHeaders());
        requestPacket.setContentLengthLong(mre.getContentLength());
        requestPacket.setContentType(mre.getContentType());
        final MemoryManager mm = ctx.getMemoryManager();
        Buffer b = mm.allocate(512);
        BufferOutputStream o = new BufferOutputStream(mm, b, true);
        mre.writeRequest(o);
        b = o.getBuffer();
        b.trim();
        if (b.hasRemaining()) {
            final HttpContent content = requestPacket.httpContentBuilder().content(b).build();
            content.setLast(true);
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
        }

        return true;
    }

} // END PartsBodyHandler
