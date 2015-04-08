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

import com.ning.http.client.providers.grizzly.events.SSLSwitchingEvent;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.ContentEncoding;
import org.glassfish.grizzly.http.EncodingFilter;
import org.glassfish.grizzly.http.GZipContentEncoding;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.CookieSerializerUtils;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.nio.RoundRobinConnectionDistributor;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.websockets.Version;
import org.glassfish.grizzly.websockets.WebSocketFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.SSLEngineFactory;
import com.ning.http.client.UpgradeHandler;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.listener.TransferCompletionHandler;
import com.ning.http.client.ntlm.NTLMEngine;
import com.ning.http.client.uri.Uri;
import com.ning.http.util.AsyncHttpProviderUtils;
import com.ning.http.util.AuthenticatorUtils;
import com.ning.http.util.ProxyUtils;
import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.http.HttpContext;

import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.MAX_HTTP_PACKET_HEADER_SIZE;
import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.TRANSPORT_CUSTOMIZER;
import com.ning.http.client.providers.grizzly.events.ContinueEvent;
import static com.ning.http.util.AsyncHttpProviderUtils.getNonEmptyPath;
import static com.ning.http.util.MiscUtils.isNonEmpty;
import java.util.concurrent.TimeoutException;
import org.glassfish.grizzly.filterchain.FilterChainEvent;

