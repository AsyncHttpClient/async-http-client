/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
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
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.ConnectionsPool;
import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.FluentStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Part;
import com.ning.http.client.PerRequestConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.UpgradeHandler;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketByteListener;
import com.ning.http.client.websocket.WebSocketCloseCodeReasonListener;
import com.ning.http.client.websocket.WebSocketListener;
import com.ning.http.client.websocket.WebSocketPingListener;
import com.ning.http.client.websocket.WebSocketPongListener;
import com.ning.http.client.websocket.WebSocketTextListener;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;
import com.ning.http.multipart.MultipartRequestEntity;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.ProxyUtils;
import com.ning.http.util.SslUtils;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.FileTransfer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeStorage;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.FilterChainEvent;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.ContentEncoding;
import org.glassfish.grizzly.http.EncodingFilter;
import org.glassfish.grizzly.http.GZipContentEncoding;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.http.util.CookieSerializerUtils;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.utils.BufferOutputStream;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.DefaultWebSocket;
import org.glassfish.grizzly.websockets.HandShake;
import org.glassfish.grizzly.websockets.ProtocolHandler;
import org.glassfish.grizzly.websockets.Version;
import org.glassfish.grizzly.websockets.WebSocketEngine;
import org.glassfish.grizzly.websockets.WebSocketFilter;
import org.glassfish.grizzly.websockets.draft06.ClosingFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.TRANSPORT_CUSTOMIZER;

