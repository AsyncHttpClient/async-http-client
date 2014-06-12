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

package org.asynchttpclient.providers.grizzly;

import static org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.CONNECTION_POOL;
import static org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.MAX_HTTP_PACKET_HEADER_SIZE;
import static org.glassfish.grizzly.asyncqueue.AsyncQueueWriter.AUTO_SIZE;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.ntlm.NTLMEngine;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property;
import org.asynchttpclient.providers.grizzly.filters.AsyncHttpClientEventFilter;
import org.asynchttpclient.providers.grizzly.filters.AsyncHttpClientFilter;
import org.asynchttpclient.providers.grizzly.filters.AsyncSpdyClientEventFilter;
import org.asynchttpclient.providers.grizzly.filters.ClientEncodingFilter;
import org.asynchttpclient.providers.grizzly.filters.SwitchingSSLFilter;
import org.asynchttpclient.util.AsyncHttpProviderUtils;
import org.asynchttpclient.util.ProxyUtils;
import org.asynchttpclient.util.SslUtils;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.ContentEncoding;
import org.glassfish.grizzly.http.GZipContentEncoding;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.npn.ClientSideNegotiator;
import org.glassfish.grizzly.spdy.NextProtoNegSupport;
import org.glassfish.grizzly.spdy.SpdyFramingFilter;
import org.glassfish.grizzly.spdy.SpdyHandlerFilter;
import org.glassfish.grizzly.spdy.SpdyMode;
import org.glassfish.grizzly.spdy.SpdySession;
import org.glassfish.grizzly.ssl.SSLBaseFilter;
import org.glassfish.grizzly.ssl.SSLConnectionContext;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.ssl.SSLUtils;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.websockets.WebSocketClientFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.glassfish.grizzly.spdy.SpdyVersion;

