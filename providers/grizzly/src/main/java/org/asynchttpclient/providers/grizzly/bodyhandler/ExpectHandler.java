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
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpRequestPacket;

import java.io.IOException;

public final class ExpectHandler implements BodyHandler {

    private final BodyHandler delegate;
    private Request request;
    private HttpRequestPacket requestPacket;

    // -------------------------------------------------------- Constructors


    public ExpectHandler(final BodyHandler delegate) {

        this.delegate = delegate;

    }


    // -------------------------------------------- Methods from BodyHandler


    public boolean handlesBodyType(Request request) {
        return delegate.handlesBodyType(request);
    }

    @SuppressWarnings({"unchecked"})
    public boolean doHandle(FilterChainContext ctx, Request request, HttpRequestPacket requestPacket) throws IOException {
        this.request = request;
        this.requestPacket = requestPacket;
        ctx.write(requestPacket, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
        return true;
    }

    public void finish(final FilterChainContext ctx) throws IOException {
        delegate.doHandle(ctx, request, requestPacket);
    }

} // END ContinueHandler
