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

import org.asynchttpclient.providers.grizzly.EventHandler;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.spdy.SpdyHandlerFilter;
import org.glassfish.grizzly.spdy.SpdyMode;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Extension of the {@link SpdyHandlerFilter} that is responsible for handling
 * events triggered by the parsing and serialization of HTTP packets.
 *
 * @since 2.0
 * @author The Grizzly Team
 */
public final class AsyncSpdyClientEventFilter extends SpdyHandlerFilter
        implements GrizzlyAsyncHttpProvider.Cleanup {


    private final EventHandler eventHandler;

    // -------------------------------------------------------- Constructors


    public AsyncSpdyClientEventFilter(final EventHandler eventHandler,
                                      SpdyMode mode,
                                      ExecutorService threadPool) {
        super(mode, threadPool);
        this.eventHandler = eventHandler;
    }

    @Override
    public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
        eventHandler.exceptionOccurred(ctx, error);
    }

    @Override
    protected void onHttpContentParsed(HttpContent content, FilterChainContext ctx) {
        eventHandler.onHttpContentParsed(content, ctx);
    }

    @Override
    protected void onHttpHeadersEncoded(HttpHeader httpHeader, FilterChainContext ctx) {
        eventHandler.onHttpHeadersEncoded(httpHeader, ctx);
    }

    @Override
    protected void onHttpContentEncoded(HttpContent content, FilterChainContext ctx) {
        eventHandler.onHttpContentEncoded(content, ctx);
    }

    @Override
    protected void onInitialLineParsed(HttpHeader httpHeader, FilterChainContext ctx) {
        eventHandler.onInitialLineParsed(httpHeader, ctx);
    }

    @Override
    protected void onHttpHeaderError(HttpHeader httpHeader, FilterChainContext ctx, Throwable t) throws IOException {
        eventHandler.onHttpHeaderError(httpHeader, ctx, t);
    }

    @Override
    protected void onHttpHeadersParsed(HttpHeader httpHeader, FilterChainContext ctx) {
        eventHandler.onHttpHeadersParsed(httpHeader, ctx);
    }

    @Override
    protected boolean onHttpPacketParsed(HttpHeader httpHeader, FilterChainContext ctx) {
        return eventHandler.onHttpPacketParsed(httpHeader, ctx);
    }

    @Override
    public void cleanup(FilterChainContext ctx) {

    }

}