/**
 * A Grizzly 2.0-based implementation of {@link AsyncHttpProvider}.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
@SuppressWarnings("rawtypes")
public class GrizzlyAsyncHttpProvider implements AsyncHttpProvider {

    public static final Logger LOGGER = LoggerFactory.getLogger(GrizzlyAsyncHttpProvider.class);
    public final static NTLMEngine NTLM_ENGINE = new NTLMEngine();

    private final AsyncHttpClientConfig clientConfig;

    private ConnectionManager connectionManager;
    private DelayedExecutor.Resolver<Connection> resolver;
    private DelayedExecutor timeoutExecutor;

    final TCPNIOTransport clientTransport;

    // ------------------------------------------------------------ Constructors

    public GrizzlyAsyncHttpProvider(final AsyncHttpClientConfig clientConfig) {

        this.clientConfig = clientConfig;
        final TCPNIOTransportBuilder builder = TCPNIOTransportBuilder.newInstance();
        clientTransport = builder.build();
        initializeTransport(clientConfig);
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
    public <T> ListenableFuture<T> execute(final Request request, final AsyncHandler<T> handler) throws IOException {

        if (clientTransport.isStopped()) {
            throw new IOException("AsyncHttpClient has been closed.");
        }
        final ProxyServer proxy = ProxyUtils.getProxyServer(clientConfig, request);
        final GrizzlyResponseFuture<T> future = new GrizzlyResponseFuture<T>(this, request, handler, proxy);
        future.setDelegate(SafeFutureImpl.<T> create());
        final CompletionHandler<Connection> connectHandler = new CompletionHandler<Connection>() {
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
                    touchConnection(c, request);
                    execute(c, request, handler, future, null);
                } catch (Exception e) {
                    failed(e);
                }
            }

            @Override
            public void updated(final Connection c) {
                // no-op
            }
        };

        connectionManager.doTrackedConnection(request, future, connectHandler);

        return future;
    }

    /**
     * {@inheritDoc}
     */
    public void close() {

        try {
            connectionManager.destroy();
            clientTransport.shutdownNow();
            final ExecutorService service = clientConfig.executorService();
            // service may be null due to a custom configuration that
            // leverages Grizzly's SameThreadIOStrategy.
            if (service != null) {
                service.shutdown();
            }
            if (timeoutExecutor != null) {
                timeoutExecutor.stop();
                final ExecutorService threadPool = timeoutExecutor.getThreadPool();
                if (threadPool != null) {
                    threadPool.shutdownNow();
                }
            }
        } catch (IOException ignored) {
        }

    }

    // ---------------------------------------------------------- Public Methods

    public AsyncHttpClientConfig getClientConfig() {
        return clientConfig;
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public DelayedExecutor.Resolver<Connection> getResolver() {
        return resolver;
    }

    // ------------------------------------------------------- Protected Methods

    @SuppressWarnings({ "unchecked" })
    public <T> ListenableFuture<T> execute(final Connection c, final Request request, final AsyncHandler<T> handler,
            final GrizzlyResponseFuture<T> future, final HttpTxContext httpTxContext) {
        Utils.addRequestInFlight(c);
        final RequestInfoHolder requestInfoHolder = new RequestInfoHolder(this, request, handler, future, httpTxContext);
        c.write(requestInfoHolder, createWriteCompletionHandler(future));

        return future;
    }

    void initializeTransport(final AsyncHttpClientConfig clientConfig) {

        final FilterChainBuilder secure = FilterChainBuilder.stateless();
        secure.add(new TransportFilter());

        final int timeout = clientConfig.getRequestTimeoutInMs();
        if (timeout > 0) {
            int delay = 500;
            //noinspection ConstantConditions
            if (timeout < delay) {
                delay = timeout - 10;
                if (delay <= 0) {
                    delay = timeout;
                }
            }
            timeoutExecutor = IdleTimeoutFilter.createDefaultIdleDelayedExecutor(delay, TimeUnit.MILLISECONDS);
            timeoutExecutor.start();
            final IdleTimeoutFilter.TimeoutResolver timeoutResolver = new IdleTimeoutFilter.TimeoutResolver() {
                @Override
                public long getTimeout(FilterChainContext ctx) {
                    final HttpTxContext context = HttpTxContext.get(ctx);
                    if (context != null) {
                        if (context.isWSRequest()) {
                            return clientConfig.getWebSocketIdleTimeoutInMs();
                        }
                        int requestTimeout = AsyncHttpProviderUtils.requestTimeout(clientConfig, context.getRequest());
                        if (requestTimeout > 0) {
                            return requestTimeout;
                        }
                    }
                    return IdleTimeoutFilter.FOREVER;
                }
            };
            final IdleTimeoutFilter timeoutFilter = new IdleTimeoutFilter(timeoutExecutor, timeoutResolver,
                    new IdleTimeoutFilter.TimeoutHandler() {
                        public void onTimeout(Connection connection) {
                            timeout(connection);
                        }
                    });
            secure.add(timeoutFilter);
            resolver = timeoutFilter.getResolver();
        }

        SSLContext context = clientConfig.getSSLContext();
        if (context == null) {
            try {
                context = SslUtils.getInstance().getSSLContext();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        final SSLEngineConfigurator configurator = new SSLEngineConfigurator(context, true, false, false);
        final SwitchingSSLFilter filter = new SwitchingSSLFilter(configurator);
        secure.add(filter);
        GrizzlyAsyncHttpProviderConfig providerConfig = (GrizzlyAsyncHttpProviderConfig) clientConfig.getAsyncHttpProviderConfig();

        boolean npnEnabled = NextProtoNegSupport.isEnabled();
        boolean spdyEnabled = clientConfig.isSpdyEnabled();

        if (spdyEnabled) {
            // if NPN isn't available, check to see if it has been explicitly
            // disabled.  If it has, we assume the user knows what they are doing
            // and we enable SPDY without NPN - this effectively disables standard
            // HTTP/1.1 support.
            if (!npnEnabled && providerConfig != null) {
                if ((Boolean) providerConfig.getProperty(Property.NPN_ENABLED)) {
                    // NPN hasn't been disabled, so it's most likely a configuration problem.
                    // Log a warning and disable spdy support.
                    LOGGER.warn("Next Protocol Negotiation support is not available.  SPDY support has been disabled.");
                    spdyEnabled = false;
                }
            }
        }

        final AsyncHttpClientEventFilter eventFilter;
        final EventHandler handler = new EventHandler(clientConfig);
        if (providerConfig != null) {
            eventFilter = new AsyncHttpClientEventFilter(handler, (Integer) providerConfig.getProperty(MAX_HTTP_PACKET_HEADER_SIZE));
        } else {
            eventFilter = new AsyncHttpClientEventFilter(handler);
        }
        handler.cleanup = eventFilter;
        ContentEncoding[] encodings = eventFilter.getContentEncodings();
        if (encodings.length > 0) {
            for (ContentEncoding encoding : encodings) {
                eventFilter.removeContentEncoding(encoding);
            }
        }
        if (clientConfig.isCompressionEnabled()) {
            eventFilter.addContentEncoding(new GZipContentEncoding(512, 512, new ClientEncodingFilter()));
        }
        secure.add(eventFilter);
        final AsyncHttpClientFilter clientFilter = new AsyncHttpClientFilter(this, clientConfig);
        secure.add(clientFilter);
        secure.add(new WebSocketClientFilter());

        clientTransport.getAsyncQueueIO().getWriter().setMaxPendingBytesPerConnection(AUTO_SIZE);
        if (providerConfig != null) {
            final TransportCustomizer customizer = (TransportCustomizer) providerConfig.getProperty(Property.TRANSPORT_CUSTOMIZER);
            if (customizer != null) {
                customizer.customize(clientTransport, secure);
            } else {
                doDefaultTransportConfig();
            }
        } else {
            doDefaultTransportConfig();
        }

        // FilterChain for the standard HTTP case has been configured, we now
        // copy it and modify for SPDY purposes.
        if (spdyEnabled) {
            FilterChainBuilder spdyFilterChain = createSpdyFilterChain(secure, npnEnabled);
            ProtocolNegotiator pn = new ProtocolNegotiator(spdyFilterChain.build());
            NextProtoNegSupport.getInstance().setClientSideNegotiator(clientTransport, pn);
        }

        // Install the HTTP filter chain.
        //clientTransport.setProcessor(fcb.build());
        FilterChainBuilder nonSecure = FilterChainBuilder.stateless();
        nonSecure.addAll(secure);
        int idx = nonSecure.indexOfType(SSLFilter.class);
        nonSecure.remove(idx);
        final ConnectionPool pool;
        if (providerConfig != null) {
            pool = (ConnectionPool) providerConfig.getProperty(CONNECTION_POOL);
        } else {
            pool = null;
        }
        connectionManager = new ConnectionManager(this, pool, secure, nonSecure, filter);

    }

    // ------------------------------------------------- Package Private Methods

    void touchConnection(final Connection c, final Request request) {

        int requestTimeout = AsyncHttpProviderUtils.requestTimeout(clientConfig, request);
        if (requestTimeout > 0) {
            if (resolver != null) {
                resolver.setTimeoutMillis(c, System.currentTimeMillis() + requestTimeout);
            }
        }

    }

    // --------------------------------------------------------- Private Methods

    private FilterChainBuilder createSpdyFilterChain(final FilterChainBuilder fcb, final boolean npnEnabled) {

        FilterChainBuilder spdyFcb = FilterChainBuilder.stateless();
        spdyFcb.addAll(fcb);
        int idx = spdyFcb.indexOfType(SSLFilter.class);
        Filter f = spdyFcb.get(idx);

        // Adjust the SSLFilter to support NPN
        if (npnEnabled) {
            SSLBaseFilter sslBaseFilter = (SSLBaseFilter) f;
            NextProtoNegSupport.getInstance().configure(sslBaseFilter);
        }

        // Remove the HTTP Client filter - this will be replaced by the
        // SPDY framing and handler filters.
        idx = spdyFcb.indexOfType(HttpClientFilter.class);
        spdyFcb.set(idx, new SpdyFramingFilter());
        final SpdyMode spdyMode = ((npnEnabled) ? SpdyMode.NPN : SpdyMode.PLAIN);
        AsyncSpdyClientEventFilter spdyFilter = new AsyncSpdyClientEventFilter(new EventHandler(clientConfig), spdyMode,
                clientConfig.executorService());
        spdyFilter.setInitialWindowSize(clientConfig.getSpdyInitialWindowSize());
        spdyFilter.setMaxConcurrentStreams(clientConfig.getSpdyMaxConcurrentStreams());
        spdyFcb.add(idx + 1, spdyFilter);

        // Remove the WebSocket filter - not currently supported.
        idx = spdyFcb.indexOfType(WebSocketClientFilter.class);
        spdyFcb.remove(idx);

        return spdyFcb;
    }

    private void doDefaultTransportConfig() {
        final ExecutorService service = clientConfig.executorService();
        clientTransport.setIOStrategy(WorkerThreadIOStrategy.getInstance());
        if (service != null) {
            clientTransport.setWorkerThreadPool(service);
        } else {
            final int multiplier = clientConfig.getIoThreadMultiplier();
            final int threadCount = multiplier * Runtime.getRuntime().availableProcessors();
            clientTransport.getWorkerThreadPoolConfig().setCorePoolSize(threadCount).setMaxPoolSize(threadCount);
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

        final String key = HttpTxContext.class.getName();
        HttpTxContext ctx;
        if (!Utils.isSpdyConnection(c)) {
            ctx = (HttpTxContext) c.getAttributes().getAttribute(key);
            if (ctx != null) {
                c.getAttributes().removeAttribute(key);
                ctx.abort(new TimeoutException("Timeout exceeded"));
            }
        } else {
            throw new IllegalStateException();
        }

        //        if (context != null) {
        //            HttpTxContext.set(c, null);
        //            context.abort(new TimeoutException("Timeout exceeded"));
        //        }

    }

    // ---------------------------------------------------------- Nested Classes

    private static final class ProtocolNegotiator implements ClientSideNegotiator {
        private static final SpdyVersion[] SUPPORTED_SPDY_VERSIONS =
                {SpdyVersion.SPDY_3_1, SpdyVersion.SPDY_3};

        private static final String HTTP = "HTTP/1.1";

        private final FilterChain spdyFilterChain;
        private final SpdyHandlerFilter spdyHandlerFilter;

        // -------------------------------------------------------- Constructors

        private ProtocolNegotiator(final FilterChain spdyFilterChain) {
            this.spdyFilterChain = spdyFilterChain;
            int idx = spdyFilterChain.indexOfType(SpdyHandlerFilter.class);
            spdyHandlerFilter = (SpdyHandlerFilter) spdyFilterChain.get(idx);
        }

        // ----------------------------------- Methods from ClientSideNegotiator

        @Override
        public boolean wantNegotiate(SSLEngine engine) {
            GrizzlyAsyncHttpProvider.LOGGER.info("ProtocolSelector::wantNegotiate");
            return true;
        }

        @Override
        public String selectProtocol(SSLEngine engine, LinkedHashSet<String> protocols) {
            GrizzlyAsyncHttpProvider.LOGGER.info("ProtocolSelector::selectProtocol: " + protocols);
            final Connection connection = NextProtoNegSupport.getConnection(engine);

            // Give preference to SPDY/3.1 or SPDY/3.  If not available, check for HTTP as a
            // fallback            
            for (SpdyVersion version : SUPPORTED_SPDY_VERSIONS) {
                final String versionDef = version.toString();
                if (protocols.contains(versionDef)) {
                    GrizzlyAsyncHttpProvider.LOGGER.info("ProtocolSelector::selecting: " + versionDef);
                    SSLConnectionContext sslCtx = SSLUtils.getSslConnectionContext(connection);
                    sslCtx.setNewConnectionFilterChain(spdyFilterChain);
                    final SpdySession spdySession =
                            version.newSession(connection, false, spdyHandlerFilter);

                    spdySession.setLocalStreamWindowSize(spdyHandlerFilter.getInitialWindowSize());
                    spdySession.setLocalMaxConcurrentStreams(spdyHandlerFilter.getMaxConcurrentStreams());
                    Utils.setSpdyConnection(connection);
                    SpdySession.bind(connection, spdySession);

                    return versionDef;
                }
            }
            
            if (protocols.contains(HTTP)) {
                GrizzlyAsyncHttpProvider.LOGGER.info("ProtocolSelector::selecting: " + HTTP);
                // Use the default HTTP FilterChain.
                return HTTP;
            } else {
                GrizzlyAsyncHttpProvider.LOGGER.info("ProtocolSelector::selecting NONE");
                // no protocol support.  Will close the connection when
                // onNoDeal is invoked
                return "";
            }
        }

        @Override
        public void onNoDeal(SSLEngine engine) {
            GrizzlyAsyncHttpProvider.LOGGER.info("ProtocolSelector::onNoDeal");
            final Connection connection = NextProtoNegSupport.getConnection(engine);
            connection.closeSilently();
        }
    }

    public static interface Cleanup {

        void cleanup(final FilterChainContext ctx);

    }
}