/**
 * A Grizzly 2.0-based implementation of {@link AsyncHttpProvider}.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyAsyncHttpProvider implements AsyncHttpProvider {

    private final static Logger LOGGER = LoggerFactory.getLogger(GrizzlyAsyncHttpProvider.class);
    
    // Lazy NTLM instance holder
    private static class NTLM_INSTANCE_HOLDER {
        private final static NTLMEngine ntlmEngine = new NTLMEngine();
    }
    
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

    AsyncHttpClientConfig getClientConfig() {
        return clientConfig;
    }

    ConnectionManager getConnectionManager() {
        return connectionManager;
    }
        
    // ------------------------------------------ Methods from AsyncHttpProvider


    @Override
    public <T> ListenableFuture<T> execute(final Request request,
            final AsyncHandler<T> asyncHandler) {

        if (clientTransport.isStopped()) {
            IOException e = new IOException("AsyncHttpClient has been closed.");
            asyncHandler.onThrowable(e);
            return new ListenableFuture.CompletedFailure<>(e);
        }
//        final ProxyServer proxy = ProxyUtils.getProxyServer(clientConfig, request);
        final GrizzlyResponseFuture<T> future =
                new GrizzlyResponseFuture<T>(asyncHandler);
        
        final CompletionHandler<Connection> connectHandler =
                new CompletionHandler<Connection>() {
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
                    final HttpTransactionContext tx =
                            HttpTransactionContext.startTransaction(c,
                            GrizzlyAsyncHttpProvider.this, request,
                            future);
                    
                    if (future.setHttpTransactionCtx(tx)) {
                        execute(tx);
                    } else {
                        tx.closeConnection();
                    }
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
            connectionManager.getConnectionAsync(request,
                    future, connectHandler);
        } catch (IOException ioe) {
            abort(future, ioe);
        } catch (RuntimeException re) {
            abort(future, re);
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(e.toString(), e);
            }
            abort(future, e);
        }

        return future;
    }

    private void abort(GrizzlyResponseFuture<?> future, Throwable t) {
        if (!future.isDone()) {
            LOGGER.debug("Aborting Future {}\n", future);
            LOGGER.debug(t.getMessage(), t);
            future.abort(t);
        }
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
    void execute(final HttpTransactionContext transactionCtx)
    throws IOException {

        try {
            transactionCtx.getConnection().write(transactionCtx,
                    createWriteCompletionHandler(transactionCtx.future));
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
                        public long getTimeout(final FilterChainContext ctx) {
                            final HttpTransactionContext context
                                    = HttpTransactionContext.currentTransaction(ctx.getConnection());
                            if (context != null) {
                                if (context.isWSRequest) {
                                    return clientConfig.getWebSocketTimeout();
                                }
                                final long timeout = context.getAhcRequest().getRequestTimeout();
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

        final boolean defaultSecState = (clientConfig.getSSLContext() != null);
        final SSLEngineConfigurator configurator
                = new AhcSSLEngineConfigurator(
                        providerConfig.getSslEngineFactory() != null
                                ? providerConfig.getSslEngineFactory()
                                : new SSLEngineFactory.DefaultSSLEngineFactory(clientConfig));
        
        final SwitchingSSLFilter sslFilter =
                new SwitchingSSLFilter(configurator, defaultSecState);
        if (clientConfig.getHostnameVerifier() != null) {
            sslFilter.addHandshakeListener(new HostnameVerifierListener());
        }
        fcb.add(sslFilter);
        
        final AhcEventFilter eventFilter = new
                AhcEventFilter(this, (Integer) providerConfig.getProperty(MAX_HTTP_PACKET_HEADER_SIZE));
        final AsyncHttpClientFilter clientFilter =
                new AsyncHttpClientFilter(clientConfig);
        ContentEncoding[] encodings = eventFilter.getContentEncodings();
        if (encodings.length > 0) {
            for (ContentEncoding encoding : encodings) {
                eventFilter.removeContentEncoding(encoding);
            }
        }
        
        eventFilter.addContentEncoding(
                new GZipContentEncoding(512,
                                        512,
                                        new ClientEncodingFilter()));
        
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

        final long timeOut = request.getRequestTimeout() > 0
                ? request.getRequestTimeout()
                : clientConfig.getRequestTimeout();
        
        
        if (timeOut > 0) {
            if (resolver != null) {
                resolver.setTimeoutMillis(c,
                        System.currentTimeMillis() + timeOut);
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


    private <T> CompletionHandler<WriteResult> createWriteCompletionHandler(
            final GrizzlyResponseFuture<T> future) {
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
        final HttpTransactionContext tx = HttpTransactionContext.currentTransaction(c);
        final TimeoutException te = new TimeoutException("Timeout exceeded");
        if (tx != null) {
            tx.abort(te);
        }
        
        c.closeWithReason(new IOException("Timeout exceeded", te));
    }

    @SuppressWarnings({"unchecked"})
    boolean sendRequest(final HttpTransactionContext httpTxCtx,
                     final FilterChainContext ctx,
                     final HttpRequestPacket requestPacket,
                     final PayloadGenerator payloadGenerator)
    throws IOException {
        
        final Connection connection = httpTxCtx.getConnection();
        final Request request = httpTxCtx.getAhcRequest();
        final AsyncHandler h = httpTxCtx.getAsyncHandler();
        
        // create HttpContext and mutually bind it with HttpTransactionContext
        final HttpContext httpCtx = HttpContext.newInstance(
                connection, connection, connection, requestPacket);
        requestPacket.getProcessingState().setHttpContext(httpCtx);
        HttpTransactionContext.bind(httpCtx, httpTxCtx);
        httpCtx.attach(ctx);
        
        
        if (h instanceof TransferCompletionHandler) {
            final FluentCaseInsensitiveStringsMap map
                    = new FluentCaseInsensitiveStringsMap(request.getHeaders());
            TransferCompletionHandler.class.cast(h).headers(map);
        }

        requestPacket.setConnection(ctx.getConnection());
        
        boolean isWriteComplete = true;
        
        if (payloadGenerator != null) { // Check if the HTTP request has body
            httpTxCtx.payloadGenerator = payloadGenerator;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("REQUEST: " + requestPacket.toString());
            }
            isWriteComplete = payloadGenerator.generate(ctx, request, requestPacket);
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("REQUEST: " + requestPacket.toString());
            }
            ctx.write(requestPacket, ctx.getTransportContext().getCompletionHandler());
        }

        
        return isWriteComplete;
    }


        
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

            final Object message = ctx.getMessage();
            if (message instanceof HttpTransactionContext) {
                ctx.setMessage(null);
                
                if (!sendAsGrizzlyRequest((HttpTransactionContext) message, ctx)) {
                    return ctx.getSuspendAction();
                } else {
                    return ctx.getStopAction();
                }
            }
            
            return ctx.getInvokeAction();
        }

        @Override
        public NextAction handleEvent(final FilterChainContext ctx,
                                      final FilterChainEvent event)
        throws IOException {

            final Object type = event.type();
            if (type == ContinueEvent.class) {
                final ContinueEvent continueEvent = (ContinueEvent) event;
                continueEvent.getContext().payloadGenerator.continueConfirmed(ctx);
            }

            return ctx.getStopAction();

        }

        // ----------------------------------------------------- Private Methods


        private boolean sendAsGrizzlyRequest(final HttpTransactionContext httpTxCtx,
                                          final FilterChainContext ctx)
        throws IOException {
            
            final Connection connection = ctx.getConnection();
            final Request ahcRequest = httpTxCtx.getAhcRequest();
            
            if (isUpgradeRequest(httpTxCtx.getAsyncHandler()) &&
                    isWSRequest(httpTxCtx.requestUri)) {
                httpTxCtx.isWSRequest = true;
                convertToUpgradeRequest(httpTxCtx);
            }
            final Request req = httpTxCtx.getAhcRequest();
            final Method method = Method.valueOf(ahcRequest.getMethod());
            final Uri uri = req.getUri();
            
            boolean secure = "https".equals(uri.getScheme());
            
            final ProxyServer proxy = ProxyUtils.getProxyServer(config, ahcRequest);
            final boolean useProxy = proxy != null;

            final boolean isEstablishingConnectTunnel =
                    useProxy && (secure || httpTxCtx.isWSRequest) &&
                    !httpTxCtx.isTunnelEstablished(connection);
            
            if (isEstablishingConnectTunnel) {
                // once the tunnel is established, sendAsGrizzlyRequest will
                // be called again and we'll finally send the request over the tunnel
                return establishConnectTunnel(proxy, httpTxCtx, uri, ctx);
            }

            final HttpRequestPacket.Builder builder =
                    HttpRequestPacket.builder()
                    .protocol(Protocol.HTTP_1_1)
                    .method(method);
                        
            if (useProxy) {
                if (secure || httpTxCtx.isWSRequest) { // Sending message over established CONNECT tunnel
                    if (config.isUseRelativeURIsWithConnectProxies()) {
                        builder.uri(getNonEmptyPath(uri));
                        builder.query(uri.getQuery());
                    } else {
                        builder.uri(uri.toUrl());
                    }
                } else {
                    builder.uri(uri.toUrl());
                }
            } else {
                builder.uri(getNonEmptyPath(uri));
                builder.query(uri.getQuery());
            }
            
            HttpRequestPacket requestPacket;
            final PayloadGenerator payloadGenerator = isPayloadAllowed(method)
                    ? PayloadGenFactory.getPayloadGenerator(ahcRequest)
                    : null;

            if (payloadGenerator != null) {
                final long contentLength = ahcRequest.getContentLength();
                if (contentLength >= 0) {
                    builder.contentLength(contentLength);
                    builder.chunked(false);
                } else {
                    builder.chunked(true);
                }
            }

            if (httpTxCtx.isWSRequest) {
                try {
                    final URI wsURI = httpTxCtx.wsRequestURI.toJavaNetURI();
                    secure = "wss".equalsIgnoreCase(wsURI.getScheme());
                    httpTxCtx.protocolHandler = Version.RFC6455.createHandler(true);
                    httpTxCtx.handshake = httpTxCtx.protocolHandler.createClientHandShake(wsURI);
                    requestPacket = (HttpRequestPacket) httpTxCtx.handshake.composeHeaders().getHttpHeader();
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException("Invalid WS URI: " + httpTxCtx.wsRequestURI);
                }
            } else {
                requestPacket = builder.build();
            }

            requestPacket.setSecure(secure);
            ctx.notifyDownstream(new SSLSwitchingEvent(secure, connection));

            copyHeaders(ahcRequest, requestPacket);
            addCookies(ahcRequest, requestPacket);

            addHostHeaderIfNeeded(ahcRequest, uri, requestPacket);
            addServiceHeaders(requestPacket);
            addAcceptHeaders(requestPacket);
            addAuthorizationHeader(connection, getRealm(ahcRequest), requestPacket);

            if (useProxy) {
                addProxyHeaders(proxy, requestPacket);
            }
                        
            return sendRequest(httpTxCtx, ctx, requestPacket,
                    wrapWithExpectHandlerIfNeeded(payloadGenerator, requestPacket));
        }

        private boolean establishConnectTunnel(
                final ProxyServer proxy,
                final HttpTransactionContext httpCtx,
                final Uri uri,
                final FilterChainContext ctx) throws IOException {
            final Connection connection = ctx.getConnection();
            
            final HttpRequestPacket requestPacket =
                    HttpRequestPacket.builder()
                    .protocol(Protocol.HTTP_1_0)
                    .method(Method.CONNECT)
                    .uri(AsyncHttpProviderUtils.getAuthority(uri))
                    .build();

            httpCtx.establishingTunnel = true;
            
            // turn off SSL, because CONNECT will be sent in plain mode
            ctx.notifyDownstream(new SSLSwitchingEvent(false, connection));

            final Request request = httpCtx.getAhcRequest();
            addHostHeaderIfNeeded(request, uri, requestPacket);
            addServiceHeaders(requestPacket);
            addAuthorizationHeader(connection, getRealm(request), requestPacket);
            addProxyHeaders(proxy, requestPacket);
            
            return sendRequest(httpCtx, ctx, requestPacket, null);
        }
        
        /**
         * check if we need to wrap the PayloadGenerator with ExpectHandler
         */
        private PayloadGenerator wrapWithExpectHandlerIfNeeded(
                final PayloadGenerator payloadGenerator,
                final HttpRequestPacket requestPacket) {
            
            if (payloadGenerator == null) {
                return null;
            }
            
            // check if we need to wrap the PayloadGenerator with ExpectWrapper
            final MimeHeaders headers = requestPacket.getHeaders();
            final int expectHeaderIdx = headers.indexOf(Header.Expect, 0);

            return expectHeaderIdx != -1
                    && headers.getValue(expectHeaderIdx).equalsIgnoreCase("100-Continue")
                    ? PayloadGenFactory.wrapWithExpect(payloadGenerator)
                    : payloadGenerator;
        }

        private boolean isPayloadAllowed(final Method method) {
            return method.getPayloadExpectation() != Method.PayloadExpectation.NOT_ALLOWED;
        }
        
        private void addAuthorizationHeader(final Connection c,
                final Realm realm, final HttpRequestPacket requestPacket) {
            if (realm != null && realm.getUsePreemptiveAuth()) {
                final String authHeaderValue = generateAuthHeader(c, realm);
                if (authHeaderValue != null) {
                    requestPacket.addHeader(Header.Authorization, authHeaderValue);
                }
            }
        }

        private void addProxyHeaders(final ProxyServer proxy,
                final HttpRequestPacket requestPacket) {
            
            if (!requestPacket.getHeaders().contains(Header.ProxyConnection)) {
                requestPacket.setHeader(Header.ProxyConnection, "keep-alive");
            }

            if (proxy.getPrincipal() != null) {
                requestPacket.setHeader(Header.ProxyAuthorization,
                        AuthenticatorUtils.computeBasicAuthentication(proxy));
            }
        }

        private void addHostHeaderIfNeeded(final Request request,
                final Uri uri, final HttpRequestPacket requestPacket) {
            if (!requestPacket.containsHeader(Header.Host)) {
                String host = request.getVirtualHost();
                if (host != null) {
                    requestPacket.addHeader(Header.Host, host);
                } else {
                    if (uri.getPort() == -1) {
                        requestPacket.addHeader(Header.Host, uri.getHost());
                    } else {
                        requestPacket.addHeader(Header.Host, uri.getHost() + ':' + uri.getPort());
                    }
                }
            }
        }

        private Realm getRealm(final Request request) {
            return request.getRealm() != null ? request.getRealm() : config.getRealm();
        }
        
        private String generateAuthHeader(final Connection c, final Realm realm) {
            try {
                switch (realm.getScheme()) {
                    case BASIC:
                        return AuthenticatorUtils.computeBasicAuthentication(realm);
                    case DIGEST:
                        return AuthenticatorUtils.computeDigestAuthentication(realm);
                    case NTLM:
                        return !Utils.getAndSetNtlmAttempted(c)
                                ? "NTLM " + NTLM_INSTANCE_HOLDER.ntlmEngine.generateType1Msg()
                                : null;
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

        private void copyHeaders(final Request request,
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
        }
        
        private void addServiceHeaders(final HttpRequestPacket requestPacket) {
            final MimeHeaders headers = requestPacket.getHeaders();
            if (!headers.contains(Header.Connection)) {
                requestPacket.addHeader(Header.Connection, "keep-alive");
            }

            if (!headers.contains(Header.UserAgent)) {
                requestPacket.addHeader(Header.UserAgent, config.getUserAgent());
            }
        }

        private void addAcceptHeaders(final HttpRequestPacket requestPacket) {
            final MimeHeaders headers = requestPacket.getHeaders();
            if (clientConfig.isCompressionEnforced() &&
                    !headers.contains(Header.AcceptEncoding)) {
                requestPacket.addHeader(Header.AcceptEncoding, "gzip");
            }
            
            if (!headers.contains(Header.Accept)) {
                requestPacket.addHeader(Header.Accept, "*/*");
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


    private static final class ClientEncodingFilter implements EncodingFilter {


        // ----------------------------------------- Methods from EncodingFilter


        public boolean applyEncoding(HttpHeader httpPacket) {
            return false;
        }


        public boolean applyDecoding(HttpHeader httpPacket) {

            final HttpResponsePacket httpResponse = (HttpResponsePacket) httpPacket;
            final DataChunk bc = httpResponse.getHeaders().getValue(Header.ContentEncoding);
            return bc != null && bc.indexOf("gzip", 0) != -1;

        }


    } // END ClientContentEncoding

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
                    .setConnectTimeout(5000)
                    .setSSLContext(sslContext).build();
            AsyncHttpClient client = new AsyncHttpClient(new GrizzlyAsyncHttpProvider(config), config);
            long start = System.currentTimeMillis();
            try {
                client.executeRequest(client.prepareGet("http://www.google.com").build()).get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            LOGGER.debug("COMPLETE: " + (System.currentTimeMillis() - start) + "ms");
        }
}



