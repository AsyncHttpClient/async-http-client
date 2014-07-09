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

package org.asynchttpclient.providers.grizzly;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.grizzly.bodyhandler.BodyHandler;
import org.asynchttpclient.providers.grizzly.statushandler.StatusHandler;
import org.asynchttpclient.providers.grizzly.statushandler.StatusHandler.InvocationStatus;
import org.asynchttpclient.uri.UriComponents;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.websocket.WebSocket;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.websockets.HandShake;
import org.glassfish.grizzly.websockets.ProtocolHandler;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.asynchttpclient.providers.grizzly.filters.events.GracefulCloseEvent;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.http.HttpResponsePacket;

public final class HttpTxContext {

    private static final Attribute<HttpTxContext> REQUEST_STATE_ATTR = Grizzly.DEFAULT_ATTRIBUTE_BUILDER
            .createAttribute(HttpTxContext.class.getName());

    private final AtomicInteger redirectCount = new AtomicInteger(0);

    private final int maxRedirectCount;
    private final boolean redirectsAllowed;
    private final GrizzlyAsyncHttpProvider provider;

    private Request request;
    private UriComponents requestUri;
    private final AsyncHandler handler;
    private BodyHandler bodyHandler;
    private StatusHandler statusHandler;
    private InvocationStatus invocationStatus = InvocationStatus.CONTINUE;
    private GrizzlyResponseStatus responseStatus;
    private GrizzlyResponseFuture future;
    private String lastRedirectURI;
    private final AtomicLong totalBodyWritten = new AtomicLong();
    private AsyncHandler.STATE currentState;

    private UriComponents wsRequestURI;
    private boolean isWSRequest;
    private HandShake handshake;
    private ProtocolHandler protocolHandler;
    private WebSocket webSocket;
    private final CloseListener listener = new CloseListener<Closeable, CloseType>() {
        @Override
        public void onClosed(Closeable closeable, CloseType type) throws IOException {
            if (responseStatus != null && // responseStatus==null if request wasn't even sent
                    isGracefullyFinishResponseOnClose()) {
                // Connection was closed.
                // This event is fired only for responses, which don't have
                // associated transfer-encoding or content-length.
                // We have to complete such a request-response processing gracefully.
                final Connection c = responseStatus.getResponse()
                        .getRequest().getConnection();
                final FilterChain fc = (FilterChain) c.getProcessor();
                
                fc.fireEventUpstream(c,
                        new GracefulCloseEvent(HttpTxContext.this), null);
            } else if (CloseType.REMOTELY.equals(type)) {
                abort(AsyncHttpProviderUtils.REMOTELY_CLOSED_EXCEPTION);
            }
        }
    };

    // -------------------------------------------------------- Constructors

    private HttpTxContext(final GrizzlyAsyncHttpProvider provider, final GrizzlyResponseFuture future, final Request request,
            final AsyncHandler handler) {
        this.provider = provider;
        this.future = future;
        this.request = request;
        this.handler = handler;
        redirectsAllowed = this.provider.getClientConfig().isFollowRedirect();
        maxRedirectCount = this.provider.getClientConfig().getMaxRedirects();
        this.requestUri = request.getURI();
    }

    // ---------------------------------------------------------- Public Methods

    public static void set(final FilterChainContext ctx, final HttpTxContext httpTxContext) {
        HttpContext httpContext = HttpContext.get(ctx);
        httpContext.getCloseable().addCloseListener(httpTxContext.listener);
        REQUEST_STATE_ATTR.set(httpContext, httpTxContext);
    }

    public static HttpTxContext remove(final FilterChainContext ctx) {
        final HttpContext httpContext = HttpContext.get(ctx);
        final HttpTxContext httpTxContext = REQUEST_STATE_ATTR.remove(httpContext);
        if (httpTxContext != null) {
            httpContext.getCloseable().removeCloseListener(httpTxContext.listener);
        }
        
        return httpTxContext;
    }

    public static HttpTxContext get(FilterChainContext ctx) {
        HttpContext httpContext = HttpContext.get(ctx);
        return ((httpContext != null) ? REQUEST_STATE_ATTR.get(httpContext) : null);
    }

