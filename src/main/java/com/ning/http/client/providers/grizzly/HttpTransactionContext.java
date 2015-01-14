/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.providers.grizzly;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import com.ning.http.client.uri.Uri;
import com.ning.http.client.ws.WebSocket;
import com.ning.http.util.AsyncHttpProviderUtils;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.websockets.HandShake;
import org.glassfish.grizzly.websockets.ProtocolHandler;

/**
 *
 * @author Grizzly team
 */
final class HttpTransactionContext {
    private static final Attribute<HttpTransactionContext> REQUEST_STATE_ATTR =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(HttpTransactionContext.class.getName());

    final AtomicInteger redirectCount = new AtomicInteger(0);
    final int maxRedirectCount;
    final boolean redirectsAllowed;
    final GrizzlyAsyncHttpProvider provider;
    
    final Request request;
    Uri requestUri;
    final AsyncHandler handler;
    
    private final Connection connection;
    
    GrizzlyAsyncHttpProvider.BodyHandler bodyHandler;
    StatusHandler statusHandler;
    StatusHandler.InvocationStatus invocationStatus = StatusHandler.InvocationStatus.CONTINUE;
    
    GrizzlyResponseFuture future;
    HttpResponsePacket responsePacket;
    GrizzlyResponseStatus responseStatus;
    
    
    String lastRedirectURI;
    AtomicLong totalBodyWritten = new AtomicLong();
    AsyncHandler.STATE currentState;
    Uri wsRequestURI;
    boolean isWSRequest;
    HandShake handshake;
    ProtocolHandler protocolHandler;
    WebSocket webSocket;
    boolean establishingTunnel;
    private final CloseListener listener = new CloseListener<Closeable, CloseType>() {
        @Override
        public void onClosed(Closeable closeable, CloseType type) throws IOException {
            if (isGracefullyFinishResponseOnClose()) {
                // Connection was closed.
                // This event is fired only for responses, which don't have
                // associated transfer-encoding or content-length.
                // We have to complete such a request-response processing gracefully.
                final FilterChain fc = (FilterChain) connection.getProcessor();
                fc.fireEventUpstream(connection,
                        new GrizzlyAsyncHttpProvider.GracefulCloseEvent(HttpTransactionContext.this), null);
            } else if (CloseType.REMOTELY.equals(type)) {
                abort(AsyncHttpProviderUtils.REMOTELY_CLOSED_EXCEPTION);
            }
        }
    };

    // -------------------------------------------------------- Static methods
    private static void set(final Connection c,
            final HttpTransactionContext httpTxContext) {
        c.addCloseListener(httpTxContext.listener);
        REQUEST_STATE_ATTR.set(c, httpTxContext);
    }

    static HttpTransactionContext cleanupTransaction(final Connection c) {
        final HttpTransactionContext httpTxContext = REQUEST_STATE_ATTR.remove(c);
        c.removeCloseListener(httpTxContext.listener);
        return httpTxContext;
    }

    static HttpTransactionContext currentTransaction(final Connection c) {
        return REQUEST_STATE_ATTR.get(c);
    }
    
    public static HttpTransactionContext startTransaction(
            final Connection connection, final GrizzlyAsyncHttpProvider provider, final GrizzlyResponseFuture future, final Request request, final AsyncHandler handler) {
        final HttpTransactionContext ctx =
                new HttpTransactionContext(provider, connection, future,
                        request, handler);
        
        set(connection, ctx);
        
        return ctx;
    }
    
    // -------------------------------------------------------- Constructors

    private HttpTransactionContext(final GrizzlyAsyncHttpProvider provider,
            final Connection connection,
            final GrizzlyResponseFuture future, final Request request,
            final AsyncHandler handler) {
        
        this.provider = provider;
        this.connection = connection;
        this.future = future;
        this.request = request;
        this.handler = handler;
        redirectsAllowed = provider.getClientConfig().isFollowRedirect();
        maxRedirectCount = provider.getClientConfig().getMaxRedirects();
        this.requestUri = request.getUri();
    }

    // ----------------------------------------------------- Private Methods

    HttpTransactionContext cloneAndStartTransactionFor(
            final Connection connection) {
        final HttpTransactionContext newContext = startTransaction(
                connection, provider, future, request, handler);
        newContext.invocationStatus = invocationStatus;
        newContext.bodyHandler = bodyHandler;
        newContext.currentState = currentState;
        newContext.statusHandler = statusHandler;
        newContext.lastRedirectURI = lastRedirectURI;
        newContext.redirectCount.set(redirectCount.get());
        return newContext;
    }

    HttpTransactionContext cloneAndStartTransactionFor(
            final Connection connection,
            final Request request) {
        final HttpTransactionContext newContext = startTransaction(
                connection, provider, future, request, handler);
        newContext.invocationStatus = invocationStatus;
        newContext.bodyHandler = bodyHandler;
        newContext.currentState = currentState;
        newContext.statusHandler = statusHandler;
        newContext.lastRedirectURI = lastRedirectURI;
        newContext.redirectCount.set(redirectCount.get());
        return newContext;
    }

    boolean isGracefullyFinishResponseOnClose() {
        final HttpResponsePacket response = responsePacket;
        return response != null &&
                !response.getProcessingState().isKeepAlive() &&
                !response.isChunked() &&
                response.getContentLength() == -1;
    }

    void abort(final Throwable t) {
        if (future != null) {
            future.abort(t);
        }
    }

    void done() {
        done(null);
    }

    @SuppressWarnings(value = {"unchecked"})
    void done(Object result) {
        if (future != null) {
            future.done(result);
        }
    }

    boolean isTunnelEstablished(final Connection c) {
        return c.getAttributes().getAttribute("tunnel-established") != null;
    }

    void tunnelEstablished(final Connection c) {
        c.getAttributes().setAttribute("tunnel-established", Boolean.TRUE);
    }
    
} // END HttpTransactionContext
