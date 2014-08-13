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

package org.asynchttpclient.providers.grizzly.filters;

import org.asynchttpclient.providers.grizzly.EventHandler;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;

import java.io.IOException;
import org.asynchttpclient.providers.grizzly.filters.events.GracefulCloseEvent;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpResponsePacket;

/**
 * Extension of the {@link HttpClientFilter} that is responsible for handling
 * events triggered by the parsing and serialization of HTTP packets.
 *
 * @since 2.0
 * @author The Grizzly Team
 */
public final class AsyncHttpClientEventFilter extends HttpClientFilter implements GrizzlyAsyncHttpProvider.Cleanup {

    private final EventHandler eventHandler;

    // -------------------------------------------------------- Constructors

    public AsyncHttpClientEventFilter(final EventHandler eventHandler) {
        this(eventHandler, DEFAULT_MAX_HTTP_PACKET_HEADER_SIZE);
    }

    public AsyncHttpClientEventFilter(final EventHandler eventHandler, final int maxHeaderSize) {

        super(maxHeaderSize);
        this.eventHandler = eventHandler;
    }

    @Override
    public NextAction handleEvent(final FilterChainContext ctx,
            final FilterChainEvent event) throws IOException {
        if (event.type() == GracefulCloseEvent.class) {
            // Connection was closed.
            // This event is fired only for responses, which don't have
            // associated transfer-encoding or content-length.
            // We have to complete such a request-response processing gracefully.
            final GracefulCloseEvent closeEvent = (GracefulCloseEvent) event;
            final HttpResponsePacket response = closeEvent.getHttpTxContext()
                    .getResponseStatus().getResponse();
            response.getProcessingState().getHttpContext().attach(ctx);
            
            onHttpPacketParsed(response, ctx);
            
            return ctx.getStopAction();
        }
        
        return ctx.getInvokeAction();
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
    protected void onHttpContentError(HttpHeader httpHeader, FilterChainContext ctx, Throwable t) throws IOException {
        eventHandler.onHttpContentError(httpHeader, ctx, t);
    }

    @Override
    protected void onHttpHeadersParsed(HttpHeader httpHeader, FilterChainContext ctx) {
        eventHandler.onHttpHeadersParsed(httpHeader, ctx);
    }

    @Override
    protected boolean onHttpHeaderParsed(final HttpHeader httpHeader,
            final Buffer buffer, final FilterChainContext ctx) {
        super.onHttpHeaderParsed(httpHeader, buffer, ctx);
        return eventHandler.onHttpHeaderParsed(httpHeader, buffer, ctx);
    }
    
    @Override
    protected boolean onHttpPacketParsed(HttpHeader httpHeader, FilterChainContext ctx) {
        return eventHandler.onHttpPacketParsed(httpHeader, ctx);
    }

    @Override
    public void cleanup(final FilterChainContext ctx) {
        clearResponse(ctx.getConnection());
    }
}
