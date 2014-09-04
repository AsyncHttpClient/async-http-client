/*
 * Copyright (c) 2012-2014 Sonatype, Inc. All rights reserved.
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

import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.BUFFER_WEBSOCKET_FRAGMENTS;
import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.MAX_HTTP_PACKET_HEADER_SIZE;
import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.TRANSPORT_CUSTOMIZER;
import static com.ning.http.util.AsyncHttpProviderUtils.getNonEmptyPath;
import static com.ning.http.util.MiscUtils.isNonEmpty;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.FileTransfer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
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
import org.glassfish.grizzly.http.util.CookieSerializerUtils;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.RoundRobinConnectionDistributor;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.websockets.ClosingFrame;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.HandShake;
import org.glassfish.grizzly.websockets.ProtocolHandler;
import org.glassfish.grizzly.websockets.SimpleWebSocket;
import org.glassfish.grizzly.websockets.Version;
import org.glassfish.grizzly.websockets.WebSocketFilter;
import org.glassfish.grizzly.websockets.WebSocketHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHandlerExtensions;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.MaxRedirectException;
import com.ning.http.client.Param;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.UpgradeHandler;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.cookie.CookieDecoder;
import com.ning.http.client.filter.FilterContext;
import com.ning.http.client.filter.ResponseFilter;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.multipart.MultipartBody;
import com.ning.http.client.multipart.MultipartUtils;
import com.ning.http.client.multipart.Part;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.uri.Uri;
import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketByteListener;
import com.ning.http.client.websocket.WebSocketCloseCodeReasonListener;
import com.ning.http.client.websocket.WebSocketListener;
import com.ning.http.client.websocket.WebSocketPingListener;
import com.ning.http.client.websocket.WebSocketPongListener;
import com.ning.http.client.websocket.WebSocketTextListener;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.ProxyUtils;
import com.ning.http.util.SslUtils;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import java.io.ByteArrayOutputStream;
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
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        SEND_FILE_SUPPORT = /*configSendFileSupport()*/ false;
    }
    private static final Attribute<HttpTransactionContext> REQUEST_STATE_ATTR =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(HttpTransactionContext.class.getName());
    private final static NTLMEngine ntlmEngine = new NTLMEngine();

    public static final IOException REMOTELY_CLOSED_EXCEPTION = new IOException("Remotely Closed");

    static {
        REMOTELY_CLOSED_EXCEPTION.setStackTrace(new StackTraceElement[] {});
    }
    
    private final BodyHandlerFactory bodyHandlerFactory = new BodyHandlerFactory();

    private final TCPNIOTransport clientTransport;
    private final AsyncHttpClientConfig clientConfig;
    private final GrizzlyAsyncHttpProviderConfig providerConfig;
    private final ConnectionManager connectionManager;

    DelayedExecutor.Resolver<Connection> resolver;
    private DelayedExecutor timeoutExecutor;




    // ------------------------------------------------------------ Constructors


    public GrizzlyAsyncHttpProvider(final AsyncHttpClientConfig clientConfig) {

        this.clientConfig = clientConfig;
        this.providerConfig =
                clientConfig.getAsyncHttpProviderConfig() instanceof GrizzlyAsyncHttpProviderConfig ?
                (GrizzlyAsyncHttpProviderConfig) clientConfig.getAsyncHttpProviderConfig()
                : new GrizzlyAsyncHttpProviderConfig();
        final TCPNIOTransportBuilder builder = TCPNIOTransportBuilder.newInstance();
        clientTransport = builder.build();
        initializeTransport(clientConfig);
        connectionManager = new ConnectionManager(this, clientTransport, providerConfig);
        try {
            clientTransport.start();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

    }


    // ------------------------------------------ Methods from AsyncHttpProvider


    @SuppressWarnings({"unchecked"})
    @Override
    public <T> ListenableFuture<T> execute(final Request request,
                                           final AsyncHandler<T> handler) throws IOException {

        if (clientTransport.isStopped()) {
            throw new IOException("AsyncHttpClient has been closed.");
        }
        final ProxyServer proxy = ProxyUtils.getProxyServer(clientConfig, request);
        final GrizzlyResponseFuture<T> future = new GrizzlyResponseFuture<T>(this, request, handler, proxy);
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
                    execute(c, request, handler, future, true);
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

    @Override
    public void close() {

        try {
            connectionManager.destroy();
            clientTransport.shutdownNow();
            final ExecutorService service = clientConfig.executorService();
            if (service != null) {
                service.shutdown();
            }
            if (timeoutExecutor != null) {
                timeoutExecutor.stop();
                timeoutExecutor.getThreadPool().shutdownNow();
            }
        } catch (IOException ignored) { }

    }

    // ------------------------------------------------------- Protected Methods


    @SuppressWarnings({"unchecked"})
    protected <T> ListenableFuture<T> execute(final Connection c,
                                              final Request request,
                                              final AsyncHandler<T> handler,
                                              final GrizzlyResponseFuture<T> future,
                                              final boolean forceTxContextExist)
    throws IOException {

        try {
            if (forceTxContextExist && HttpTransactionContext.get(c) == null) {
                HttpTransactionContext.set(c,
                        new HttpTransactionContext(this, future, request, handler));
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
        fcb.add(new TransportFilter());

        final int timeout = clientConfig.getRequestTimeout();
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
                                    HttpTransactionContext.get(ctx.getConnection());
                            if (context != null) {
                                if (context.isWSRequest) {
                                    return clientConfig.getWebSocketTimeout();
                                }
                                final long timeout = context.request.getRequestTimeout();
                                if (timeout > 0) {
                                    return timeout;
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
                context = SslUtils.getInstance().getSSLContext(clientConfig.isAcceptAnyCertificate());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        final SSLEngineConfigurator configurator =
                new SSLEngineConfigurator(context,
                        true,
                        false,
                        false);
        final SwitchingSSLFilter sslFilter =
                new SwitchingSSLFilter(configurator, defaultSecState);
        if (clientConfig.getHostnameVerifier() != null) {
            sslFilter.addHandshakeListener(new HostnameVerifierListener());
        }
        fcb.add(sslFilter);
        
        final AsyncHttpClientEventFilter eventFilter = new
                AsyncHttpClientEventFilter(this, (Integer) providerConfig.getProperty(MAX_HTTP_PACKET_HEADER_SIZE));
        final AsyncHttpClientFilter clientFilter =
                new AsyncHttpClientFilter(clientConfig);
        ContentEncoding[] encodings = eventFilter.getContentEncodings();
        if (encodings.length > 0) {
            for (ContentEncoding encoding : encodings) {
                eventFilter.removeContentEncoding(encoding);
            }
        }
        if (clientConfig.isCompressionEnforced()) {
            eventFilter.addContentEncoding(
                    new GZipContentEncoding(512,
                                            512,
                                            new ClientEncodingFilter()));
        }
        fcb.add(eventFilter);
        fcb.add(clientFilter);
        clientTransport.getAsyncQueueIO().getWriter()
                       .setMaxPendingBytesPerConnection(AsyncQueueWriter.AUTO_SIZE);
        
        clientTransport.setNIOChannelDistributor(
                new RoundRobinConnectionDistributor(clientTransport, false, false));
        
        final int kernelThreadsCount =
                clientConfig.getIoThreadMultiplier() *
                Runtime.getRuntime().availableProcessors();
        
        clientTransport.setSelectorRunnersCount(kernelThreadsCount);
        clientTransport.setKernelThreadPoolConfig(
                ThreadPoolConfig.defaultConfig()
                .setCorePoolSize(kernelThreadsCount)
                .setMaxPoolSize(kernelThreadsCount)
                .setPoolName("grizzly-ahc-kernel")
//                .setPoolName(discoverTestName("grizzly-ahc-kernel")) // uncomment for tests to track down the leaked threads
        );

        
        final TransportCustomizer customizer = (TransportCustomizer)
                providerConfig.getProperty(TRANSPORT_CUSTOMIZER);
        if (customizer != null) {
            customizer.customize(clientTransport, fcb);
        } else {
            doDefaultTransportConfig();
        }
        fcb.add(new WebSocketFilter());
        
        clientTransport.setProcessor(fcb.build());

    }


    // ------------------------------------------------- Package Private Methods


    void touchConnection(final Connection c, final Request request) {

        final long perRequestTimeout = request.getRequestTimeout();
        if (perRequestTimeout > 0) {
            final long newTimeout = System.currentTimeMillis() + perRequestTimeout;
            if (resolver != null) {
                resolver.setTimeoutMillis(c, newTimeout);
            }
        } else {
            final long timeout = clientConfig.getRequestTimeout();
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


    void timeout(final Connection c) {

        final HttpTransactionContext context = HttpTransactionContext.remove(c);
        context.abort(new TimeoutException("Timeout exceeded"));

    }

    static int getPort(final Uri uri, final int p) {
        int port = p;
        if (port == -1) {
            final String protocol = uri.getScheme().toLowerCase(Locale.ENGLISH);
            if ("http".equals(protocol) || "ws".equals(protocol)) {
                port = 80;
            } else if ("https".equals(protocol) || "wss".equals(protocol)) {
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
                     final HttpRequestPacket requestPacket,
                     final BodyHandler bodyHandler)
    throws IOException {

        boolean isWriteComplete = true;
        
        if (bodyHandler != null) { // Check if the HTTP request has body
            final HttpTransactionContext context = HttpTransactionContext.get(ctx.getConnection());
            
            context.bodyHandler = bodyHandler;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("REQUEST: " + requestPacket.toString());
            }
            isWriteComplete = bodyHandler.doHandle(ctx, request, requestPacket);
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("REQUEST: " + requestPacket.toString());
            }
            ctx.write(requestPacket, ctx.getTransportContext().getCompletionHandler());
        }

        
        return isWriteComplete;
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


    static final class HttpTransactionContext {

        final AtomicInteger redirectCount = new AtomicInteger(0);

        final int maxRedirectCount;
        final boolean redirectsAllowed;
        final GrizzlyAsyncHttpProvider provider;

        Request request;
        Uri requestUri;
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
        
        Uri wsRequestURI;
        boolean isWSRequest;
        HandShake handshake;
        ProtocolHandler protocolHandler;
        WebSocket webSocket;
        boolean establishingTunnel;
        
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
                            new GracefulCloseEvent(HttpTransactionContext.this), null);
                } else if (CloseType.REMOTELY.equals(type)) {
                    abort(REMOTELY_CLOSED_EXCEPTION);
                }
            }
        };

        // -------------------------------------------------------- Static methods
        
        static void set(final Connection c, final HttpTransactionContext httpTxContext) {
            c.addCloseListener(httpTxContext.listener);
            REQUEST_STATE_ATTR.set(c, httpTxContext);
        }

        static HttpTransactionContext remove(final Connection c) {
            final HttpTransactionContext httpTxContext = REQUEST_STATE_ATTR.remove(c);
            c.removeCloseListener(httpTxContext.listener);
            return httpTxContext;
        }

        static HttpTransactionContext get(final Connection c) {
            return REQUEST_STATE_ATTR.get(c);
        }
        
        // -------------------------------------------------------- Constructors


        HttpTransactionContext(final GrizzlyAsyncHttpProvider provider,
                               final GrizzlyResponseFuture future,
                               final Request request,
                               final AsyncHandler handler) {

            this.provider = provider;
            this.future = future;
            this.request = request;
            this.handler = handler;
            redirectsAllowed = provider.clientConfig.isFollowRedirect();
            maxRedirectCount = provider.clientConfig.getMaxRedirects();
            this.requestUri = request.getUri();

        }


        // ----------------------------------------------------- Private Methods


        HttpTransactionContext copy() {
            final HttpTransactionContext newContext =
                    new HttpTransactionContext(provider,
                                               future,
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

        boolean isGracefullyFinishResponseOnClose() {
            final HttpResponsePacket response = responseStatus.getResponse();
            return !response.getProcessingState().isKeepAlive() &&
                    !response.isChunked() && response.getContentLength() == -1;
        }

        void abort(final Throwable t) {
            if (future != null) {
                future.abort(t);
            }
        }

        void done() {
            if (future != null) {
                future.done();
            }
        }

        @SuppressWarnings({"unchecked"})
        void result(Object result) {
            if (future != null) {
                future.delegate.result(result);
                future.done();
            }
        }

        boolean isTunnelEstablished(final Connection c) {
            return c.getAttributes().getAttribute("tunnel-established") != null;
        }


        void tunnelEstablished(final Connection c) {
            c.getAttributes().setAttribute("tunnel-established", Boolean.TRUE);
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

    /**
     * {@link FilterChainEvent} to gracefully complete the request-response processing
     * when {@link Connection} is getting closed by the remote host.
     *
     * @since 1.8.7
     * @author The Grizzly Team
     */
    public static class GracefulCloseEvent implements FilterChainEvent {
        private final HttpTransactionContext httpTxContext;

        public GracefulCloseEvent(HttpTransactionContext httpTxContext) {
            this.httpTxContext = httpTxContext;
        }

        public HttpTransactionContext getHttpTxContext() {
            return httpTxContext;
        }

        @Override
        public Object type() {
            return GracefulCloseEvent.class;
        }
    }  // END GracefulCloseEvent
        
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

        // ----------------------------------------------------- Private Methods


        private boolean sendAsGrizzlyRequest(final Request request,
                                          final FilterChainContext ctx)
        throws IOException {
            
            final Connection connection = ctx.getConnection();
            
            final HttpTransactionContext httpCtx = HttpTransactionContext.get(connection);
            if (isUpgradeRequest(httpCtx.handler) && isWSRequest(httpCtx.requestUri)) {
                httpCtx.isWSRequest = true;
                convertToUpgradeRequest(httpCtx);
            }
            final Request req = httpCtx.request;
            final Uri uri = req.getUri();
            final Method method = Method.valueOf(request.getMethod());
            final HttpRequestPacket.Builder builder = HttpRequestPacket.builder();
            boolean secure = "https".equals(uri.getScheme());
            builder.method(method);
            builder.protocol(Protocol.HTTP_1_1);
            
            if (!request.getHeaders().containsKey(Header.Host.toString())) {
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
            }
            final ProxyServer proxy = ProxyUtils.getProxyServer(config, request);
            final boolean useProxy = proxy != null;
            if (useProxy) {
                if ((secure || httpCtx.isWSRequest) && !httpCtx.isTunnelEstablished(connection)) {
                    secure = false;
                    httpCtx.establishingTunnel = true;
                    builder.method(Method.CONNECT);
                    builder.uri(AsyncHttpProviderUtils.getAuthority(uri));
                } else if ((secure || httpCtx.isWSRequest) && config.isUseRelativeURIsWithConnectProxies()){
                    builder.uri(getNonEmptyPath(uri));
                } else {
                    builder.uri(uri.toUrl());
                }
            } else {
                builder.uri(getNonEmptyPath(uri));
            }
            
            final BodyHandler bodyHandler = isPayloadAllowed(method) ?
                    bodyHandlerFactory.getBodyHandler(request) :
                    null;
            
            if (bodyHandler != null) {
                final long contentLength = request.getContentLength();
                if (contentLength >= 0) {
                    builder.contentLength(contentLength);
                    builder.chunked(false);
                } else {
                    builder.chunked(true);
                }
            }
            
            HttpRequestPacket requestPacket;
            if (httpCtx.isWSRequest && !httpCtx.establishingTunnel) {
                try {
                    final URI wsURI = httpCtx.wsRequestURI.toJavaNetURI();
                    secure = "wss".equalsIgnoreCase(wsURI.getScheme());
                    httpCtx.protocolHandler = Version.RFC6455.createHandler(true);
                    httpCtx.handshake = httpCtx.protocolHandler.createHandShake(wsURI);
                    requestPacket = (HttpRequestPacket)
                            httpCtx.handshake.composeHeaders().getHttpHeader();
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException("Invalid WS URI: " + httpCtx.wsRequestURI);
                }
            } else {
                requestPacket = builder.build();
            }
            requestPacket.setSecure(secure);

            ctx.notifyDownstream(new SwitchingSSLFilter.SSLSwitchingEvent(secure, connection));

            if (!useProxy && !httpCtx.isWSRequest) {
                requestPacket.setQueryString(uri.getQuery());
                //addQueryString(request, requestPacket);
            }
            addHeaders(request, requestPacket);
            addCookies(request, requestPacket);
            addAuthorizationHeader(request, requestPacket);

            if (useProxy) {
                if (!requestPacket.getHeaders().contains(Header.ProxyConnection)) {
                    requestPacket.setHeader(Header.ProxyConnection, "keep-alive");
                }

                if (proxy.getPrincipal() != null) {
                    requestPacket.setHeader(Header.ProxyAuthorization,
                            AuthenticatorUtils.computeBasicAuthentication(proxy));
                }
            }
            final AsyncHandler h = httpCtx.handler;
            if (h instanceof TransferCompletionHandler) {
                final FluentCaseInsensitiveStringsMap map =
                        new FluentCaseInsensitiveStringsMap(request.getHeaders());
                TransferCompletionHandler.class.cast(h).headers(map);
            }
            
            requestPacket.setConnection(connection);
            return sendRequest(ctx, request, requestPacket,
                    wrapWithExpectHandlerIfNeeded(bodyHandler, requestPacket));

        }

        /**
         * check if we need to wrap the BodyHandler with ExpectHandler
         */
        private BodyHandler wrapWithExpectHandlerIfNeeded(
                final BodyHandler bodyHandler,
                final HttpRequestPacket requestPacket) {
            
            if (bodyHandler == null) {
                return null;
            }
            
            // check if we need to wrap the BodyHandler with ExpectHandler
            final MimeHeaders headers = requestPacket.getHeaders();
            final int expectHeaderIdx = headers.indexOf(Header.Expect, 0);

            return expectHeaderIdx != -1
                    && headers.getValue(expectHeaderIdx).equalsIgnoreCase("100-Continue")
                    ? new ExpectHandler(bodyHandler)
                    : bodyHandler;
        }

        private boolean isPayloadAllowed(final Method method) {
            return method.getPayloadExpectation() != Method.PayloadExpectation.NOT_ALLOWED;
        }
        
        private void addAuthorizationHeader(final Request request, final HttpRequestPacket requestPacket) {
            Realm realm = request.getRealm();
            if (realm == null) {
                realm = config.getRealm();
            }
            if (realm != null && realm.getUsePreemptiveAuth()) {
                final String authHeaderValue = generateAuthHeader(realm);
                if (authHeaderValue != null) {
                    requestPacket.addHeader(Header.Authorization, authHeaderValue);
                }
            }
        }

        private String generateAuthHeader(final Realm realm) {
            try {
                switch (realm.getAuthScheme()) {
                case BASIC:
                    return AuthenticatorUtils.computeBasicAuthentication(realm);
                case DIGEST:
                    return AuthenticatorUtils.computeDigestAuthentication(realm);
                case NTLM:
                    return ntlmEngine.generateType1Msg("NTLM " + realm.getNtlmDomain(), realm.getNtlmHost());
                default:
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }


        private boolean isUpgradeRequest(final AsyncHandler handler) {
            return (handler instanceof UpgradeHandler);
        }


        private boolean isWSRequest(final Uri requestUri) {
            return requestUri.getScheme().startsWith("ws");
        }

        
        private void convertToUpgradeRequest(final HttpTransactionContext ctx) {
            final Uri requestUri = ctx.requestUri;

            ctx.wsRequestURI = requestUri;
            ctx.requestUri = requestUri.withNewScheme(
                    "ws".equals(requestUri.getScheme())
                            ? "http"
                            : "https");
        }

        private void addHeaders(final Request request,
                                final HttpRequestPacket requestPacket) {

            final FluentCaseInsensitiveStringsMap map = request.getHeaders();
            if (isNonEmpty(map)) {
                for (final Map.Entry<String, List<String>> entry : map.entrySet()) {
                    final String headerName = entry.getKey();
                    final List<String> headerValues = entry.getValue();
                    if (isNonEmpty(headerValues)) {
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
            if (isNonEmpty(cookies)) {
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
                gCookies[idx++] = new org.glassfish.grizzly.http.Cookie(
                        cookie.getName(), cookie.getValue());
            }

        }
    } // END AsyncHttpClientFiler


    private static final class AsyncHttpClientEventFilter extends HttpClientFilter {

        private final Map<Integer, StatusHandler> HANDLER_MAP =
                new HashMap<Integer, StatusHandler>();


        private final GrizzlyAsyncHttpProvider provider;

        // -------------------------------------------------------- Constructors


        AsyncHttpClientEventFilter(final GrizzlyAsyncHttpProvider provider, int maxHerdersSizeProperty) {
            super(maxHerdersSizeProperty);

            this.provider = provider;
            HANDLER_MAP.put(HttpStatus.UNAUTHORIZED_401.getStatusCode(),
                    AuthorizationHandler.INSTANCE);
            HANDLER_MAP.put(HttpStatus.MOVED_PERMANENTLY_301.getStatusCode(),
                    RedirectHandler.INSTANCE);
            HANDLER_MAP.put(HttpStatus.FOUND_302.getStatusCode(),
                    RedirectHandler.INSTANCE);
            HANDLER_MAP.put(HttpStatus.SEE_OTHER_303.getStatusCode(),
                    RedirectHandler.INSTANCE);
            HANDLER_MAP.put(HttpStatus.TEMPORARY_REDIRECT_307.getStatusCode(),
                    RedirectHandler.INSTANCE);
            HANDLER_MAP.put(HttpStatus.PERMANENT_REDIRECT_308.getStatusCode(),
                    RedirectHandler.INSTANCE);

        }


        // --------------------------------------- Methods from HttpClientFilter


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
                        .responseStatus.getResponse();
                response.getProcessingState().getHttpContext().attach(ctx);
                onHttpPacketParsed(response, ctx);

                return ctx.getStopAction();
            }

            return ctx.getInvokeAction();
        }
        
        @Override
        public void exceptionOccurred(FilterChainContext ctx, Throwable error) {

            HttpTransactionContext.get(ctx.getConnection()).abort(error);

        }


        @Override
        protected void onHttpContentParsed(HttpContent content,
                                           FilterChainContext ctx) {

            final HttpTransactionContext context =
                    HttpTransactionContext.get(ctx.getConnection());
            final AsyncHandler handler = context.handler;
            if (handler != null && context.currentState != AsyncHandler.STATE.ABORT) {
                try {
                    context.currentState = handler.onBodyPartReceived(
                            new GrizzlyResponseBodyPart(content,
                                    ctx.getConnection()));
                } catch (Exception e) {
                    handler.onThrowable(e);
                }
            }

        }

        @Override
        protected void onHttpHeadersEncoded(HttpHeader httpHeader, FilterChainContext ctx) {
            final HttpTransactionContext context =
                    HttpTransactionContext.get(ctx.getConnection());
            final AsyncHandler handler = context.handler;
            if (handler instanceof TransferCompletionHandler) {
                ((TransferCompletionHandler) handler).onHeaderWriteCompleted();
            }
            if (handler instanceof AsyncHandlerExtensions) {
                ((AsyncHandlerExtensions) handler).onSendRequest();
            }
        }

        @Override
        protected void onHttpContentEncoded(HttpContent content, FilterChainContext ctx) {
            final HttpTransactionContext context =
                    HttpTransactionContext.get(ctx.getConnection());
            final AsyncHandler handler = context.handler;
            if (handler instanceof TransferCompletionHandler) {
                final int written = content.getContent().remaining();
                final long total = context.totalBodyWritten.addAndGet(written);
                ((TransferCompletionHandler) handler).onContentWriteProgress(
                        written,
                        total,
                        content.getHttpHeader().getContentLength());
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
                    HttpTransactionContext.get(ctx.getConnection());
            final int status = ((HttpResponsePacket) httpHeader).getStatus();
            if (context.establishingTunnel && HttpStatus.OK_200.statusMatches(status)) {
                return;
            }
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
                            context.request.getUri(),
                            provider.clientConfig);
            context.responseStatus = responseStatus;
            if (context.statusHandler != null) {
                return;
            }
            if (context.currentState != AsyncHandler.STATE.ABORT) {

                try {
                    final AsyncHandler handler = context.handler;
                    if (handler != null) {
                        context.currentState = handler.onStatusReceived(responseStatus);
                        if (context.isWSRequest && context.currentState == AsyncHandler.STATE.ABORT) {
                            httpHeader.setSkipRemainder(true);
                            try {
                                context.result(handler.onCompleted());
                                context.done();
                            } catch (Throwable e) {
                                context.abort(e);
                            }
                        }
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

            httpHeader.setSkipRemainder(true);
            HttpTransactionContext.get(ctx.getConnection()).abort(t);
        }

        @Override
        protected void onHttpContentError(final HttpHeader httpHeader,
                                         final FilterChainContext ctx,
                                         final Throwable t) throws IOException {

            httpHeader.setSkipRemainder(true);
            HttpTransactionContext.get(ctx.getConnection()).abort(t);
        }
        
        @SuppressWarnings({"unchecked"})
        @Override
        protected void onHttpHeadersParsed(HttpHeader httpHeader,
                                           FilterChainContext ctx) {

            super.onHttpHeadersParsed(httpHeader, ctx);
            LOGGER.debug("RESPONSE: {}", httpHeader);
            final HttpTransactionContext context =
                    HttpTransactionContext.get(ctx.getConnection());
            
            if (httpHeader.containsHeader(Header.Connection)) {
                if ("close".equals(httpHeader.getHeader(Header.Connection))) {
                    ConnectionManager.markConnectionAsDoNotCache(ctx.getConnection());
                }
            }
            if (httpHeader.isSkipRemainder() || context.establishingTunnel) {
                return;
            }

            final AsyncHandler handler = context.handler;
            final List<ResponseFilter> filters = context.provider.clientConfig.getResponseFilters();
            final GrizzlyResponseHeaders responseHeaders = new GrizzlyResponseHeaders((HttpResponsePacket) httpHeader);
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
                        HttpTransactionContext.set(c, newContext);
                        try {
                            context.provider.execute(c,
                                    newRequest,
                                    newHandler,
                                    context.future,
                                    false);
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
            if (context.isWSRequest) {
                try {
                    context.protocolHandler.setConnection(ctx.getConnection());
                    final GrizzlyWebSocketAdapter webSocketAdapter = createWebSocketAdapter(context);
                    context.webSocket = webSocketAdapter;
                    SimpleWebSocket ws = webSocketAdapter.gWebSocket;
                    if (context.currentState == AsyncHandler.STATE.UPGRADE) {
                        httpHeader.setChunked(false);
                        ws.onConnect();
                        WebSocketHolder.set(ctx.getConnection(),
                                            context.protocolHandler,
                                            ws);
                        ((WebSocketUpgradeHandler) context.handler).onSuccess(context.webSocket);
                        final int wsTimeout = context.provider.clientConfig.getWebSocketTimeout();
                        IdleTimeoutFilter.setCustomTimeout(ctx.getConnection(),
                                ((wsTimeout <= 0)
                                        ? IdleTimeoutFilter.FOREVER
                                        : wsTimeout),
                                TimeUnit.MILLISECONDS);
                        context.result(handler.onCompleted());
                    } else {
                        httpHeader.setSkipRemainder(true);
                        ((WebSocketUpgradeHandler) context.handler).
                                onClose(context.webSocket,
                                        1002,
                                        "WebSocket protocol error: unexpected HTTP response status during handshake.");
                        context.result(null);
                    }
                } catch (Throwable e) {
                    httpHeader.setSkipRemainder(true);
                    context.abort(e);
                }
            } else {
                if (context.currentState != AsyncHandler.STATE.ABORT) {
                    try {
                        context.currentState = handler.onHeadersReceived(
                                responseHeaders);
                    } catch (Exception e) {
                        httpHeader.setSkipRemainder(true);
                        context.abort(e);
                    }
                }
            }

        }

        @Override
        protected boolean onHttpHeaderParsed(final HttpHeader httpHeader,
                final Buffer buffer, final FilterChainContext ctx) {
            super.onHttpHeaderParsed(httpHeader, buffer, ctx);
            
            final HttpRequestPacket request = ((HttpResponsePacket) httpHeader).getRequest();
            if (Method.CONNECT.equals(request.getMethod())) {
                // finish request/response processing, because Grizzly itself
                // treats CONNECT traffic as part of request-response processing
                // and we don't want it be treated like that
                httpHeader.setExpectContent(false);
            }
            
            return false;
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

            final HttpTransactionContext context =
                    HttpTransactionContext.get(ctx.getConnection());
            if (context.establishingTunnel
                    && HttpStatus.OK_200.statusMatches(
                    ((HttpResponsePacket) httpHeader).getStatus())) {
                context.establishingTunnel = false;
                final Connection c = ctx.getConnection();
                context.tunnelEstablished(c);
                try {
                    context.provider.execute(c,
                            context.request,
                            context.handler,
                            context.future,
                            false);
                    return result;
                } catch (IOException e) {
                    context.abort(e);
                    return result;
                }
            } else {
                cleanup(ctx, provider);
                final AsyncHandler handler = context.handler;
                if (handler != null) {
                    try {
                        context.result(handler.onCompleted());
                    } catch (Throwable e) {
                        context.abort(e);
                    }
                } else {
                    context.done();
                }

                return result;
            }
        }


        // ----------------------------------------------------- Private Methods

        private static GrizzlyWebSocketAdapter createWebSocketAdapter(final HttpTransactionContext context) {
            SimpleWebSocket ws = new SimpleWebSocket(context.protocolHandler);
            AsyncHttpProviderConfig config = context.provider.clientConfig.getAsyncHttpProviderConfig();
            boolean bufferFragments = true;
            if (config instanceof GrizzlyAsyncHttpProviderConfig) {
                bufferFragments = (Boolean) ((GrizzlyAsyncHttpProviderConfig) config).getProperty(BUFFER_WEBSOCKET_FRAGMENTS);
            }

            return new GrizzlyWebSocketAdapter(ws, bufferFragments);
        }

        private static boolean isRedirectAllowed(final HttpTransactionContext ctx) {
            return ctx.request.getFollowRedirect() != null? ctx.request.getFollowRedirect().booleanValue() : ctx.redirectsAllowed;
        }

        private static HttpTransactionContext cleanup(final FilterChainContext ctx,
                                                      final GrizzlyAsyncHttpProvider provider) {

            final Connection c = ctx.getConnection();
            final HttpTransactionContext context = HttpTransactionContext.remove(c);
            if (!context.provider.connectionManager.canReturnConnection(c)) {
                context.abort(new IOException("Maximum pooled connections exceeded"));
            } else {
                if (!context.provider.connectionManager.returnConnection(context.request, c)) {
                    ctx.getConnection().close();
                }
            }

            return context;

        }

        private static boolean redirectCountExceeded(final HttpTransactionContext context) {

            return (context.redirectCount.get() > context.maxRedirectCount);

        }


        private static boolean isRedirect(final int status) {

            return HttpStatus.MOVED_PERMANENTLY_301.statusMatches(status)
                    || HttpStatus.FOUND_302.statusMatches(status)
                    || HttpStatus.SEE_OTHER_303.statusMatches(status)
                    || HttpStatus.TEMPORARY_REDIRECT_307.statusMatches(status)
                    || HttpStatus.PERMANENT_REDIRECT_308.statusMatches(status);

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
                    if (httpTransactionContext.handler != null) {
                        try {
                            httpTransactionContext.handler.onStatusReceived(httpTransactionContext.responseStatus);
                        } catch (Exception e) {
                            httpTransactionContext.abort(e);
                        }
                    }
                    return true;
                }

                responsePacket.setSkipRemainder(true); // ignore the remainder of the response

                final Request req = httpTransactionContext.request;
                realm = new Realm.RealmBuilder().clone(realm)
                                .setScheme(realm.getAuthScheme())
                                .setUri(httpTransactionContext.request.getUri())
                                .setMethodName(req.getMethod())
                                .setUsePreemptiveAuth(true)
                                .parseWWWAuthenticateHeader(auth)
                                .build();
                String lowerCaseAuth = auth.toLowerCase(Locale.ENGLISH);
                if (lowerCaseAuth.startsWith("basic")) {
                    req.getHeaders().remove(Header.Authorization.toString());
                    try {
                        req.getHeaders().add(Header.Authorization.toString(),
                                             AuthenticatorUtils.computeBasicAuthentication(realm));
                    } catch (UnsupportedEncodingException ignored) {
                    }
                } else if (lowerCaseAuth.startsWith("digest")) {
                    req.getHeaders().remove(Header.Authorization.toString());
                    try {
                        req.getHeaders().add(Header.Authorization.toString(),
                                             AuthenticatorUtils.computeDigestAuthentication(realm));
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException("Digest authentication not supported", e);
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
                    HttpTransactionContext.set(c, newContext);
                    newContext.invocationStatus = InvocationStatus.STOP;
                    try {
                        httpTransactionContext.provider.execute(c,
                                                                req,
                                                                httpTransactionContext.handler,
                                                                httpTransactionContext.future,
                                                                false);
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

                Uri orig;
                if (httpTransactionContext.lastRedirectURI == null) {
                    orig = httpTransactionContext.request.getUri();
                } else {
                    orig = Uri.create(httpTransactionContext.request.getUri(),
                                                httpTransactionContext.lastRedirectURI);
                }
                httpTransactionContext.lastRedirectURI = redirectURL;
                Request requestToSend;
                Uri uri = Uri.create(orig, redirectURL);
                if (!uri.toUrl().equalsIgnoreCase(orig.toUrl())) {
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
                    newContext.requestUri = requestToSend.getUri();
                    HttpTransactionContext.set(c, newContext);
                    httpTransactionContext.provider.execute(c,
                                                            requestToSend,
                                                            newContext.handler,
                                                            newContext.future,
                                                            false);
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


            private boolean switchingSchemes(final Uri oldUri,
                                             final Uri newUri) {

                return !oldUri.getScheme().equals(newUri.getScheme());

            }

            private void notifySchemeSwitch(final FilterChainContext ctx,
                                            final Connection c,
                                            final Uri uri) throws IOException {

                ctx.notifyDownstream(new SwitchingSSLFilter.SSLSwitchingEvent(
                                               "https".equals(uri.getScheme()), c));
            }

        } // END RedirectHandler


        // ----------------------------------------------------- Private Methods


        private static Request newRequest(final Uri uri,
                                          final HttpResponsePacket response,
                                          final HttpTransactionContext ctx,
                                          boolean asGet) {

            final RequestBuilder builder = new RequestBuilder(ctx.request);
            if (asGet) {
                builder.setMethod("GET");
            }
            builder.setUrl(uri.toString());

            if (ctx.provider.clientConfig.isRemoveQueryParamOnRedirect()) {
                builder.resetQuery();
            } else {
                builder.addQueryParams(ctx.request.getQueryParams());
            }
            for (String cookieStr : response.getHeaders().values(Header.Cookie)) {
                builder.addOrReplaceCookie(CookieDecoder.decode(cookieStr));
            }
            return builder.build();

        }


    } // END AsyncHttpClientEventFilter


    private static final class ClientEncodingFilter implements EncodingFilter {


        // ----------------------------------------- Methods from EncodingFilter


        public boolean applyEncoding(HttpHeader httpPacket) {

           httpPacket.addHeader(Header.AcceptEncoding, "gzip");
           return false;

        }


        public boolean applyDecoding(HttpHeader httpPacket) {

            final HttpResponsePacket httpResponse = (HttpResponsePacket) httpPacket;
            final DataChunk bc = httpResponse.getHeaders().getValue(Header.ContentEncoding);
            return bc != null && bc.indexOf("gzip", 0) != -1;

        }


    } // END ClientContentEncoding


    private static final class NonCachingPool implements ConnectionPool {


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

    public static abstract class BodyHandler {

        public static int MAX_CHUNK_SIZE = 8192;

        public abstract boolean handlesBodyType(final Request request);

        public abstract boolean doHandle(final FilterChainContext ctx,
                final Request request, final HttpRequestPacket requestPacket)
                throws IOException;

        /**
         * Tries to predict request content-length based on the {@link Request}.
         * Not all the <tt>BodyHandler</tt>s can predict the content-length in
         * advance.
         *
         * @param request
         * @return the content-length, or <tt>-1</tt> if the content-length
         * can't be predicted
         */
        protected long getContentLength(final Request request) {
            return request.getContentLength();
        }
    } // END BodyHandler


    private final class BodyHandlerFactory {

        private final BodyHandler[] HANDLERS = new BodyHandler[] {
            new StringBodyHandler(),
            new ByteArrayBodyHandler(),
            new ParamsBodyHandler(),
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
            
            return null;
        }

    } // END BodyHandlerFactory


    private static final class ExpectHandler extends BodyHandler {

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
            
            // Set content-length if possible
            final long contentLength = delegate.getContentLength(request);
            if (contentLength != -1) {
                requestPacket.setContentLengthLong(contentLength);
            }
            
            ctx.write(requestPacket, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            return true;
        }

        public void finish(final FilterChainContext ctx) throws IOException {
            delegate.doHandle(ctx, request, requestPacket);
        }

    } // END ContinueHandler


    private final class ByteArrayBodyHandler extends BodyHandler {


        // -------------------------------------------- Methods from BodyHandler

        public boolean handlesBodyType(final Request request) {
            return (request.getByteData() != null);
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            final MemoryManager mm = ctx.getMemoryManager();
            final byte[] data = request.getByteData();
            final Buffer gBuffer = Buffers.wrap(mm, data);
            if (requestPacket.getContentLength() == -1) {
                if (!clientConfig.isCompressionEnforced()) {
                    requestPacket.setContentLengthLong(data.length);
                }
            }
            final HttpContent content = requestPacket.httpContentBuilder().content(gBuffer).build();
            content.setLast(true);
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            return true;
        }
        
        @Override
        protected long getContentLength(final Request request) {
            if (request.getContentLength() >= 0) {
                return request.getContentLength();
            }

            return clientConfig.isCompressionEnforced()
                    ? -1
                    : request.getByteData().length;
        }        
    }


    private final class StringBodyHandler extends BodyHandler {


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
                charset = Charsets.ASCII_CHARSET.name();
            }
            final byte[] data = request.getStringData().getBytes(charset);
            final MemoryManager mm = ctx.getMemoryManager();
            final Buffer gBuffer = Buffers.wrap(mm, data);
            if (requestPacket.getContentLength() == -1) {
                if (!clientConfig.isCompressionEnforced()) {
                    requestPacket.setContentLengthLong(data.length);
                }
            }
            final HttpContent content = requestPacket.httpContentBuilder().content(gBuffer).build();
            content.setLast(true);
            ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            return true;
        }

    } // END StringBodyHandler


    private final class ParamsBodyHandler extends BodyHandler {


        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            return isNonEmpty(request.getFormParams());
        }

        @SuppressWarnings({"unchecked"})
        public boolean doHandle(final FilterChainContext ctx,
                             final Request request,
                             final HttpRequestPacket requestPacket)
        throws IOException {

            if (requestPacket.getContentType() == null) {
                requestPacket.setContentType("application/x-www-form-urlencoded");
            }
            String charset = request.getBodyEncoding();
            if (charset == null) {
                charset = Charsets.ASCII_CHARSET.name();
            }
            
            if (isNonEmpty(request.getFormParams())) {
                StringBuilder sb = new StringBuilder(128);
                for (Param param : request.getFormParams()) {
                    String name = URLEncoder.encode(param.getName(), charset);
                    String value = URLEncoder.encode(param.getValue(), charset);
                    sb.append(name).append('=').append(value).append('&');
                }
                sb.setLength(sb.length() - 1);
                final byte[] data = sb.toString().getBytes(charset);
                final MemoryManager mm = ctx.getMemoryManager();
                final Buffer gBuffer = Buffers.wrap(mm, data);
                final HttpContent content = requestPacket.httpContentBuilder().content(gBuffer).build();
                if (requestPacket.getContentLength() == -1 && !clientConfig.isCompressionEnforced()) {
                    requestPacket.setContentLengthLong(data.length);
                }
                content.setLast(true);
                ctx.write(content, ((!requestPacket.isCommitted()) ? ctx.getTransportContext().getCompletionHandler() : null));
            }
            
            return true;
        }

    } // END ParamsBodyHandler

    private static final class StreamDataBodyHandler extends BodyHandler {

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


    private static final class PartsBodyHandler extends BodyHandler {

        // -------------------------------------------- Methods from BodyHandler


        public boolean handlesBodyType(final Request request) {
            return isNonEmpty(request.getParts());
        }

        public boolean doHandle(final FilterChainContext ctx,
                                final Request request,
                                final HttpRequestPacket requestPacket)
                throws IOException {

            final List<Part> parts = request.getParts();
            final MultipartBody multipartBody = MultipartUtils.newMultipartBody(parts, request.getHeaders());
            final long contentLength = multipartBody.getContentLength();
            final String contentType = multipartBody.getContentType();
            requestPacket.setContentLengthLong(contentLength);
            requestPacket.setContentType(contentType);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("REQUEST(modified): contentLength={}, contentType={}", new Object[]{requestPacket.getContentLength(), requestPacket.getContentType()});
            }

            final FeedableBodyGenerator generator = new FeedableBodyGenerator() {
                @Override
                public Body createBody() throws IOException {
                    return multipartBody;
                }
            };
            generator.setFeeder(new FeedableBodyGenerator.BaseFeeder(generator) {
                @Override
                public void flush() throws IOException {
                    final Body bodyLocal = feedableBodyGenerator.createBody();
                    try {
                        final MemoryManager mm = ctx.getMemoryManager();
                        boolean last = false;
                        while (!last) {
                            Buffer buffer = mm.allocate(BodyHandler.MAX_CHUNK_SIZE);
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
                                    throw new IllegalStateException("MultipartBody unexpectedly returned 0 bytes available");
                                }
                            }
                            feed(buffer, last);
                        }
                    } finally {
                        if (bodyLocal != null) {
                            try {
                                bodyLocal.close();
                            } catch (IOException ignore) {
                            }
                        }
                    }
                }
            });
            generator.initializeAsynchronousTransfer(ctx, requestPacket);
            return false;
        }

    } // END PartsBodyHandler


    private final class FileBodyHandler extends BodyHandler {

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
            final HttpTransactionContext context = HttpTransactionContext.get(ctx.getConnection());
            if (clientConfig.isCompressionEnforced() || !SEND_FILE_SUPPORT ||
                    requestPacket.isSecure()) {
                
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
                        if (handler instanceof TransferCompletionHandler) {
                            final long written = result.getWrittenSize();
                            final long total = context.totalBodyWritten.addAndGet(written);
                            ((TransferCompletionHandler) handler).onContentWriteProgress(
                                    written,
                                    total,
                                    requestPacket.getContentLength());
                        }
                    }
                });
            }

            return true;
        }

        @Override
        protected long getContentLength(final Request request) {
            if (request.getContentLength() >= 0) {
                return request.getContentLength();
            }

            return clientConfig.isCompressionEnforced()
                    ? -1
                    : request.getFile().length();
        }        
    } // END FileBodyHandler


    private static final class BodyGeneratorBodyHandler extends BodyHandler {

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
            if (len >= 0) {
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
        private final ConnectionPool pool;
        private final TCPNIOConnectorHandler connectionHandler;
        private final ConnectionMonitor connectionMonitor;
        private final GrizzlyAsyncHttpProvider provider;

        // -------------------------------------------------------- Constructors

        ConnectionManager(final GrizzlyAsyncHttpProvider provider,
                          final TCPNIOTransport transport,
                          final GrizzlyAsyncHttpProviderConfig providerConfig) {

            ConnectionPool connectionPool;
            this.provider = provider;
            final AsyncHttpClientConfig config = provider.clientConfig;
            if (config.isAllowPoolingConnections()) {
                ConnectionPool pool = providerConfig != null ? providerConfig.getConnectionPool() : null;
                if (pool != null) {
                    connectionPool = pool;
                } else {
                    connectionPool = new GrizzlyConnectionPool((config));
                }
            } else {
                connectionPool = new NonCachingPool();
            }
            pool = connectionPool;
            connectionHandler = TCPNIOConnectorHandler.builder(transport).build();
            final int maxConns = provider.clientConfig.getMaxConnections();
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
            Connection c = pool.poll(getPartitionId(request, requestFuture.getProxy()));
            if (c == null) {
                if (!connectionMonitor.acquire()) {
                    throw new IOException("Max connections exceeded");
                }
                doAsyncConnect(request, requestFuture, connectHandler);
            } else {
                provider.touchConnection(c, request);
                connectHandler.completed(c);
            }

        }

        Connection obtainConnection(final Request request,
                                    final GrizzlyResponseFuture requestFuture)
        throws IOException, ExecutionException, InterruptedException, TimeoutException {

            final Connection c = obtainConnection0(request, requestFuture);
            DO_NOT_CACHE.set(c, Boolean.TRUE);
            return c;

        }

        void doAsyncConnect(final Request request,
                            final GrizzlyResponseFuture requestFuture,
                            final CompletionHandler<Connection> connectHandler)
        throws IOException, ExecutionException, InterruptedException {

            ProxyServer proxy = requestFuture.getProxy();
            final Uri uri = request.getUri();
            String host = ((proxy != null) ? proxy.getHost() : uri.getHost());
            int port = ((proxy != null) ? proxy.getPort() : uri.getPort());
            
            CompletionHandler<Connection> completionHandler =
                    createConnectionCompletionHandler(request, requestFuture,
                            connectHandler);
            
            final HostnameVerifier verifier =
                    provider.clientConfig.getHostnameVerifier();
            
            if (Utils.isSecure(uri) && verifier != null) {
                completionHandler =
                        HostnameVerifierListener.wrapWithHostnameVerifierHandler(
                                completionHandler, verifier, uri.getHost());
            }

            if (request.getLocalAddress() != null) {
                connectionHandler.connect(new InetSocketAddress(host,
                        getPort(uri, port)),
                        new InetSocketAddress(request.getLocalAddress(), 0),
                        completionHandler);
            } else {
                connectionHandler.connect(new InetSocketAddress(host,
                        getPort(uri, port)),
                        completionHandler);
            }

        }

        private Connection obtainConnection0(final Request request,
                                             final GrizzlyResponseFuture requestFuture)
        throws IOException, ExecutionException, InterruptedException, TimeoutException {

            final Uri uri = request.getUri();
            final ProxyServer proxy = requestFuture.getProxy();
            String host = (proxy != null) ? proxy.getHost() : uri.getHost();
            int port = (proxy != null) ? proxy.getPort() : uri.getPort();
            int cTimeout = provider.clientConfig.getConnectionTimeout();
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

        boolean returnConnection(final Request request, final Connection c) {
            ProxyServer proxyServer = ProxyUtils.getProxyServer(provider.clientConfig, request);
            final boolean result = (DO_NOT_CACHE.get(c) == null
                                       && pool.offer(getPartitionId(request, proxyServer), c));
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

        private static String getPartitionId(Request request, ProxyServer proxyServer) {
            return request.getConnectionPoolPartitioning().getPartitionId(request.getUri(), proxyServer);
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

        @Override
        public void onFilterChainChanged(FilterChain filterChain) {
            // no-op
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

    private static final class GrizzlyWebSocketAdapter implements WebSocket {
        
        final SimpleWebSocket gWebSocket;
        final boolean bufferFragments;

        // -------------------------------------------------------- Constructors
        
        
        GrizzlyWebSocketAdapter(final SimpleWebSocket gWebSocket,
                                final boolean bufferFragments) {
            this.gWebSocket = gWebSocket;
            this.bufferFragments = bufferFragments;
        }
        
        
        // ------------------------------------------ Methods from AHC WebSocket
        
        
        @Override
        public WebSocket sendMessage(byte[] message) {
            gWebSocket.send(message);
            return this;
        }

        @Override
        public WebSocket stream(byte[] fragment, boolean last) {
            if (isNonEmpty(fragment)) {
                gWebSocket.stream(last, fragment, 0, fragment.length);
            }
            return this;
        }

        @Override
        public WebSocket stream(byte[] fragment, int offset, int len, boolean last) {
        	if (isNonEmpty(fragment)) {
                gWebSocket.stream(last, fragment, offset, len);
            }
            return this;
        }

        @Override
        public WebSocket sendMessage(String message) {
            gWebSocket.send(message);
            return this;
        }

        @Override
        public WebSocket stream(String fragment, boolean last) {
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
        private final GrizzlyWebSocketAdapter webSocket;
        private final StringBuilder stringBuffer;
        private final ByteArrayOutputStream byteArrayOutputStream;


        // -------------------------------------------------------- Constructors


        AHCWebSocketListenerAdapter(final WebSocketListener ahcListener,
                                    final GrizzlyWebSocketAdapter webSocket) {
            this.ahcListener = ahcListener;
            this.webSocket = webSocket;
            if (webSocket.bufferFragments) {
                stringBuffer = new StringBuilder();
                byteArrayOutputStream = new ByteArrayOutputStream();
            } else {
                stringBuffer = null;
                byteArrayOutputStream = null;
            }
        }


        // ------------------------------ Methods from Grizzly WebSocketListener


        @Override
        public void onClose(org.glassfish.grizzly.websockets.WebSocket gWebSocket, DataFrame dataFrame) {
            try {
                if (ahcListener instanceof WebSocketCloseCodeReasonListener) {
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
                if (ahcListener instanceof WebSocketTextListener) {
                    WebSocketTextListener.class.cast(ahcListener).onMessage(s);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onMessage(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
            try {
                if (ahcListener instanceof WebSocketByteListener) {
                    WebSocketByteListener.class.cast(ahcListener).onMessage(bytes);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onPing(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
            try {
                if (ahcListener instanceof WebSocketPingListener) {
                    WebSocketPingListener.class.cast(ahcListener).onPing(bytes);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onPong(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes) {
            try {
                if (ahcListener instanceof WebSocketPongListener) {
                    WebSocketPongListener.class.cast(ahcListener).onPong(bytes);
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onFragment(org.glassfish.grizzly.websockets.WebSocket webSocket, String s, boolean last) {
            try {
                if (this.webSocket.bufferFragments) {
                    synchronized (this.webSocket) {
                        stringBuffer.append(s);
                        if (last) {
                            if (ahcListener instanceof WebSocketTextListener) {
                                final String message = stringBuffer.toString();
                                stringBuffer.setLength(0);
                                WebSocketTextListener.class.cast(ahcListener).onMessage(message);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                ahcListener.onError(e);
            }
        }

        @Override
        public void onFragment(org.glassfish.grizzly.websockets.WebSocket webSocket, byte[] bytes, boolean last) {
            try {
                if (this.webSocket.bufferFragments) {
                    synchronized (this.webSocket) {
                        byteArrayOutputStream.write(bytes);
                        if (last) {
                            if (ahcListener instanceof WebSocketByteListener) {
                                final byte[] bytesLocal = byteArrayOutputStream.toByteArray();
                                byteArrayOutputStream.reset();
                                WebSocketByteListener.class.cast(ahcListener).onMessage(bytesLocal);
                            }
                        }
                    }
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


    public static void main(String[] args) {
            SecureRandom secureRandom = new SecureRandom();
            SSLContext sslContext = null;
            try {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, secureRandom);
            } catch (Exception e) {
                e.printStackTrace();
            }
            AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder()
                    .setConnectionTimeout(5000)
                    .setSSLContext(sslContext).build();
            AsyncHttpClient client = new AsyncHttpClient(new GrizzlyAsyncHttpProvider(config), config);
            try {
                long start = System.currentTimeMillis();
                try {
                    client.executeRequest(client.prepareGet("http://www.google.com").build()).get();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
                System.out.println("COMPLETE: " + (System.currentTimeMillis() - start) + "ms");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
}



