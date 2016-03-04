/*
 * Copyright (c) 2012-2016 Sonatype, Inc. All rights reserved.
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

import com.ning.http.client.providers.grizzly.events.GracefulCloseEvent;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.uri.Uri;
import com.ning.http.client.ws.WebSocket;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.ProxyUtils;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeStorage;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.websockets.HandShake;
import org.glassfish.grizzly.websockets.ProtocolHandler;

/**
 *
 * @author Grizzly team
 */
public final class HttpTransactionContext {
    private static final Attribute<HttpTransactionContext> REQUEST_STATE_ATTR =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(HttpTransactionContext.class.getName());

    int redirectCount;
    final int maxRedirectCount;
    final boolean redirectsAllowed;
    final GrizzlyAsyncHttpProvider provider;
    final ProxyServer proxyServer;
        
    private final Request ahcRequest;
    Uri requestUri;
    
    private final Connection connection;
    
    PayloadGenerator payloadGenerator;
    
    StatusHandler statusHandler;
    // StatusHandler invocation status
    StatusHandler.InvocationStatus invocationStatus =
            StatusHandler.InvocationStatus.CONTINUE;
    
    GrizzlyResponseFuture future;
    HttpResponsePacket responsePacket;
    GrizzlyResponseStatus responseStatus;
    
    
    Uri lastRedirectUri;
    long totalBodyWritten;
    AsyncHandler.STATE currentState;
    Uri wsRequestURI;
    boolean isWSRequest;
    HandShake handshake;
    ProtocolHandler protocolHandler;
    WebSocket webSocket;
    boolean establishingTunnel;
    
    // don't recycle the context, don't return associated connection to
    // the pool
    boolean isReuseConnection;

    /**
     * <tt>true</tt> if the request is fully sent, or <tt>false</tt>otherwise.
     */
    volatile boolean isRequestFullySent;
    Set<CompletionHandler<HttpTransactionContext>> reqFullySentHandlers;
    
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
                        new GracefulCloseEvent(HttpTransactionContext.this), null);
            } else if (CloseType.REMOTELY.equals(type)) {
                abort(AsyncHttpProviderUtils.REMOTELY_CLOSED_EXCEPTION);
            } else {
                try {
                    closeable.assertOpen();
                } catch (IOException ioe) {
                    abort(ioe);
                }
            }
        }
    };

    // -------------------------------------------------------- Static methods
    static void bind(final HttpContext httpCtx,
            final HttpTransactionContext httpTxContext) {
        httpCtx.getCloseable().addCloseListener(httpTxContext.listener);
        REQUEST_STATE_ATTR.set(httpCtx, httpTxContext);
    }

    static void cleanupTransaction(final HttpContext httpCtx,
            final CompletionHandler<HttpTransactionContext> completionHandler) {
        final HttpTransactionContext httpTxContext = currentTransaction(httpCtx);
        
        assert httpTxContext != null;
        
        if (httpTxContext.isRequestFullySent) {
            cleanupTransaction(httpCtx, httpTxContext);
            completionHandler.completed(httpTxContext);
        } else {
            httpTxContext.addRequestSentCompletionHandler(completionHandler);
            if (httpTxContext.isRequestFullySent &&
                    httpTxContext.removeRequestSentCompletionHandler(completionHandler)) {
                completionHandler.completed(httpTxContext);
            }
        }
    }

    static void cleanupTransaction(final HttpContext httpCtx,
            final HttpTransactionContext httpTxContext) {
        httpCtx.getCloseable().removeCloseListener(httpTxContext.listener);
        REQUEST_STATE_ATTR.remove(httpCtx);
    }
    
    static HttpTransactionContext currentTransaction(
            final HttpHeader httpHeader) {
        return currentTransaction(httpHeader.getProcessingState().getHttpContext());
    }

    static HttpTransactionContext currentTransaction(final AttributeStorage storage) {
        return REQUEST_STATE_ATTR.get(storage);
    }

    static HttpTransactionContext currentTransaction(final HttpContext httpCtx) {
        return ((AhcHttpContext) httpCtx).getHttpTransactionContext();
    }
    
    static HttpTransactionContext startTransaction(
            final Connection connection, final GrizzlyAsyncHttpProvider provider,
            final Request request, final GrizzlyResponseFuture future) {
        return new HttpTransactionContext(provider, connection, future, request);
    }
    
    // -------------------------------------------------------- Constructors

    private HttpTransactionContext(final GrizzlyAsyncHttpProvider provider,
            final Connection connection,
            final GrizzlyResponseFuture future, final Request ahcRequest) {

        this.provider = provider;
        this.connection = connection;
        this.future = future;
        this.ahcRequest = ahcRequest;
        this.proxyServer = ProxyUtils.getProxyServer(
                provider.getClientConfig(), ahcRequest);
        redirectsAllowed = provider.getClientConfig().isFollowRedirect();
        maxRedirectCount = provider.getClientConfig().getMaxRedirects();
        this.requestUri = ahcRequest.getUri();
    }

    Connection getConnection() {
        return connection;
    }
    
    AsyncHandler getAsyncHandler() {
        return future.getAsyncHandler();
    }
    
    Request getAhcRequest() {
        return ahcRequest;
    }

    ProxyServer getProxyServer() {
        return proxyServer;
    }
    
    // ----------------------------------------------------- Private Methods

    HttpTransactionContext cloneAndStartTransactionFor(
            final Connection connection) {
        return cloneAndStartTransactionFor(connection, ahcRequest);
    }

    HttpTransactionContext cloneAndStartTransactionFor(
            final Connection connection,
            final Request request) {
        final HttpTransactionContext newContext = startTransaction(
                connection, provider, request, future);
        newContext.invocationStatus = invocationStatus;
        newContext.payloadGenerator = payloadGenerator;
        newContext.currentState = currentState;
        newContext.statusHandler = statusHandler;
        newContext.lastRedirectUri = lastRedirectUri;
        newContext.redirectCount = redirectCount;
        
        // detach the future
        future = null;
        
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
    
    void reuseConnection() {
        this.isReuseConnection = true;
    }

    boolean isReuseConnection() {
        return isReuseConnection;
    }

    void touchConnection() {
        provider.touchConnection(connection, ahcRequest);
    }

    void closeConnection() {
        connection.closeSilently();
    }

    private synchronized void addRequestSentCompletionHandler(
            final CompletionHandler<HttpTransactionContext> completionHandler) {
        if (reqFullySentHandlers == null) {
            reqFullySentHandlers = new HashSet<>();
        }
        reqFullySentHandlers.add(completionHandler);
    }

    private synchronized boolean removeRequestSentCompletionHandler(
            final CompletionHandler<HttpTransactionContext> completionHandler) {
        return reqFullySentHandlers != null
                ? reqFullySentHandlers.remove(completionHandler)
                : false;
    }
    
    boolean isRequestFullySent() {
        return isRequestFullySent;
    }
    
    @SuppressWarnings("unchecked")
    void onRequestFullySent() {
        this.isRequestFullySent = true;
        
        Object[] handlers = null;
        synchronized (this) {
            if (reqFullySentHandlers != null) {
                handlers = reqFullySentHandlers.toArray();
                reqFullySentHandlers = null;
            }
        }
        
        if (handlers != null) {
            for (Object o : handlers) {
                try {
                    ((CompletionHandler<HttpTransactionContext>) o).completed(this);
                } catch (Exception e) {
                }
            }
        }
    }
} // END HttpTransactionContext
