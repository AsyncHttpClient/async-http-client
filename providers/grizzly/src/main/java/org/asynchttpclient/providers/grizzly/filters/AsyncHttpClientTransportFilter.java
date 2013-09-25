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

package org.asynchttpclient.providers.grizzly.filters;

import org.asynchttpclient.providers.grizzly.HttpTxContext;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;

import java.io.EOFException;
import java.io.IOException;

/**
 * Custom {@link TransportFilter} implementation to capture and handle low-level
 * exceptions.
 *
 * @since 1.7
 * @author The Grizzly Team
 */
public final class AsyncHttpClientTransportFilter extends TransportFilter {


    // ----------------------------------------------------- Methods from Filter


    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        final HttpTxContext context =
                HttpTxContext.get(ctx.getConnection());
        if (context == null) {
            return super.handleRead(ctx);
        }
        ctx.getTransportContext().setCompletionHandler(new CompletionHandler() {
            @Override
            public void cancelled() {

            }

            @Override
            public void failed(Throwable throwable) {
                if (throwable instanceof EOFException) {
                    context.abort(new IOException("Remotely Closed"));
                }
            }

            @Override
            public void completed(Object result) {
            }

            @Override
            public void updated(Object result) {
            }
        });
        return super.handleRead(ctx);
    }

    @Override
    public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
        final HttpTxContext context = HttpTxContext.get(ctx.getConnection());
        if (context != null) {
            context.abort(error.getCause());
        }
    }

}
