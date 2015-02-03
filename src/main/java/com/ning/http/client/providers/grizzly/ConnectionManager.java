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

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.uri.Uri;
import com.ning.http.util.ProxyUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.HostnameVerifier;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;

/**
 * Connection manager
 * 
 * @author Grizzly team
 */
class ConnectionManager {
    private static final Attribute<Boolean> DO_NOT_CACHE =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                    ConnectionManager.class.getName());
    
    private final ConnectionPool pool;
    private final TCPNIOConnectorHandler connectionHandler;
    private final ConnectionMonitor connectionMonitor;
    private final GrizzlyAsyncHttpProvider provider;
    private final AsyncHttpClientConfig config;

    // -------------------------------------------------------- Constructors
    ConnectionManager(final GrizzlyAsyncHttpProvider provider,
            final TCPNIOTransport transport,
            final GrizzlyAsyncHttpProviderConfig providerConfig) {
        
        this.provider = provider;
        config = provider.getClientConfig();
        
        ConnectionPool connectionPool;
        if (config.isAllowPoolingConnections()) {
            ConnectionPool providedPool = providerConfig != null 
                    ? providerConfig.getConnectionPool()
                    : null;
            connectionPool = providedPool != null
                    ? providedPool
                    : new GrizzlyConnectionPool(config);
        } else {
            connectionPool = new NonCachingPool();
        }
        pool = connectionPool;
        connectionHandler = TCPNIOConnectorHandler.builder(transport).build();
        final int maxConns = config.getMaxConnections();
        connectionMonitor = new ConnectionMonitor(maxConns);
    }

    // ----------------------------------------------------- Private Methods
    static void markConnectionAsDoNotCache(final Connection c) {
        DO_NOT_CACHE.set(c, Boolean.TRUE);
    }

    static boolean isConnectionCacheable(final Connection c) {
        final Boolean canCache = DO_NOT_CACHE.get(c);
        return (canCache != null) ? canCache : false;
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
            throws IOException, ExecutionException,
            InterruptedException, TimeoutException {
        
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
        String host = (proxy != null) ? proxy.getHost() : uri.getHost();
        int port = (proxy != null) ? proxy.getPort() : uri.getPort();
        CompletionHandler<Connection> completionHandler =
                createConnectionCompletionHandler(request, requestFuture,
                        connectHandler);
        
        final HostnameVerifier verifier = config.getHostnameVerifier();
        if (Utils.isSecure(uri) && verifier != null) {
            completionHandler =
                    HostnameVerifierListener.wrapWithHostnameVerifierHandler(
                            completionHandler, verifier, uri.getHost());
        }
        if (request.getLocalAddress() != null) {
            connectionHandler.connect(
                    new InetSocketAddress(host, getPort(uri, port)),
                    new InetSocketAddress(request.getLocalAddress(), 0),
                    completionHandler);
        } else {
            connectionHandler.connect(
                    new InetSocketAddress(host, getPort(uri, port)),
                    completionHandler);
        }
    }

    private Connection obtainConnection0(final Request request,
            final GrizzlyResponseFuture requestFuture)
            throws IOException, ExecutionException,
            InterruptedException, TimeoutException {
        
        final Uri uri = request.getUri();
        final ProxyServer proxy = requestFuture.getProxy();
        String host = (proxy != null) ? proxy.getHost() : uri.getHost();
        int port = (proxy != null) ? proxy.getPort() : uri.getPort();
        int cTimeout = config.getConnectTimeout();
        FutureImpl<Connection> future = Futures.createSafeFuture();
        CompletionHandler<Connection> ch = Futures.toCompletionHandler(future,
                createConnectionCompletionHandler(request, requestFuture, null));
        if (cTimeout > 0) {
            connectionHandler.connect(
                    new InetSocketAddress(host, getPort(uri, port)), ch);
            return future.get(cTimeout, TimeUnit.MILLISECONDS);
        } else {
            connectionHandler.connect(
                    new InetSocketAddress(host, getPort(uri, port)), ch);
            return future.get();
        }
    }

    boolean returnConnection(final Request request, final Connection c) {
        ProxyServer proxyServer = ProxyUtils.getProxyServer(config, request);
        final boolean result = DO_NOT_CACHE.get(c) == null &&
                pool.offer(getPartitionId(request, proxyServer), c);
        if (result) {
            if (provider.resolver != null) {
                provider.resolver.setTimeoutMillis(c, IdleTimeoutFilter.FOREVER);
            }
        }
        return result;
    }

    boolean canReturnConnection(final Connection c) {
        return DO_NOT_CACHE.get(c) != null || pool.canCacheConnection();
    }

    void destroy() {
        pool.destroy();
    }

    CompletionHandler<Connection> createConnectionCompletionHandler(
            final Request request, final GrizzlyResponseFuture future,
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

    private static String getPartitionId(Request request,
            ProxyServer proxyServer) {
        return request.getConnectionPoolPartitioning()
                .getPartitionId(request.getUri(), proxyServer);
    }

    private static int getPort(final Uri uri, final int p) {
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
            return connections == null || connections.tryAcquire();
        }

        @Override
        public void onClosed(Connection connection, Connection.CloseType closeType) throws IOException {
            if (connections != null) {
                connections.release();
            }
        }
    } // END ConnectionMonitor
    
    
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
    
} // END ConnectionManager