    public static HttpTxContext create(final RequestInfoHolder requestInfoHolder) {
        return new HttpTxContext(requestInfoHolder.getProvider(),//
                requestInfoHolder.getFuture(),//
                requestInfoHolder.getRequest(),//
                requestInfoHolder.getHandler());
    }

    public void abort(final Throwable t) {
        if (future != null) {
            future.abort(t);
        }
    }

    public AtomicInteger getRedirectCount() {
        return redirectCount;
    }

    public int getMaxRedirectCount() {
        return maxRedirectCount;
    }

    public boolean isRedirectsAllowed() {
        return redirectsAllowed;
    }

    public GrizzlyAsyncHttpProvider getProvider() {
        return provider;
    }

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public UriComponents getRequestUri() {
        return requestUri;
    }

    public void setRequestUri(UriComponents requestUri) {
        this.requestUri = requestUri;
    }

    public AsyncHandler getHandler() {
        return handler;
    }

    public BodyHandler getBodyHandler() {
        return bodyHandler;
    }

    public void setBodyHandler(BodyHandler bodyHandler) {
        this.bodyHandler = bodyHandler;
    }

    public StatusHandler getStatusHandler() {
        return statusHandler;
    }

    public void setStatusHandler(StatusHandler statusHandler) {
        this.statusHandler = statusHandler;
    }

    public InvocationStatus getInvocationStatus() {
        return invocationStatus;
    }

    public void setInvocationStatus(InvocationStatus invocationStatus) {
        this.invocationStatus = invocationStatus;
    }

    public GrizzlyResponseStatus getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(GrizzlyResponseStatus responseStatus) {
        this.responseStatus = responseStatus;
    }

    public GrizzlyResponseFuture getFuture() {
        return future;
    }

    public void setFuture(GrizzlyResponseFuture future) {
        this.future = future;
    }

    public String getLastRedirectURI() {
        return lastRedirectURI;
    }

    public void setLastRedirectURI(String lastRedirectURI) {
        this.lastRedirectURI = lastRedirectURI;
    }

    public AtomicLong getTotalBodyWritten() {
        return totalBodyWritten;
    }

    public AsyncHandler.STATE getCurrentState() {
        return currentState;
    }

    public void setCurrentState(AsyncHandler.STATE currentState) {
        this.currentState = currentState;
    }

    public UriComponents getWsRequestURI() {
        return wsRequestURI;
    }

    public void setWsRequestURI(UriComponents wsRequestURI) {
        this.wsRequestURI = wsRequestURI;
    }

    public boolean isWSRequest() {
        return isWSRequest;
    }

    public void setWSRequest(boolean WSRequest) {
        isWSRequest = WSRequest;
    }

    public HandShake getHandshake() {
        return handshake;
    }

    public void setHandshake(HandShake handshake) {
        this.handshake = handshake;
    }

    public ProtocolHandler getProtocolHandler() {
        return protocolHandler;
    }

    public void setProtocolHandler(ProtocolHandler protocolHandler) {
        this.protocolHandler = protocolHandler;
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    public void setWebSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    private boolean isGracefullyFinishResponseOnClose() {
        final HttpResponsePacket response = responseStatus.getResponse();
        return !response.getProcessingState().isKeepAlive() &&
                !response.isChunked() && response.getContentLength() == -1;
    }
    
    // ------------------------------------------------- Package Private Methods

    public HttpTxContext copy() {
        final HttpTxContext newContext = new HttpTxContext(provider, future, request, handler);
        newContext.invocationStatus = invocationStatus;
        newContext.bodyHandler = bodyHandler;
        newContext.currentState = currentState;
        newContext.statusHandler = statusHandler;
        newContext.lastRedirectURI = lastRedirectURI;
        newContext.redirectCount.set(redirectCount.get());
        return newContext;
    }

    void done() {
        if (future != null) {
            future.done();
        }
    }

    @SuppressWarnings({ "unchecked" })
    void result(Object result) {
        if (future != null) {
            future.delegate.result(result);
            future.done();
        }
    }
}
