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


import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.ContentEncoding;
import org.glassfish.grizzly.http.EncodingFilter;
import org.glassfish.grizzly.http.GZipContentEncoding;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.nio.RoundRobinConnectionDistributor;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.websockets.WebSocketFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProvider;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Request;
import com.ning.http.client.SSLEngineFactory;
import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.*;

/**
 * A Grizzly 2.0-based implementation of {@link AsyncHttpProvider}.
 *
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyAsyncHttpProvider implements AsyncHttpProvider {

    private final static Logger LOGGER = LoggerFactory.getLogger(GrizzlyAsyncHttpProvider.class);
    
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
                        // GrizzlyResponseFuture has been already completed (canceled?)
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
            connectionManager.openAsync(request, connectHandler);
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
                            final Connection connection = ctx.getConnection();
                            
                            if (connectionManager.isReadyInPool(connection)) {
                                // if the connection is in pool - let ConnectionManager take care of its life cycle
                                return IdleTimeoutFilter.FOREVER;
                            }
                            
                            final HttpTransactionContext context
                                    = HttpTransactionContext.currentTransaction(connection);
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
        fcb.add(sslFilter);
        
        final AhcEventFilter eventFilter = new
                AhcEventFilter(this,
                        (Integer) providerConfig.getProperty(MAX_HTTP_PACKET_HEADER_SIZE));
        final AsyncHttpClientFilter clientFilter = new AsyncHttpClientFilter(this);
        ContentEncoding[] encodings = eventFilter.getContentEncodings();
        if (encodings.length > 0) {
            for (ContentEncoding encoding : encodings) {
                eventFilter.removeContentEncoding(encoding);
            }
        }
        
        if ((Boolean) providerConfig.getProperty(DECOMPRESS_RESPONSE)) {
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



