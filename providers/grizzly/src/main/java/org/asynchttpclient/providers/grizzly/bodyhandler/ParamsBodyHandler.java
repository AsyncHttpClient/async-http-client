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

import org.asynchttpclient.Param;
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
import java.net.URLEncoder;
import java.util.List;

public final class ParamsBodyHandler extends BodyHandler {

    private final boolean compressionEnabled;

    public ParamsBodyHandler(GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider) {
        compressionEnabled = grizzlyAsyncHttpProvider.getClientConfig().isCompressionEnabled();
    }

    // -------------------------------------------- Methods from BodyHandler

    public boolean handlesBodyType(final Request request) {
        final List<Param> params = request.getFormParams();
        return isNonEmpty(params);
    }

    @SuppressWarnings({ "unchecked" })
    public boolean doHandle(final FilterChainContext ctx, final Request request, final HttpRequestPacket requestPacket) throws IOException {

        if (requestPacket.getContentType() == null) {
            requestPacket.setContentType("application/x-www-form-urlencoded");
        }
        StringBuilder sb = null;
        String charset = request.getBodyEncoding();
        if (charset == null) {
            charset = Charsets.ASCII_CHARSET.name();
        }
        final List<Param> params = request.getFormParams();
        if (!params.isEmpty()) {
            if (sb == null) {
                sb = new StringBuilder(128);
            }
            for (Param param : params) {
                sb.append(URLEncoder.encode(param.getName(), charset)).append('=').append(URLEncoder.encode(param.getValue(), charset));
                sb.append('&');
            }
            sb.setLength(sb.length() - 1);
        }
        if (sb != null) {
            final byte[] data = sb.toString().getBytes(charset);
            final MemoryManager mm = ctx.getMemoryManager();
            final Buffer gBuffer = Buffers.wrap(mm, data);
            final HttpContent content = requestPacket.httpContentBuilder().content(gBuffer).build();
            if (requestPacket.getContentLength() == -1) {
                if (!compressionEnabled) {
                    requestPacket.setContentLengthLong(data.length);
                }
            }
            content.setLast(true);
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
        }
        return true;
    }
} // END ParamsBodyHandler