/**
 * A Grizzly 2.0-based implementation of {@link AsyncHttpProvider}.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyAsyncHttpProvider implements AsyncHttpProvider {

    private final static Logger LOGGER = LoggerFactory.getLogger(GrizzlyAsyncHttpProvider.class);
    private static final boolean SEND_FILE_SUPPORT;
    static {
        SEND_FILE_SUPPORT = configSendFileSupport();
    }
    private final Attribute<HttpTransactionContext> REQUEST_STATE_ATTR =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(HttpTransactionContext.class.getName());

    private final BodyHandlerFactory bodyHandlerFactory = new BodyHandlerFactory();

    private final TCPNIOTransport clientTransport;
    private final AsyncHttpClientConfig clientConfig;
    private final ConnectionManager connectionManager;

    DelayedExecutor.Resolver<Connection> resolver;
    private DelayedExecutor timeoutExecutor;




    // ------------------------------------------------------------ Constructors


    public GrizzlyAsyncHttpProvider(final AsyncHttpClientConfig clientConfig) {

        this.clientConfig = clientConfig;
        final TCPNIOTransportBuilder builder = TCPNIOTransportBuilder.newInstance();
        clientTransport = builder.build();
        initializeTransport(clientConfig);
        connectionManager = new ConnectionManager(this, clientTransport);
        try {
            clientTransport.start();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

    }


    // ------------------------------------------ Methods from AsyncHttpProvider


    /**
     * {@inheritDoc}
     */
    @SuppressWarnings({"unchecked"})
    public <T> ListenableFuture<T> execute(final Request request,
                                           final AsyncHandler<T> handler) throws IOException {

        final GrizzlyResponseFuture<T> future =
                new GrizzlyResponseFuture<T>(this, request, handler);
        future.setDelegate(SafeFutureImpl.<T>create());
        final CompletionHandler<Connection>  connectHandler = new CompletionHandler<Connection>() {
            @Override
            public void cancelled() {
                future.cancel(true);
            }

            @Override
            public void failed(final Throwable throwable) {
                future.abort(throwable);
            }

            @Override
            public void completed(final Connection c) {
                try {
                    execute(c, request, handler, future);
                } catch (Exception e) {
                    if (e instanceof RuntimeException) {
                        failed(e);
                    } else if (e instanceof IOException) {
                        failed(e);
                    }
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(e.toString(), e);
                    }
                }
            }

            @Override
            public void updated(final Connection c) {
                // no-op
            }
        };

        try {
            connectionManager.doAsyncTrackedConnection(request, future, connectHandler);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else if (e instanceof IOException) {
                throw (IOException) e;
            }
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(e.toString(), e);
            }
        }

        return future;
    }

    /**
     * {@inheritDoc}
     */
    public void close() {

        try {
            connectionManager.destroy();
            clientTransport.stop();
            final ExecutorService service = clientConfig.executorService();
            if (service != null) {
                service.shutdown();
            }
            if (timeoutExecutor != null) {
                timeoutExecutor.stop();
            }
        } catch (IOException ignored) { }

    }


    /**
     * {@inheritDoc}
     */
    public Response prepareResponse(HttpResponseStatus status,
                                    HttpResponseHeaders headers,
                                    Collection<HttpResponseBodyPart> bodyParts) {

        return new GrizzlyResponse(status, headers, bodyParts);

    }


    // ------------------------------------------------------- Protected Methods


    @SuppressWarnings({"unchecked"})
    protected <T> ListenableFuture<T> execute(final Connection c,
                                              final Request request,
                                              final AsyncHandler<T> handler,
                                              final GrizzlyResponseFuture<T> future)
    throws IOException {

        try {
            if (getHttpTransactionContext(c) == null) {
                setHttpTransactionContext(c,
                        new HttpTransactionContext(future, request, handler));
            }
            c.write(request, createWriteCompletionHandler(future));
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else if (e instanceof IOException) {
                throw (IOException) e;
            }
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(e.toString(), e);
            }
        }

        return future;
    }


    protected void initializeTransport(final AsyncHttpClientConfig clientConfig) {

        final FilterChainBuilder fcb = FilterChainBuilder.stateless();
        fcb.add(new AsyncHttpClientTransportFilter());

        final int timeout = clientConfig.getRequestTimeoutInMs();
        if (timeout > 0) {
            int delay = 500;
            if (timeout < delay) {
                delay = timeout - 10;
            }
            timeoutExecutor = IdleTimeoutFilter.createDefaultIdleDelayedExecutor(delay, TimeUnit.MILLISECONDS);
            timeoutExecutor.start();
            final IdleTimeoutFilter.TimeoutResolver timeoutResolver =
                    new IdleTimeoutFilter.TimeoutResolver() {
                        @Override
                        public long getTimeout(FilterChainContext ctx) {
                            final HttpTransactionContext context =
                                    GrizzlyAsyncHttpProvider.this.getHttpTransactionContext(ctx.getConnection());
                            if (context != null) {
                                if (context.isWSRequest) {
                                    return clientConfig.getWebSocketIdleTimeoutInMs();
                                }
                                final PerRequestConfig config = context.request.getPerRequestConfig();
                                if (config != null) {
                                    final long timeout = config.getRequestTimeoutInMs();
                                    if (timeout > 0) {
                                        return timeout;
                                    }
                                }
                            }
                            return timeout;
                        }
                    };
            final IdleTimeoutFilter timeoutFilter = new IdleTimeoutFilter(timeoutExecutor,
                    timeoutResolver,
                    new IdleTimeoutFilter.TimeoutHandler() {
                        public void onTimeout(Connection connection) {
                            timeout(connection);
                        }
                    });
            fcb.add(timeoutFilter);
            resolver = timeoutFilter.getResolver();
        }

        SSLContext context = clientConfig.getSSLContext();
        boolean defaultSecState = (context != null);
        if (context == null) {
            try {
                context = SslUtils.getSSLContext();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        final SSLEngineConfigurator configurator =
                new SSLEngineConfigurator(context,
                        true,
                        false,
                        false);
        final SwitchingSSLFilter filter = new SwitchingSSLFilter(configurator, defaultSecState);
        fcb.add(filter);
        final AsyncHttpClientEventFilter eventFilter = new
                AsyncHttpClientEventFilter(this);
        final AsyncHttpClientFilter clientFilter =
                new AsyncHttpClientFilter(clientConfig);
        ContentEncoding[] encodings = eventFilter.getContentEncodings();
        if (encodings.length > 0) {
            for (ContentEncoding encoding : encodings) {
                eventFilter.removeContentEncoding(encoding);
            }
        }
        if (clientConfig.isCompressionEnabled()) {
            eventFilter.addContentEncoding(
                    new GZipContentEncoding(512,
                                            512,
                                            new ClientEncodingFilter()));
        }
        fcb.add(eventFilter);
        fcb.add(clientFilter);
        
        GrizzlyAsyncHttpProviderConfig providerConfig =
                (GrizzlyAsyncHttpProviderConfig) clientConfig.getAsyncHttpProviderConfig();
        if (providerConfig != null) {
            final TransportCustomizer customizer = (TransportCustomizer)
                    providerConfig.getProperty(TRANSPORT_CUSTOMIZER);
            if (customizer != null) {
                customizer.customize(clientTransport, fcb);
            } else {
                doDefaultTransportConfig();
            }
        } else {
            doDefaultTransportConfig();
        }
        fcb.add(new WebSocketFilter());
        clientTransport.getAsyncQueueIO().getWriter().setMaxPendingBytesPerConnection(-1);
        clientTransport.setProcessor(fcb.build());

    }


    // ------------------------------------------------- Package Private Methods


    void touchConnection(final Connection c, final Request request) {

        final PerRequestConfig config = request.getPerRequestConfig();
        if (config != null) {
            final long timeout = config.getRequestTimeoutInMs();
            if (timeout > 0) {
                final long newTimeout = System.currentTimeMillis() + timeout;
                if (resolver != null) {
                    resolver.setTimeoutMillis(c, newTimeout);
                }
            }
        } else {
            final long timeout = clientConfig.getRequestTimeoutInMs();
            if (timeout > 0) {
                if (resolver != null) {
                    resolver.setTimeoutMillis(c, System.currentTimeMillis() + timeout);
                }
            }
        }

    }


    // --------------------------------------------------------- Private Methods


    private static boolean configSendFileSupport() {

        return !((System.getProperty("os.name").equalsIgnoreCase("linux")
                && !linuxSendFileSupported())
                || System.getProperty("os.name").equalsIgnoreCase("HP-UX"));
    }


    private static boolean linuxSendFileSupported() {
        final String version = System.getProperty("java.version");
        if (version.startsWith("1.6")) {
            int idx = version.indexOf('_');
            if (idx == -1) {
                return false;
            }
            final int patchRev = Integer.parseInt(version.substring(idx + 1));
            return (patchRev >= 18);
        } else {
            return version.startsWith("1.7") || version.startsWith("1.8");
        }
    }
    
    private void doDefaultTransportConfig() {
        final ExecutorService service = clientConfig.executorService();
        if (service != null) {
            clientTransport.setIOStrategy(WorkerThreadIOStrategy.getInstance());
            clientTransport.setWorkerThreadPool(service);
        } else {
            clientTransport.setIOStrategy(SameThreadIOStrategy.getInstance());
        }
    }


    private <T> CompletionHandler<WriteResult> createWriteCompletionHandler(final GrizzlyResponseFuture<T> future) {
        return new CompletionHandler<WriteResult>() {

            public void cancelled() {
                future.cancel(true);
            }

            public void failed(Throwable throwable) {
                future.abort(throwable);
            }

            public void completed(WriteResult result) {
            }

            public void updated(WriteResult result) {
                // no-op
            }

        };
    }


    void setHttpTransactionContext(final AttributeStorage storage,
                                           final HttpTransactionContext httpTransactionState) {

        if (httpTransactionState == null) {
            REQUEST_STATE_ATTR.remove(storage);
        } else {
            REQUEST_STATE_ATTR.set(storage, httpTransactionState);
        }

    }

    HttpTransactionContext getHttpTransactionContext(final AttributeStorage storage) {

        return REQUEST_STATE_ATTR.get(storage);

    }


    void timeout(final Connection c) {

        final HttpTransactionContext context = getHttpTransactionContext(c);
        setHttpTransactionContext(c, null);
        context.abort(new TimeoutException("Timeout exceeded"));

    }

    static int getPort(final URI uri, final int p) {
        int port = p;
        if (port == -1) {
            final String protocol = uri.getScheme().toLowerCase();
            if ("http".equals(protocol)) {
                port = 80;
            } else if ("https".equals(protocol)) {
                port = 443;
            } else {
                throw new IllegalArgumentException("Unknown protocol: " + protocol);
            }
        }
        return port;
    }


    @SuppressWarnings({"unchecked"})
    boolean sendRequest(final FilterChainContext ctx,
                     final Request request,
                     final HttpRequestPacket requestPacket)
    throws IOException {

        boolean isWriteComplete = true;
        
        if (requestHasEntityBody(request)) {
            final HttpTransactionContext context = getHttpTransactionContext(ctx.getConnection());
            BodyHandler handler = bodyHandlerFactory.getBodyHandler(request);
            if (requestPacket.getHeaders().contains(Header.Expect)
                    && requestPacket.getHeaders().getValue(1).equalsIgnoreCase("100-Continue")) {
                handler = new ExpectHandler(handler);
            }
            context.bodyHandler = handler;
            isWriteComplete = handler.doHandle(ctx, request, requestPacket);
        } else {
            ctx.write(requestPacket, ctx.getTransportContext().getCompletionHandler());
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("REQUEST: " + requestPacket.toString());
        }
        
        return isWriteComplete;
    }


    private static boolean requestHasEntityBody(final Request request) {

        final String method = request.getMethod();
        return (Method.POST.matchesMethod(method)
                || Method.PUT.matchesMethod(method)
                || Method.PATCH.matchesMethod(method)
                || Method.DELETE.matchesMethod(method));

    }


    // ----------------------------------------------------------- Inner Classes


    private interface StatusHandler {

        public enum InvocationStatus {
            CONTINUE,
            STOP
        }

        boolean handleStatus(final HttpResponsePacket httpResponse,
                             final HttpTransactionContext httpTransactionContext,
                             final FilterChainContext ctx);

        boolean handlesStatus(final int statusCode);

    } // END StatusHandler


    final class HttpTransactionContext {

        final AtomicInteger redirectCount = new AtomicInteger(0);

        final int maxRedirectCount;
        final boolean redirectsAllowed;
        final GrizzlyAsyncHttpProvider provider =
                GrizzlyAsyncHttpProvider.this;

        Request request;
        String requestUrl;
        AsyncHandler handler;
        BodyHandler bodyHandler;
        StatusHandler statusHandler;
        StatusHandler.InvocationStatus invocationStatus =
                StatusHandler.InvocationStatus.CONTINUE;
        GrizzlyResponseStatus responseStatus;
        GrizzlyResponseFuture future;
        String lastRedirectURI;
        AtomicLong totalBodyWritten = new AtomicLong();
        AsyncHandler.STATE currentState;
        
        String wsRequestURI;
        boolean isWSRequest;
        HandShake handshake;
        ProtocolHandler protocolHandler;
        WebSocket webSocket;


        // -------------------------------------------------------- Constructors


        HttpTransactionContext(final GrizzlyResponseFuture future,
                               final Request request,
                               final AsyncHandler handler) {

            this.future = future;
            this.request = request;
            this.handler = handler;
            redirectsAllowed = provider.clientConfig.isRedirectEnabled();
            maxRedirectCount = provider.clientConfig.getMaxRedirects();
            this.requestUrl = request.getUrl();

        }


        // ----------------------------------------------------- Private Methods


        HttpTransactionContext copy() {
            final HttpTransactionContext newContext =
                    new HttpTransactionContext(future,
                                               request,
                                               handler);
            newContext.invocationStatus = invocationStatus;
            newContext.bodyHandler = bodyHandler;
            newContext.currentState = currentState;
            newContext.statusHandler = statusHandler;
            newContext.lastRedirectURI = lastRedirectURI;
            newContext.redirectCount.set(redirectCount.get());
            return newContext;

        }


        void abort(final Throwable t) {
            if (future != null) {
                future.abort(t);
            }
        }

        void done(final Callable c) {
            if (future != null) {
                future.done(c);
            }
        }

        @SuppressWarnings({"unchecked"})
        void result(Object result) {
            if (future != null) {
                future.delegate.result(result);
                future.done(null);
            }
        }


    } // END HttpTransactionContext


    // ---------------------------------------------------------- Nested Classes

    private static final class ContinueEvent implements FilterChainEvent {

        private final HttpTransactionContext context;


        // -------------------------------------------------------- Constructors


        ContinueEvent(final HttpTransactionContext context) {

            this.context = context;

        }


        // --------------------------------------- Methods from FilterChainEvent


        @Override
        public Object type() {
            return ContinueEvent.class;
        }

    } // END ContinueEvent


    private final class AsyncHttpClientTransportFilter extends TransportFilter {

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            final HttpTransactionContext context = getHttpTransactionContext(ctx.getConnection());
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
                    context.abort(throwable);
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

    } // END AsyncHttpClientTransportFilter


    private final class AsyncHttpClientFilter extends BaseFilter {


        private final AsyncHttpClientConfig config;


        // -------------------------------------------------------- Constructors


        AsyncHttpClientFilter(final AsyncHttpClientConfig config) {

            this.config = config;

        }


        // --------------------------------------------- Methods from BaseFilter


        @Override
        public NextAction handleWrite(final FilterChainContext ctx)
        throws IOException {

            Object message = ctx.getMessage();
            if (message instanceof Request) {
                ctx.setMessage(null);
                if (!sendAsGrizzlyRequest((Request) message, ctx)) {
                    return ctx.getSuspendAction();
                }
            } else if (message instanceof Buffer) {
                return ctx.getInvokeAction();
            }

            return ctx.getStopAction();
        }

        @Override
        public NextAction handleEvent(final FilterChainContext ctx,
                                      final FilterChainEvent event)
        throws IOException {

            final Object type = event.type();
            if (type == ContinueEvent.class) {
                final ContinueEvent continueEvent = (ContinueEvent) event;
                ((ExpectHandler) continueEvent.context.bodyHandler).finish(ctx);
            }

            return ctx.getStopAction();

        }

//        @Override
//        public NextAction handleRead(FilterChainContext ctx) throws IOException {
//            Object message = ctx.getMessage();
//            if (HttpPacket.isHttp(message)) {
//                final HttpPacket packet = (HttpPacket) message;
//                HttpResponsePacket responsePacket;
//                if (HttpContent.isContent(packet)) {
//                    responsePacket = (HttpResponsePacket) ((HttpContent) packet).getHttpHeader();
//                } else {
//                    responsePacket = (HttpResponsePacket) packet;
//                }
//                if (HttpStatus.SWITCHING_PROTOCOLS_101.statusMatches(responsePacket.getStatus())) {
//                    return ctx.getStopAction();
//                }
//            }
//            return super.handleRead(ctx);
//        }

        // ----------------------------------------------------- Private Methods


        private boolean sendAsGrizzlyRequest(final Request request,
                                          final FilterChainContext ctx)
        throws IOException {

            final HttpTransactionContext httpCtx = getHttpTransactionContext(ctx.getConnection());
            if (isUpgradeRequest(httpCtx.handler) && isWSRequest(httpCtx.requestUrl)) {
                httpCtx.isWSRequest = true;
                convertToUpgradeRequest(httpCtx);
            }
            final URI uri = AsyncHttpProviderUtils.createUri(httpCtx.requestUrl);
            final HttpRequestPacket.Builder builder = HttpRequestPacket.builder();
            boolean secure = "https".equals(uri.getScheme());
            builder.method(request.getMethod());
            builder.protocol(Protocol.HTTP_1_1);
            String host = request.getVirtualHost();
            if (host != null) {
                builder.header(Header.Host, host);
            } else {
                if (uri.getPort() == -1) {
                    builder.header(Header.Host, uri.getHost());
                } else {
                    builder.header(Header.Host, uri.getHost() + ':' + uri.getPort());
                }
            }
            final ProxyServer proxy = getProxyServer(request);
            final boolean useProxy = (proxy != null);
            if (useProxy) {
                if (secure) {
                    builder.method(Method.CONNECT);
                    builder.uri(AsyncHttpProviderUtils.getAuthority(uri));
                } else {
                    builder.uri(uri.toString());
                }
            } else {
                builder.uri(uri.getPath());
            }
            if (requestHasEntityBody(request)) {
                final long contentLength = request.getContentLength();
                if (contentLength > 0) {
                    builder.contentLength(contentLength);
                    builder.chunked(false);
                } else {
                    builder.chunked(true);
                }
            }

            HttpRequestPacket requestPacket;
            if (httpCtx.isWSRequest) {
                try {
                    final URI wsURI = new URI(httpCtx.wsRequestURI);
                    httpCtx.protocolHandler = Version.DRAFT17.createHandler(true);
                    httpCtx.handshake = httpCtx.protocolHandler.createHandShake(wsURI);
                    requestPacket = (HttpRequestPacket)
                            httpCtx.handshake.composeHeaders().getHttpHeader();
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException("Invalid WS URI: " + httpCtx.wsRequestURI);
                }
            } else {
                requestPacket = builder.build();
            }
            requestPacket.setSecure(true);
            if (!useProxy && !httpCtx.isWSRequest) {
                addQueryString(request, requestPacket);
            }
            addHeaders(request, requestPacket);
            addCookies(request, requestPacket);

            if (useProxy) {
                boolean avoidProxy = ProxyUtils.avoidProxy(proxy, request);
                if (!avoidProxy) {
                    if (!requestPacket.getHeaders().contains(Header.ProxyConnection)) {
                        requestPacket.setHeader(Header.ProxyConnection, "keep-alive");
                    }

                    if (proxy.getPrincipal() != null) {
                        requestPacket.setHeader(Header.ProxyAuthorization,
                                AuthenticatorUtils.computeBasicAuthentication(proxy));
                    }
                }
            }
            final AsyncHandler h = httpCtx.handler;
            if (h != null) {
                if (TransferCompletionHandler.class.isAssignableFrom(h.getClass())) {
                    final FluentCaseInsensitiveStringsMap map =
                            new FluentCaseInsensitiveStringsMap(request.getHeaders());
                    TransferCompletionHandler.class.cast(h).transferAdapter(new GrizzlyTransferAdapter(map));
                }
            }
            return sendRequest(ctx, request, requestPacket);

        }


        private boolean isUpgradeRequest(final AsyncHandler handler) {
            return (handler instanceof UpgradeHandler);
        }


        private boolean isWSRequest(final String requestUri) {
            return (requestUri.charAt(0) == 'w' && requestUri.charAt(1) == 's');
        }

        
        private void convertToUpgradeRequest(final HttpTransactionContext ctx) {
            final int colonIdx = ctx.requestUrl.indexOf(':');

            if (colonIdx < 2 || colonIdx > 3) {
                throw new IllegalArgumentException("Invalid websocket URL: " + ctx.requestUrl);
            }

            final StringBuilder sb = new StringBuilder(ctx.requestUrl);
            sb.replace(0, colonIdx, ((colonIdx == 2) ? "http" : "https"));
            ctx.wsRequestURI = ctx.requestUrl;
            ctx.requestUrl = sb.toString();
        }

        
        private ProxyServer getProxyServer(Request request) {

            ProxyServer proxyServer = request.getProxyServer();
            if (proxyServer == null) {
                proxyServer = config.getProxyServer();
            }
            return proxyServer;

        }


        private void addHeaders(final Request request,
                                final HttpRequestPacket requestPacket) {

            final FluentCaseInsensitiveStringsMap map = request.getHeaders();
            if (map != null && !map.isEmpty()) {
                for (final Map.Entry<String, List<String>> entry : map.entrySet()) {
                    final String headerName = entry.getKey();
                    final List<String> headerValues = entry.getValue();
                    if (headerValues != null && !headerValues.isEmpty()) {
                        for (final String headerValue : headerValues) {
                            requestPacket.addHeader(headerName, headerValue);
                        }
                    }
                }
            }

            final MimeHeaders headers = requestPacket.getHeaders();
            if (!headers.contains(Header.Connection)) {
                requestPacket.addHeader(Header.Connection, "keep-alive");
            }

            if (!headers.contains(Header.Accept)) {
                requestPacket.addHeader(Header.Accept, "*/*");
            }

            if (!headers.contains(Header.UserAgent)) {
                requestPacket.addHeader(Header.UserAgent, config.getUserAgent());
            }


        }


        private void addCookies(final Request request,
                                final HttpRequestPacket requestPacket) {

            final Collection<Cookie> cookies = request.getCookies();
            if (cookies != null && !cookies.isEmpty()) {
                StringBuilder sb = new StringBuilder(128);
                org.glassfish.grizzly.http.Cookie[] gCookies =
                        new org.glassfish.grizzly.http.Cookie[cookies.size()];
                convertCookies(cookies, gCookies);
                CookieSerializerUtils.serializeClientCookies(sb, gCookies);
                requestPacket.addHeader(Header.Cookie, sb.toString());
            }

        }


        private void convertCookies(final Collection<Cookie> cookies,
                                    final org.glassfish.grizzly.http.Cookie[] gCookies) {
            int idx = 0;
            for (final Cookie cookie : cookies) {
                final org.glassfish.grizzly.http.Cookie gCookie =
                        new org.glassfish.grizzly.http.Cookie(cookie.getName(), cookie.getValue());
                gCookie.setDomain(cookie.getDomain());
                gCookie.setPath(cookie.getPath());
                gCookie.setVersion(cookie.getVersion());
                gCookie.setMaxAge(cookie.getMaxAge());
                gCookie.setSecure(cookie.isSecure());
                gCookies[idx] = gCookie;
                idx++;
            }

        }


        private void addQueryString(final Request request,
                                    final HttpRequestPacket requestPacket) {

            final FluentStringsMap map = request.getQueryParams();
            if (map != null && !map.isEmpty()) {
                StringBuilder sb = new StringBuilder(128);
                for (final Map.Entry<String, List<String>> entry : map.entrySet()) {
                    final String name = entry.getKey();
                    final List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        try {
                            for (int i = 0, len = values.size(); i < len; i++) {
                                final String value = values.get(i);
                                if (value != null && value.length() > 0) {
                                    sb.append(URLEncoder.encode(name, "UTF-8")).append('=')
                                        .append(URLEncoder.encode(values.get(i), "UTF-8")).append('&');
                                } else {
                                    sb.append(URLEncoder.encode(name, "UTF-8")).append('&');
                                }
                            }
                        } catch (UnsupportedEncodingException ignored) {
                        }
                    }
                }
                String queryString = sb.deleteCharAt((sb.length() - 1)).toString();

                requestPacket.setQueryString(queryString);
            }

        }

    } // END AsyncHttpClientFiler


    private static final class AsyncHttpClientEventFilter extends HttpClientFilter {

        private final Map<Integer,StatusHandler> HANDLER_MAP = new HashMap<Integer,StatusHandler>();


        private final GrizzlyAsyncHttpProvider provider;


        // -------------------------------------------------------- Constructors


        AsyncHttpClientEventFilter(final GrizzlyAsyncHttpProvider provider) {

            this.provider = provider;
            HANDLER_MAP.put(HttpStatus.UNAUTHORIZED_401.getStatusCode(),
                            AuthorizationHandler.INSTANCE);
            HANDLER_MAP.put(HttpStatus.MOVED_PERMANENTLY_301.getStatusCode(),
                    RedirectHandler.INSTANCE);
            HANDLER_MAP.put(HttpStatus.FOUND_302.getStatusCode(),
                    RedirectHandler.INSTANCE);
            HANDLER_MAP.put(HttpStatus.TEMPORARY_REDIRECT_307.getStatusCode(),
                    RedirectHandler.INSTANCE);

        }


        // --------------------------------------- Methods from HttpClientFilter


        @Override
        public void exceptionOccurred(FilterChainContext ctx, Throwable error) {

            provider.getHttpTransactionContext(ctx.getConnection()).abort(error);

        }


        @Override
        protected void onHttpContentParsed(HttpContent content,
                                           FilterChainContext ctx) {

            final HttpTransactionContext context =
                    provider.getHttpTransactionContext(ctx.getConnection());
            final AsyncHandler handler = context.handler;
            if (handler != null && context.currentState != AsyncHandler.STATE.ABORT) {
                try {
                    context.currentState = handler.onBodyPartReceived(
                            new GrizzlyResponseBodyPart(content,
                                    null,
                                    ctx.getConnection(),
                                    provider));
                } catch (Exception e) {
                    handler.onThrowable(e);
                }
            }

        }

        @Override
        protected void onHttpHeadersEncoded(HttpHeader httpHeader, FilterChainContext ctx) {
            final HttpTransactionContext context = provider.getHttpTransactionContext(ctx.getConnection());
            final AsyncHandler handler = context.handler;
            if (handler != null) {
                if (TransferCompletionHandler.class.isAssignableFrom(handler.getClass())) {
                    ((TransferCompletionHandler) handler).onHeaderWriteCompleted();
                }
            }
        }

        @Override
        protected void onHttpContentEncoded(HttpContent content, FilterChainContext ctx) {
            final HttpTransactionContext context = provider.getHttpTransactionContext(ctx.getConnection());
            final AsyncHandler handler = context.handler;
            if (handler != null) {
                if (TransferCompletionHandler.class.isAssignableFrom(handler.getClass())) {
                    final int written = content.getContent().remaining();
                    final long total = context.totalBodyWritten.addAndGet(written);
                    ((TransferCompletionHandler) handler).onContentWriteProgress(
                            written,
                            total,
                            content.getHttpHeader().getContentLength());
                }
            }
        }

        @Override
        protected void onInitialLineParsed(HttpHeader httpHeader,
                                           FilterChainContext ctx) {

            super.onInitialLineParsed(httpHeader, ctx);
            if (httpHeader.isSkipRemainder()) {
                return;
            }
            final HttpTransactionContext context =
                    provider.getHttpTransactionContext(ctx.getConnection());
            final int status = ((HttpResponsePacket) httpHeader).getStatus();
            if (HttpStatus.CONINTUE_100.statusMatches(status)) {
                ctx.notifyUpstream(new ContinueEvent(context));
                return;
            }

            if (context.statusHandler != null && !context.statusHandler.handlesStatus(status)) {
                context.statusHandler = null;
                context.invocationStatus = StatusHandler.InvocationStatus.CONTINUE;
            } else {
                context.statusHandler = null;
            }
            if (context.invocationStatus == StatusHandler.InvocationStatus.CONTINUE) {
                if (HANDLER_MAP.containsKey(status)) {
                    context.statusHandler = HANDLER_MAP.get(status);
                }
                if (context.statusHandler instanceof RedirectHandler) {
                    if (!isRedirectAllowed(context)) {
                        context.statusHandler = null;
                    }
                }
            }
            if (isRedirectAllowed(context)) {
                if (isRedirect(status)) {
                    if (context.statusHandler == null) {
                        context.statusHandler = RedirectHandler.INSTANCE;
                    }
                    context.redirectCount.incrementAndGet();
                    if (redirectCountExceeded(context)) {
                        httpHeader.setSkipRemainder(true);
                        context.abort(new MaxRedirectException());
                    }
                } else {
                    if (context.redirectCount.get() > 0) {
                        context.redirectCount.set(0);
                    }
                }
            }
            final GrizzlyResponseStatus responseStatus =
                        new GrizzlyResponseStatus((HttpResponsePacket) httpHeader,
                                                  getURI(context.requestUrl),
                                                  provider);
            context.responseStatus = responseStatus;
            if (context.statusHandler != null) {
                return;
            }
            if (context.currentState != AsyncHandler.STATE.ABORT) {

                try {
                    final AsyncHandler handler = context.handler;
                    if (handler != null) {
                        context.currentState = handler.onStatusReceived(responseStatus);
                    }
                } catch (Exception e) {
                    httpHeader.setSkipRemainder(true);
                    context.abort(e);
                }
            }

        }


        @Override
        protected void onHttpHeaderError(final HttpHeader httpHeader,
                                         final FilterChainContext ctx,
                                         final Throwable t) throws IOException {

            t.printStackTrace();
            httpHeader.setSkipRemainder(true);
            final HttpTransactionContext context =
                    provider.getHttpTransactionContext(ctx.getConnection());
            context.abort(t);
        }

        @SuppressWarnings({"unchecked"})
        @Override
        protected void onHttpHeadersParsed(HttpHeader httpHeader,
                                           FilterChainContext ctx) {

            super.onHttpHeadersParsed(httpHeader, ctx);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RESPONSE: " + httpHeader.toString());
            }
            if (httpHeader.containsHeader(Header.Connection)) {
                if ("close".equals(httpHeader.getHeader(Header.Connection))) {
                    ConnectionManager.markConnectionAsDoNotCache(ctx.getConnection());
                }
            }
            if (httpHeader.isSkipRemainder()) {
                return;
            }
            final HttpTransactionContext context =
                    provider.getHttpTransactionContext(ctx.getConnection());
            final AsyncHandler handler = context.handler;
            final List<ResponseFilter> filters = context.provider.clientConfig.getResponseFilters();
            final GrizzlyResponseHeaders responseHeaders = new GrizzlyResponseHeaders((HttpResponsePacket) httpHeader,
                                    null,
                                    provider);
            if (!filters.isEmpty()) {
                FilterContext fc = new FilterContext.FilterContextBuilder()
                        .asyncHandler(handler).request(context.request)
                        .responseHeaders(responseHeaders)
                        .responseStatus(context.responseStatus).build();
                try {
                    for (final ResponseFilter f : filters) {
                        fc = f.filter(fc);
                    }
                } catch (Exception e) {
                    context.abort(e);
                }
                if (fc.replayRequest()) {
                    httpHeader.setSkipRemainder(true);
                    final Request newRequest = fc.getRequest();
                    final AsyncHandler newHandler = fc.getAsyncHandler();
                    try {
                        final ConnectionManager m =
                                context.provider.connectionManager;
                        final Connection c =
                                m.obtainConnection(newRequest,
                                                   context.future);
                        final HttpTransactionContext newContext =
                                context.copy();
                        context.future = null;
                        provider.setHttpTransactionContext(c, newContext);
                        try {
                            context.provider.execute(c,
                                                     newRequest,
                                                     newHandler,
                                                     context.future);
                        } catch (IOException ioe) {
                            newContext.abort(ioe);
                        }
                    } catch (Exception e) {
                        context.abort(e);
                    }
                    return;
                }
            }
            if (context.statusHandler != null && context.invocationStatus == StatusHandler.InvocationStatus.CONTINUE) {
                final boolean result = context.statusHandler.handleStatus(((HttpResponsePacket) httpHeader),
                                                                          context,
                                                                          ctx);
                if (!result) {
                    httpHeader.setSkipRemainder(true);
                    return;
                }
            }

            if (context.currentState != AsyncHandler.STATE.ABORT) {
                boolean upgrade = context.currentState == AsyncHandler.STATE.UPGRADE;
                try {
                    context.currentState = handler.onHeadersReceived(
                            responseHeaders);
                } catch (Exception e) {
                    httpHeader.setSkipRemainder(true);
                    context.abort(e);
                    return;
                }
                if (upgrade) {
                    try {
                        httpHeader.setChunked(false);
                        context.protocolHandler.setConnection(ctx.getConnection());
                        DefaultWebSocket ws = new DefaultWebSocket(context.protocolHandler);
                        ws.onConnect();
                        context.webSocket = new GrizzlyWebSocketAdapter(ws);
                        WebSocketEngine.getEngine().setWebSocketHolder(ctx.getConnection(),
                                context.protocolHandler,
                                ws);
                        ((WebSocketUpgradeHandler) context.handler).onSuccess(context.webSocket);
                        final int wsTimeout = context.provider.clientConfig.getWebSocketIdleTimeoutInMs();
                        IdleTimeoutFilter.setCustomTimeout(ctx.getConnection(),
                                                           ((wsTimeout <= 0)
                                                                   ? IdleTimeoutFilter.FOREVER
                                                                   : wsTimeout),
                                                           TimeUnit.MILLISECONDS);
                        context.result(handler.onCompleted());
                    } catch (Exception e) {
                        httpHeader.setSkipRemainder(true);
                        context.abort(e);
                    } 
                }
            }

        }


        @SuppressWarnings({"unchecked"})
        @Override
        protected boolean onHttpPacketParsed(HttpHeader httpHeader, FilterChainContext ctx) {

            boolean result;
            if (httpHeader.isSkipRemainder()) {
                clearResponse(ctx.getConnection());
                cleanup(ctx, provider);
                return false;
            }

            result = super.onHttpPacketParsed(httpHeader, ctx);

            final HttpTransactionContext context = cleanup(ctx, provider);

            final AsyncHandler handler = context.handler;
            if (handler != null) {
                try {
                    context.result(handler.onCompleted());
                } catch (Exception e) {
                    context.abort(e);
                }
            } else {
                context.done(null);
            }

            return result;
        }


        // ----------------------------------------------------- Private Methods


        private static boolean isRedirectAllowed(final HttpTransactionContext ctx) {
            boolean allowed = ctx.request.isRedirectEnabled();
            if (ctx.request.isRedirectOverrideSet()) {
                return allowed;
            }
            if (!allowed) {
                allowed = ctx.redirectsAllowed;
            }
            return allowed;
        }

        private static HttpTransactionContext cleanup(final FilterChainContext ctx,
                                                      final GrizzlyAsyncHttpProvider provider) {

            final Connection c = ctx.getConnection();
            final HttpTransactionContext context =
                    provider.getHttpTransactionContext(c);
            context.provider.setHttpTransactionContext(c, null);
            if (!context.provider.connectionManager.canReturnConnection(c)) {
                context.abort(new IOException("Maximum pooled connections exceeded"));
            } else {
                if (!context.provider.connectionManager.returnConnection(context.requestUrl, c)) {
                    ctx.getConnection().close().markForRecycle(true);
                }
            }

            return context;

        }


        private static URI getURI(String url) {

            return AsyncHttpProviderUtils.createUri(url);

        }


        private static boolean redirectCountExceeded(final HttpTransactionContext context) {

            return (context.redirectCount.get() > context.maxRedirectCount);

        }


        private static boolean isRedirect(final int status) {

            return HttpStatus.MOVED_PERMANENTLY_301.statusMatches(status)
                    || HttpStatus.FOUND_302.statusMatches(status)
                    || HttpStatus.SEE_OTHER_303.statusMatches(status)
                    || HttpStatus.TEMPORARY_REDIRECT_307.statusMatches(status);

        }


        // ------------------------------------------------------- Inner Classes


        private static final class AuthorizationHandler implements StatusHandler {

            private static final AuthorizationHandler INSTANCE =
                    new AuthorizationHandler();

            // -------------------------------------- Methods from StatusHandler


            public boolean handlesStatus(int statusCode) {
                return (HttpStatus.UNAUTHORIZED_401.statusMatches(statusCode));
            }

            @SuppressWarnings({"unchecked"})
            public boolean handleStatus(final HttpResponsePacket responsePacket,
                                     final HttpTransactionContext httpTransactionContext,
                                     final FilterChainContext ctx) {

                final String auth = responsePacket.getHeader(Header.WWWAuthenticate);
                if (auth == null) {
                    throw new IllegalStateException("401 response received, but no WWW-Authenticate header was present");
                }

                Realm realm = httpTransactionContext.request.getRealm();
                if (realm == null) {
                    realm = httpTransactionContext.provider.clientConfig.getRealm();
                }
                if (realm == null) {
                    httpTransactionContext.invocationStatus = InvocationStatus.STOP;
                    return true;
                }

                responsePacket.setSkipRemainder(true); // ignore the remainder of the response

                final Request req = httpTransactionContext.request;
                realm = new Realm.RealmBuilder().clone(realm)
                                .setScheme(realm.getAuthScheme())
                                .setUri(URI.create(httpTransactionContext.requestUrl).getPath())
                                .setMethodName(req.getMethod())
                                .setUsePreemptiveAuth(true)
                                .parseWWWAuthenticateHeader(auth)
                                .build();
                if (auth.toLowerCase().startsWith("basic")) {
                    req.getHeaders().remove(Header.Authorization.toString());
                    try {
                        req.getHeaders().add(Header.Authorization.toString(),
                                             AuthenticatorUtils.computeBasicAuthentication(realm));
                    } catch (UnsupportedEncodingException ignored) {
                    }
                } else if (auth.toLowerCase().startsWith("digest")) {
                    req.getHeaders().remove(Header.Authorization.toString());
                    try {
                        req.getHeaders().add(Header.Authorization.toString(),
                                             AuthenticatorUtils.computeDigestAuthentication(realm));
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException("Digest authentication not supported", e);
                    } catch (UnsupportedEncodingException e) {
                        throw new IllegalStateException("Unsupported encoding.", e);
                    }
                } else {
                    throw new IllegalStateException("Unsupported authorization method: " + auth);
                }

                final ConnectionManager m = httpTransactionContext.provider.connectionManager;
                try {
                    final Connection c = m.obtainConnection(req,
                                                            httpTransactionContext.future);
                    final HttpTransactionContext newContext =
                            httpTransactionContext.copy();
                    httpTransactionContext.future = null;
                    httpTransactionContext.provider.setHttpTransactionContext(c, newContext);
                    newContext.invocationStatus = InvocationStatus.STOP;
                    try {
                        httpTransactionContext.provider.execute(c,
                                                                req,
                                                                httpTransactionContext.handler,
                                                                httpTransactionContext.future);
                        return false;
                    } catch (IOException ioe) {
                        newContext.abort(ioe);
                        return false;
                    }
                } catch (Exception e) {
                    httpTransactionContext.abort(e);
                }
                httpTransactionContext.invocationStatus = InvocationStatus.STOP;
                return false;
            }

        } // END AuthorizationHandler


        private static final class RedirectHandler implements StatusHandler {

            private static final RedirectHandler INSTANCE = new RedirectHandler();


            // ------------------------------------------ Methods from StatusHandler


            public boolean handlesStatus(int statusCode) {
                return (isRedirect(statusCode));
            }

            @SuppressWarnings({"unchecked"})
            public boolean handleStatus(final HttpResponsePacket responsePacket,
                                        final HttpTransactionContext httpTransactionContext,
                                        final FilterChainContext ctx) {

                final String redirectURL = responsePacket.getHeader(Header.Location);
                if (redirectURL == null) {
                    throw new IllegalStateException("redirect received, but no location header was present");
                }

                URI orig;
                if (httpTransactionContext.lastRedirectURI == null) {
                    orig = AsyncHttpProviderUtils.createUri(httpTransactionContext.requestUrl);
                } else {
                    orig = AsyncHttpProviderUtils.getRedirectUri(AsyncHttpProviderUtils.createUri(httpTransactionContext.requestUrl),
                                                                 httpTransactionContext.lastRedirectURI);
                }
                httpTransactionContext.lastRedirectURI = redirectURL;
                Request requestToSend;
                URI uri = AsyncHttpProviderUtils.getRedirectUri(orig, redirectURL);
                if (!uri.toString().equalsIgnoreCase(orig.toString())) {
                    requestToSend = newRequest(uri,
                                               responsePacket,
                                               httpTransactionContext,
                                               sendAsGet(responsePacket,  httpTransactionContext));
                } else {
                    httpTransactionContext.statusHandler = null;
                    httpTransactionContext.invocationStatus = InvocationStatus.CONTINUE;
                        try {
                            httpTransactionContext.handler.onStatusReceived(httpTransactionContext.responseStatus);
                        } catch (Exception e) {
                            httpTransactionContext.abort(e);
                        }
                    return true;
                }

                final ConnectionManager m = httpTransactionContext.provider.connectionManager;
                try {
                    final Connection c = m.obtainConnection(requestToSend,
                                                            httpTransactionContext.future);
                    if (switchingSchemes(orig, uri)) {
                        try {
                            notifySchemeSwitch(ctx, c, uri);
                        } catch (IOException ioe) {
                            httpTransactionContext.abort(ioe);
                        }
                    }
                    final HttpTransactionContext newContext =
                            httpTransactionContext.copy();
                    httpTransactionContext.future = null;
                    newContext.invocationStatus = InvocationStatus.CONTINUE;
                    newContext.request = requestToSend;
                    newContext.requestUrl = requestToSend.getUrl();
                    httpTransactionContext.provider.setHttpTransactionContext(c, newContext);
                    httpTransactionContext.provider.execute(c,
                                                            requestToSend,
                                                            newContext.handler,
                                                            newContext.future);
                    return false;
                } catch (Exception e) {
                    httpTransactionContext.abort(e);
                }

                httpTransactionContext.invocationStatus = InvocationStatus.CONTINUE;
                return true;

            }


            // ------------------------------------------------- Private Methods

            private boolean sendAsGet(final HttpResponsePacket response,
                                      final HttpTransactionContext ctx) {
                final int statusCode = response.getStatus();
                return !(statusCode < 302 || statusCode > 303)
                          && !(statusCode == 302
                             && ctx.provider.clientConfig.isStrict302Handling());
            }


            private boolean switchingSchemes(final URI oldUri,
                                             final URI newUri) {

                return !oldUri.getScheme().equals(newUri.getScheme());

            }

            private void notifySchemeSwitch(final FilterChainContext ctx,
                                            final Connection c,
                                            final URI uri) throws IOException {

                ctx.notifyDownstream(new SwitchingSSLFilter.SSLSwitchingEvent(
                                               "https".equals(uri.getScheme()), c));
            }

        } // END RedirectHandler


        // ----------------------------------------------------- Private Methods


        private static Request newRequest(final URI uri,
                                          final HttpResponsePacket response,
                                          final HttpTransactionContext ctx,
                                          boolean asGet) {

            final RequestBuilder builder = new RequestBuilder(ctx.request);
            if (asGet) {
                builder.setMethod("GET");
            }
            builder.setUrl(uri.toString());

            if (ctx.provider.clientConfig.isRemoveQueryParamOnRedirect()) {
                builder.setQueryParameters(null);
            }
            for (String cookieStr : response.getHeaders().values(Header.Cookie)) {
                Cookie c = AsyncHttpProviderUtils.parseCookie(cookieStr);
                builder.addOrReplaceCookie(c);
            }
            return builder.build();

        }


    } // END AsyncHttpClientEventFilter


    private static final class ClientEncodingFilter implements EncodingFilter {


        // ----------------------------------------- Methods from EncodingFilter


        public boolean applyEncoding(HttpHeader httpPacket) {

           httpPacket.addHeader(Header.AcceptEncoding, "gzip");
           return true;

        }


        public boolean applyDecoding(HttpHeader httpPacket) {

            final HttpResponsePacket httpResponse = (HttpResponsePacket) httpPacket;
            final DataChunk bc = httpResponse.getHeaders().getValue(Header.ContentEncoding);
            return bc != null && bc.indexOf("gzip", 0) != -1;

        }


    } // END ClientContentEncoding


    private static final class NonCachingPool implements ConnectionsPool<String,Connection> {


        // ---------------------------------------- Methods from ConnectionsPool


        public boolean offer(String uri, Connection connection) {
            return false;
        }

        public Connection poll(String uri) {
            return null;
        }

        public boolean removeAll(Connection connection) {
            return false;
        }

        public boolean canCacheConnection() {
            return true;
        }

        public void destroy() {
            // no-op
        }

    } // END NonCachingPool


    private static interface BodyHandler {

        static int MAX_CHUNK_SIZE = 8192;

        boolean handlesBodyType(final Request request);

        boolean doHandle(final FilterChainContext ctx,
                      final Request request,
                      final HttpRequestPacket requestPacket) throws IOException;

    } // END BodyHandler


    private final class BodyHandlerFactory {

        private final BodyHandler[] HANDLERS = new BodyHandler[] {
            new StringBodyHandler(),
            new ByteArrayBodyHandler(),
            new ParamsBodyHandler(),
            new EntityWriterBodyHandler(),
            new StreamDataBodyHandler(),
            new PartsBodyHandler(),
            new FileBodyHandler(),
            new BodyGeneratorBodyHandler()
        };

        public BodyHandler getBodyHandler(final Request request) {
            for (final BodyHandler h : HANDLERS) {
                if (h.handlesBodyType(request)) {
                    return h;
                }
            }
            return new NoBodyHandler();
        }

    } // END BodyHandlerFactory


    private static final class ExpectHandler implements BodyHandler {

        private final BodyHandler delegate;
        private Request request;
        private HttpRequestPacket requestPacket;

        // -------------------------------------------------------- Constructors


        private ExpectHandler(final BodyHandler delegate) {

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


    private final class ByteArrayBodyHandler implements BodyHandler {


        // -------------------------------------------- Methods from BodyHandler

        public boolean handlesBodyType(final Request request) {
            return (request.getByteData() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            String charset = request.getBodyEncoding();
            if (charset == null) {
                charset = Charsets.DEFAULT_CHARACTER_ENCODING;
            }
            final byte[] data = new String(request.getByteData(), charset).getBytes(charset);
            final MemoryManager mm = ctx.getMemoryManager();
            final Buffer gBuffer = Buffers.wrap(mm, data);
            if (requestPacket.getContentLength() == -1) {
                    if (!clientConfig.isCompressionEnabled()) {
                        requestPacket.setContentLengthLong(data.length);
                    }
                }
            final HttpContent content = requestPacket.httpContentBuilder().content(gBuffer).build();
            content.setLast(true);
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            return true;
        }
    }


    private final class StringBodyHandler implements BodyHandler {


        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            return (request.getStringData() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            String charset = request.getBodyEncoding();
            if (charset == null) {
                charset = Charsets.DEFAULT_CHARACTER_ENCODING;
            }
            final byte[] data = request.getStringData().getBytes(charset);
            final MemoryManager mm = ctx.getMemoryManager();
            final Buffer gBuffer = Buffers.wrap(mm, data);
            if (requestPacket.getContentLength() == -1) {
                if (!clientConfig.isCompressionEnabled()) {
                    requestPacket.setContentLengthLong(data.length);
                }
            }
            final HttpContent content = requestPacket.httpContentBuilder().content(gBuffer).build();
            content.setLast(true);
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            return true;
        }

    } // END StringBodyHandler


    private static final class NoBodyHandler implements BodyHandler {


        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            return false;
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final HttpContent content = requestPacket.httpContentBuilder().content(Buffers.EMPTY_BUFFER).build();
            content.setLast(true);
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            return true;
        }

    } // END NoBodyHandler


    private final class ParamsBodyHandler implements BodyHandler {


        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            final FluentStringsMap params = request.getParams();
            return (params != null && !params.isEmpty());
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            if (requestPacket.getContentType() == null) {
                requestPacket.setContentType("application/x-www-form-urlencoded");
            }
            StringBuilder sb = null;
            String charset = request.getBodyEncoding();
            if (charset == null) {
                charset = Charsets.DEFAULT_CHARACTER_ENCODING;
            }
            final FluentStringsMap params = request.getParams();
            if (!params.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : params.entrySet()) {
                    String name = entry.getKey();
                    List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        if (sb == null) {
                            sb = new StringBuilder(128);
                        }
                        for (String value : values) {
                            if (sb.length() > 0) {
                                sb.append('&');
                            }
                            sb.append(URLEncoder.encode(name, charset))
                                    .append('=').append(URLEncoder.encode(value, charset));
                        }
                    }
                }
            }
            if (sb != null) {
                final byte[] data = sb.toString().getBytes(charset);
                final MemoryManager mm = ctx.getMemoryManager();
                final Buffer gBuffer = Buffers.wrap(mm, data);
                final HttpContent content = requestPacket.httpContentBuilder().content(gBuffer).build();
                if (requestPacket.getContentLength() == -1) {
                    if (!clientConfig.isCompressionEnabled()) {
                        requestPacket.setContentLengthLong(data.length);
                    }
                }
                content.setLast(true);
                ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            }
            return true;
        }

    } // END ParamsBodyHandler


    private static final class EntityWriterBodyHandler implements BodyHandler {

        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            return (request.getEntityWriter() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final MemoryManager mm = ctx.getMemoryManager();
            Buffer b = mm.allocate(512);
            BufferOutputStream o = new BufferOutputStream(mm, b, true);
            final Request.EntityWriter writer = request.getEntityWriter();
            writer.writeEntity(o);
            b = o.getBuffer();
            b.trim();
            if (b.hasRemaining()) {
                final HttpContent content = requestPacket.httpContentBuilder().content(b).build();
                content.setLast(true);
                ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            }

            return true;
        }

    } // END EntityWriterBodyHandler


    private static final class StreamDataBodyHandler implements BodyHandler {

        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            return (request.getStreamData() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final MemoryManager mm = ctx.getMemoryManager();
            Buffer buffer = mm.allocate(512);
            final byte[] b = new byte[512];
            int read;
            final InputStream in = request.getStreamData();
            try {
                in.reset();
            } catch (IOException ioe) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(ioe.toString(), ioe);
                }
            }
            if (in.markSupported()) {
                in.mark(0);
            }

            while ((read = in.read(b)) != -1) {
                if (read > buffer.remaining()) {
                    buffer = mm.reallocate(buffer, buffer.capacity() + 512);
                }
                buffer.put(b, 0, read);
            }
            buffer.trim();
            if (buffer.hasRemaining()) {
                final HttpContent content = requestPacket.httpContentBuilder().content(buffer).build();
                buffer.allowBufferDispose(false);
                content.setLast(true);
                ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            }
            
            return true;
        }

    } // END StreamDataBodyHandler


    private static final class PartsBodyHandler implements BodyHandler {

        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            final List<Part> parts = request.getParts();
            return (parts != null && !parts.isEmpty());
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            MultipartRequestEntity mre =
                    AsyncHttpProviderUtils.createMultipartRequestEntity(
                            request.getParts(),
                            request.getParams());
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


    private final class FileBodyHandler implements BodyHandler {

        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            return (request.getFile() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final File f = request.getFile();
            requestPacket.setContentLengthLong(f.length());
            final HttpTransactionContext context = getHttpTransactionContext(ctx.getConnection());
            if (!SEND_FILE_SUPPORT || requestPacket.isSecure()) {
                final FileInputStream fis = new FileInputStream(request.getFile());
                final MemoryManager mm = ctx.getMemoryManager();
                AtomicInteger written = new AtomicInteger();
                boolean last = false;
                try {
                    for (byte[] buf = new byte[MAX_CHUNK_SIZE]; !last; ) {
                        Buffer b = null;
                        int read;
                        if ((read = fis.read(buf)) < 0) {
                            last = true;
                            b = Buffers.EMPTY_BUFFER;
                        }
                        if (b != Buffers.EMPTY_BUFFER) {
                            written.addAndGet(read);
                            b = Buffers.wrap(mm, buf, 0, read);
                        }

                        final HttpContent content =
                                requestPacket.httpContentBuilder().content(b).
                                        last(last).build();
                        ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
                    }
                } finally {
                    try {
                        fis.close();
                    } catch (IOException ignored) {
                    }
                }
            } else {
                // write the headers
                ctx.write(requestPacket, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
                ctx.write(new FileTransfer(f), new EmptyCompletionHandler<WriteResult>() {

                    @Override
                    public void updated(WriteResult result) {
                        final AsyncHandler handler = context.handler;
                        if (handler != null) {
                            if (TransferCompletionHandler.class.isAssignableFrom(handler.getClass())) {
                                final long written = result.getWrittenSize();
                                final long total = context.totalBodyWritten.addAndGet(written);
                                ((TransferCompletionHandler) handler).onContentWriteProgress(
                                        written,
                                        total,
                                        requestPacket.getContentLength());
                            }
                        }
                    }
                });
            }

            return true;
        }

    } // END FileBodyHandler


    private static final class BodyGeneratorBodyHandler implements BodyHandler {

        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            return (request.getBodyGenerator() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final BodyGenerator generator = request.getBodyGenerator();
            final Body bodyLocal = generator.createBody();
            final long len = bodyLocal.getContentLength();
            if (len > 0) {
                requestPacket.setContentLengthLong(len);
            } else {
                requestPacket.setChunked(true);
            }

            final MemoryManager mm = ctx.getMemoryManager();
            boolean last = false;

            while (!last) {
                Buffer buffer = mm.allocate(MAX_CHUNK_SIZE);
                buffer.allowBufferDispose(true);
                
                final long readBytes = bodyLocal.read(buffer.toByteBuffer());
                if (readBytes > 0) {
                    buffer.position((int) readBytes);
                    buffer.trim();
                } else {
                    buffer.dispose();
                    
                    if (readBytes < 0) {
                        last = true;
                        buffer = Buffers.EMPTY_BUFFER;
                    } else {
                        // pass the context to bodyLocal to be able to
                        // continue body transferring once more data is available
                        if (generator instanceof FeedableBodyGenerator) {
                            ((FeedableBodyGenerator) generator).initializeAsynchronousTransfer(ctx, requestPacket);
                            return false;
                        } else {
                            throw new IllegalStateException("BodyGenerator unexpectedly returned 0 bytes available");
                        }
                    }
                }

                final HttpContent content =
                        requestPacket.httpContentBuilder().content(buffer).
                                last(last).build();
                ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            }
            
            return true;
        }

    } // END BodyGeneratorBodyHandler


    static class ConnectionManager {

        private static final Attribute<Boolean> DO_NOT_CACHE =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(ConnectionManager.class.getName());
        private final ConnectionsPool<String,Connection> pool;
        private final TCPNIOConnectorHandler connectionHandler;
        private final ConnectionMonitor connectionMonitor;
        private final GrizzlyAsyncHttpProvider provider;

        // -------------------------------------------------------- Constructors

        ConnectionManager(final GrizzlyAsyncHttpProvider provider,
                          final TCPNIOTransport transport) {

            ConnectionsPool<String,Connection> connectionPool;
            this.provider = provider;
            final AsyncHttpClientConfig config = provider.clientConfig;
            if (config.getAllowPoolingConnection()) {
                ConnectionsPool pool = config.getConnectionsPool();
                if (pool != null) {
                    //noinspection unchecked
                    connectionPool = (ConnectionsPool<String, Connection>) pool;
                } else {
                    connectionPool = new GrizzlyConnectionsPool((config));
                }
            } else {
                connectionPool = new NonCachingPool();
            }
            pool = connectionPool;
            connectionHandler = TCPNIOConnectorHandler.builder(transport).build();
            final int maxConns = provider.clientConfig.getMaxTotalConnections();
            connectionMonitor = new ConnectionMonitor(maxConns);


        }

        // ----------------------------------------------------- Private Methods

        static void markConnectionAsDoNotCache(final Connection c) {
            DO_NOT_CACHE.set(c, Boolean.TRUE);
        }

        static boolean isConnectionCacheable(final Connection c) {
            final Boolean canCache =  DO_NOT_CACHE.get(c);
            return ((canCache != null) ? canCache : false);
        }

        void doAsyncTrackedConnection(final Request request,
                                      final GrizzlyResponseFuture requestFuture,
                                      final CompletionHandler<Connection> connectHandler)
        throws IOException, ExecutionException, InterruptedException {
            final String url = request.getUrl();
            Connection c = pool.poll(AsyncHttpProviderUtils.getBaseUrl(url));
            if (c == null) {
                if (!connectionMonitor.acquire()) {
                    throw new IOException("Max connections exceeded");
                }
                doAsyncConnect(url, request, requestFuture, connectHandler);
            } else {
                provider.touchConnection(c, request);
                connectHandler.completed(c);
            }

        }

        Connection obtainConnection(final Request request,
                                    final GrizzlyResponseFuture requestFuture)
        throws IOException, ExecutionException, InterruptedException, TimeoutException {

            final Connection c = (obtainConnection0(request.getUrl(),
                                                    request,
                                                    requestFuture));
            DO_NOT_CACHE.set(c, Boolean.TRUE);
            return c;

        }

        void doAsyncConnect(final String url,
                            final Request request,
                            final GrizzlyResponseFuture requestFuture,
                            final CompletionHandler<Connection> connectHandler)
        throws IOException, ExecutionException, InterruptedException {

            final URI uri = AsyncHttpProviderUtils.createUri(url);
            ProxyServer proxy = getProxyServer(request);
            if (ProxyUtils.avoidProxy(proxy, request)) {
                proxy = null;
            }
            String host = ((proxy != null) ? proxy.getHost() : uri.getHost());
            int port = ((proxy != null) ? proxy.getPort() : uri.getPort());
            if(request.getLocalAddress()!=null) {
                connectionHandler.connect(new InetSocketAddress(host, getPort(uri, port)), new InetSocketAddress(request.getLocalAddress(), 0),
                        createConnectionCompletionHandler(request, requestFuture, connectHandler));
            } else {
                connectionHandler.connect(new InetSocketAddress(host, getPort(uri, port)),
                        createConnectionCompletionHandler(request, requestFuture, connectHandler));
            }

        }

        private Connection obtainConnection0(final String url,
                                             final Request request,
                                             final GrizzlyResponseFuture requestFuture)
        throws IOException, ExecutionException, InterruptedException, TimeoutException {

            final URI uri = AsyncHttpProviderUtils.createUri(url);
            ProxyServer proxy = getProxyServer(request);
            if (ProxyUtils.avoidProxy(proxy, request)) {
                proxy = null;
            }
            String host = ((proxy != null) ? proxy.getHost() : uri.getHost());
            int port = ((proxy != null) ? proxy.getPort() : uri.getPort());
            int cTimeout = provider.clientConfig.getConnectionTimeoutInMs();
            FutureImpl<Connection> future = Futures.createSafeFuture();
            CompletionHandler<Connection> ch = Futures.toCompletionHandler(future,
                    createConnectionCompletionHandler(request, requestFuture, null));
            if (cTimeout > 0) {
                connectionHandler.connect(new InetSocketAddress(host, getPort(uri, port)),
                        ch);
                return future.get(cTimeout, TimeUnit.MILLISECONDS);
            } else {
                connectionHandler.connect(new InetSocketAddress(host, getPort(uri, port)),
                        ch);
                return future.get();
            }
        }

        private ProxyServer getProxyServer(Request request) {

            ProxyServer proxyServer = request.getProxyServer();
            if (proxyServer == null) {
                proxyServer = provider.clientConfig.getProxyServer();
            }
            return proxyServer;

        }

        boolean returnConnection(final String url, final Connection c) {
            final boolean result = (DO_NOT_CACHE.get(c) == null
                                       && pool.offer(AsyncHttpProviderUtils.getBaseUrl(url), c));
            if (result) {
                if (provider.resolver != null) {
                    provider.resolver.setTimeoutMillis(c, IdleTimeoutFilter.FOREVER);
                }
            }
            return result;

        }


        boolean canReturnConnection(final Connection c) {

            return (DO_NOT_CACHE.get(c) != null || pool.canCacheConnection());

        }


        void destroy() {

            pool.destroy();

        }

        CompletionHandler<Connection> createConnectionCompletionHandler(final Request request,
                                                                        final GrizzlyResponseFuture future,
                                                                        final CompletionHandler<Connection> wrappedHandler) {
            return new CompletionHandler<Connection>() {
                public void cancelled() {
                    if (wrappedHandler != null) {
                        wrappedHandler.cancelled();
                    } else {
                        future.cancel(true);
                    }
                }

                public void failed(Throwable throwable) {
                    if (wrappedHandler != null) {
                        wrappedHandler.failed(throwable);
                    } else {
                        future.abort(throwable);
                    }
                }

                public void completed(Connection connection) {
                    future.setConnection(connection);
                    provider.touchConnection(connection, request);
                    if (wrappedHandler != null) {
                        connection.addCloseListener(connectionMonitor);
                        wrappedHandler.completed(connection);
                    }
                }

                public void updated(Connection result) {
                    if (wrappedHandler != null) {
                        wrappedHandler.updated(result);
                    }
                }
            };
        }

        // ------------------------------------------------------ Nested Classes

        private static class ConnectionMonitor implements Connection.CloseListener {

        private final Semaphore connections;

            // ------------------------------------------------------------ Constructors


            ConnectionMonitor(final int maxConnections) {
                if (maxConnections != -1) {
                    connections = new Semaphore(maxConnections);
                } else {
                    connections = null;
                }
            }

            // ----------------------------------- Methods from Connection.CloseListener


            public boolean acquire() {

                return (connections == null || connections.tryAcquire());

            }

            @Override
            public void onClosed(Connection connection, Connection.CloseType closeType) throws IOException {

                if (connections != null) {
                    connections.release();
                }

            }

        } // END ConnectionMonitor

    } // END ConnectionManager

    static final class SwitchingSSLFilter extends SSLFilter {

        private final boolean secureByDefault;
        final Attribute<Boolean> CONNECTION_IS_SECURE =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(SwitchingSSLFilter.class.getName());

        // -------------------------------------------------------- Constructors


        SwitchingSSLFilter(final SSLEngineConfigurator clientConfig,
                           final boolean secureByDefault) {

            super(null, clientConfig);
            this.secureByDefault = secureByDefault;

        }


        // ---------------------------------------------- Methods from SSLFilter


        @Override
        public NextAction handleEvent(FilterChainContext ctx, FilterChainEvent event) throws IOException {

            if (event.type() == SSLSwitchingEvent.class) {
                final SSLSwitchingEvent se = (SSLSwitchingEvent) event;
                CONNECTION_IS_SECURE.set(se.connection, se.secure);
                return ctx.getStopAction();
            }
            return ctx.getInvokeAction();

        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {

            if (isSecure(ctx.getConnection())) {
                return super.handleRead(ctx);
            }
            return ctx.getInvokeAction();

        }

        @Override
        public NextAction handleWrite(FilterChainContext ctx) throws IOException {

            if (isSecure(ctx.getConnection())) {
                return super.handleWrite(ctx);
            }
            return ctx.getInvokeAction();

        }


        // ----------------------------------------------------- Private Methods


        private boolean isSecure(final Connection c) {

            Boolean secStatus = CONNECTION_IS_SECURE.get(c);
            if (secStatus == null) {
                secStatus = secureByDefault;
            }
            return secStatus;

        }


        // ------------------------------------------------------ Nested Classes

        static final class SSLSwitchingEvent implements FilterChainEvent {

            final boolean secure;
            final Connection connection;

            // ---------------------------------------------------- Constructors


            SSLSwitchingEvent(final boolean secure, final Connection c) {

                this.secure = secure;
                connection = c;

            }

            // ----------------------------------- Methods from FilterChainEvent


            @Override
            public Object type() {
                return SSLSwitchingEvent.class;
            }

        } // END SSLSwitchingEvent

    } // END SwitchingSSLFilter

    private static final class GrizzlyTransferAdapter extends TransferCompletionHandler.TransferAdapter {


        // -------------------------------------------------------- Constructors


        public GrizzlyTransferAdapter(FluentCaseInsensitiveStringsMap headers) throws IOException {
            super(headers);
        }


        // ---------------------------------------- Methods from TransferAdapter


        @Override
        public void getBytes(byte[] bytes) {
            // TODO implement
        }

    } // END GrizzlyTransferAdapter
    
    
    private static final class GrizzlyWebSocketAdapter implements WebSocket {
        
        private final org.glassfish.grizzly.websockets.WebSocket gWebSocket;

        // -------------------------------------------------------- Constructors
        
        
        GrizzlyWebSocketAdapter(final org.glassfish.grizzly.websockets.WebSocket gWebSocket) {
            this.gWebSocket = gWebSocket;            
        }
        
        
        // ------------------------------------------ Methods from AHC WebSocket
        
        
        @Override
        public WebSocket sendMessage(byte[] message) {
            gWebSocket.send(message);
            return this;
        }

        @Override
        public WebSocket stream(byte[] fragment, boolean last) {
            if (fragment != null && fragment.length > 0) {
                gWebSocket.stream(last, fragment, 0, fragment.length);
            }
            return this;
        }

        @Override
        public WebSocket stream(byte[] fragment, int offset, int len, boolean last) {
            if (fragment != null && fragment.length > 0) {
                gWebSocket.stream(last, fragment, offset, len);
            }
            return this;
        }

        @Override
        public WebSocket sendTextMessage(String message) {
            gWebSocket.send(message);
            return this;
        }

        @Override
        public WebSocket streamText(String fragment, boolean last) {
            gWebSocket.stream(last, fragment);
            return this;
        }

        @Override
        public WebSocket sendPing(byte[] payload) {
            gWebSocket.sendPing(payload);
            return this;
        }

        @Override
        public WebSocket sendPong(byte[] payload) {
            gWebSocket.sendPong(payload);
            return this;
        }

        @Override
        public WebSocket addWebSocketListener(WebSocketListener l) {
            gWebSocket.add(new AHCWebSocketListenerAdapter(l, this));
            return this;
        }

        @Override
        public WebSocket removeWebSocketListener(WebSocketListener l) {
            gWebSocket.remove(new AHCWebSocketListenerAdapter(l, this));
            return this;
        }

        @Override
        public boolean isOpen() {
            return gWebSocket.isConnected();
        }

        @Override
        public void close() {
            gWebSocket.close();
        }
        
    } // END GrizzlyWebSocketAdapter


    private static final class AHCWebSocketListenerAdapter implements org.glassfish.grizzly.websockets.WebSocketListener {

        private final WebSocketListener ahcListener;
        private final WebSocket webSocket;

        // -------------------------------------------------------- Constructors


        AHCWebSocketListenerAdapter(final WebSocketListener ahcListener, WebSocket webSocket) {
            this.ahcListener = ahcListener;
            this.webSocket = webSocket;
        }


        // ------------------------------ Methods from Grizzly WebSocketListener


        @Override
        public void onClose(org.glassfish.grizzly.websockets.WebSocket gWebSocket, DataFrame dataFrame) {
            try {
                if (WebSocketCloseCodeReasonListener.class.isAssignableFrom(ahcListener.getClass())) {
                    ClosingFrame cf = ClosingFrame.class.cast(dataFrame);
                    WebSocketCloseCodeReasonListener.class.cast(ahcListener).onClose(webSocket, cf.getCode(), cf.getReason());
                } else {
                    ahcListener.onClose(webSocket);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onConnect(org.glassfish.grizzly.websockets.WebSocket gWebSocket) {
            try {
                ahcListener.onOpen(webSocket);
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onMessage(org.glassfish.grizzly.websockets.WebSocket webSocket, String s) {
            try {
                if (WebSocketTextListener.class.isAssignableFrom(ahcListener.getClass())) {
                    WebSocketTextListener.class.cast(ahcListener).onMessage(s);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onMessage(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
            try {
                if (WebSocketByteListener.class.isAssignableFrom(ahcListener.getClass())) {
                    WebSocketByteListener.class.cast(ahcListener).onMessage(bytes);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onPing(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
            try {
                if (WebSocketPingListener.class.isAssignableFrom(ahcListener.getClass())) {
                    WebSocketPingListener.class.cast(ahcListener).onPing(bytes);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onPong(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
            try {
                if (WebSocketPongListener.class.isAssignableFrom(ahcListener.getClass())) {
                    WebSocketPongListener.class.cast(ahcListener).onPong(bytes);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onFragment(org.glassfish.grizzly.websockets.WebSocket webSocket, String s, boolean b) {
            try {
                if (WebSocketTextListener.class.isAssignableFrom(ahcListener.getClass())) {
                    WebSocketTextListener.class.cast(ahcListener).onFragment(s, b);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onFragment(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes, boolean b) {
            try {
                if (WebSocketByteListener.class.isAssignableFrom(ahcListener.getClass())) {
                    WebSocketByteListener.class.cast(ahcListener).onFragment(bytes, b);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            AHCWebSocketListenerAdapter that = (AHCWebSocketListenerAdapter) o;

            if (ahcListener != null ? !ahcListener.equals(that.ahcListener) : that.ahcListener != null)
                return false;
            if (webSocket != null ? !webSocket.equals(that.webSocket) : that.webSocket != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = ahcListener != null ? ahcListener.hashCode() : 0;
            result = 31 * result + (webSocket != null ? webSocket.hashCode() : 0);
            return result;
        }
    } // END AHCWebSocketListenerAdapter
    
}



