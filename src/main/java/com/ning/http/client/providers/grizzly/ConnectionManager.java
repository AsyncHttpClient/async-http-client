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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.ConnectorHandler;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.connectionpool.ConnectionInfo;
import org.glassfish.grizzly.connectionpool.Endpoint;
import org.glassfish.grizzly.connectionpool.MultiEndpointPool;
import org.glassfish.grizzly.connectionpool.SingleEndpointPool;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.utils.DataStructures;
import org.glassfish.grizzly.utils.Exceptions;

/**
 * Connection manager.
 * 
 * @author Grizzly team
 */
class ConnectionManager {
    private static final Attribute<Boolean> IS_NOT_KEEP_ALIVE =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                    ConnectionManager.class.getName() + ".is-not-keepalive");

    private final boolean poolingEnabled;
    private final MultiEndpointPool<SocketAddress> pool;
    
    private final TCPNIOTransport transport;
    private final TCPNIOConnectorHandler defaultConnectionHandler;
    private final AsyncHttpClientConfig config;
    private final boolean poolingSSLConnections;
    private final Map<String, Endpoint> endpointMap =
            DataStructures.<String, Endpoint>getConcurrentMap();

    // -------------------------------------------------------- Constructors
    ConnectionManager(final GrizzlyAsyncHttpProvider provider,
            final TCPNIOTransport transport,
            final GrizzlyAsyncHttpProviderConfig providerConfig) {
        
        this.transport = transport;
        config = provider.getClientConfig();
        this.poolingEnabled = config.isAllowPoolingConnections();
        this.poolingSSLConnections = config.isAllowPoolingSslConnections();
        
        defaultConnectionHandler = TCPNIOConnectorHandler.builder(transport).build();
        
        if (providerConfig != null && providerConfig.getConnectionPool() != null) {
            pool = providerConfig.getConnectionPool();
        } else {
            if (poolingEnabled) {
                final MultiEndpointPool.Builder<SocketAddress> builder
                        = MultiEndpointPool.builder(SocketAddress.class)
                        .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                        .asyncPollTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                        .maxConnectionsTotal(config.getMaxConnections())
                        .maxConnectionsPerEndpoint(config.getMaxConnectionsPerHost())
                        .keepAliveTimeout(config.getPooledConnectionIdleTimeout(), TimeUnit.MILLISECONDS)
                        .keepAliveCheckInterval(1, TimeUnit.SECONDS)
                        .connectorHandler(defaultConnectionHandler)
                        .connectionTTL(config.getConnectionTTL(), TimeUnit.MILLISECONDS)
                        .failFastWhenMaxSizeReached(true);

                if (!poolingSSLConnections) {
                    builder.endpointPoolCustomizer(new NoSSLPoolCustomizer());
                }

                pool = builder.build();
            } else {
                pool = MultiEndpointPool.builder(SocketAddress.class)
                        .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                        .asyncPollTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                        .maxConnectionsTotal(config.getMaxConnections())
                        .maxConnectionsPerEndpoint(config.getMaxConnectionsPerHost())
                        .keepAliveTimeout(0, TimeUnit.MILLISECONDS) // no pool
                        .connectorHandler(defaultConnectionHandler)
                        .failFastWhenMaxSizeReached(true)
                        .build();
            }
        }
    }

    // ----------------------------------------------------- Private Methods
    void openAsync(final Request request,
            final CompletionHandler<Connection> completionHandler)
            throws IOException {
        
        final ProxyServer proxy = ProxyUtils.getProxyServer(config, request);
        
        final String scheme;
        final String host;
        final int port;
        if (proxy != null) {
            scheme = proxy.getProtocol().getProtocol();
            host = proxy.getHost();
            port = getPort(scheme, proxy.getPort());
        } else {
            final Uri uri = request.getUri();
            scheme = uri.getScheme();
            host = uri.getHost();
            port = getPort(scheme, uri.getPort());
        }
        
        final String partitionId = getPartitionId(request, proxy);
        Endpoint endpoint = endpointMap.get(partitionId);
        if (endpoint == null) {
            final boolean isSecure = Utils.isSecure(scheme);
            endpoint = new AhcEndpoint(partitionId,
                    isSecure, host, port, request.getLocalAddress(),
                    defaultConnectionHandler);

            endpointMap.put(partitionId, endpoint);
        }

        pool.take(endpoint, completionHandler);
    }

    Connection openSync(final Request request)
            throws IOException {
        
        final ProxyServer proxy = ProxyUtils.getProxyServer(config, request);
        
        final String scheme;
        final String host;
        final int port;
        if (proxy != null) {
            scheme = proxy.getProtocol().getProtocol();
            host = proxy.getHost();
            port = getPort(scheme, proxy.getPort());
        } else {
            final Uri uri = request.getUri();
            scheme = uri.getScheme();
            host = uri.getHost();
            port = getPort(scheme, uri.getPort());
        }
        
        final boolean isSecure = Utils.isSecure(scheme);
        
        final String partitionId = getPartitionId(request, proxy);
        Endpoint endpoint = endpointMap.get(partitionId);
        if (endpoint == null) {
            endpoint = new AhcEndpoint(partitionId,
                    isSecure, host, port, request.getLocalAddress(),
                    defaultConnectionHandler);

            endpointMap.put(partitionId, endpoint);
        }

        Connection c = pool.poll(endpoint);
        
        if (c == null) {
            final Future<Connection> future =
                    defaultConnectionHandler.connect(
                    new InetSocketAddress(host, port),
                    request.getLocalAddress() != null
                            ? new InetSocketAddress(request.getLocalAddress(), 0)
                            : null);

            final int cTimeout = config.getConnectTimeout();
            try {
                c = cTimeout > 0
                        ? future.get(cTimeout, TimeUnit.MILLISECONDS)
                        : future.get();
            } catch (ExecutionException ee) {
                throw Exceptions.makeIOException(ee.getCause());
            } catch (Exception e) {
                throw Exceptions.makeIOException(e);
            } finally {
                future.cancel(false);
            }
        }

        assert c != null; // either connection is not null or exception thrown
        return c;
    }

    boolean returnConnection(final Connection c) {
        return pool.release(c);
    }

    void destroy() {
        pool.close();
    }

    boolean isReadyInPool(final Connection c) {
        final ConnectionInfo<SocketAddress> ci = pool.getConnectionInfo(c);
        return ci != null && ci.isReady();
    }
    
    static boolean isKeepAlive(final Connection connection) {
        return !IS_NOT_KEEP_ALIVE.isSet(connection);
    }
    
    private static String getPartitionId(Request request,
            ProxyServer proxyServer) {
        return request.getConnectionPoolPartitioning()
                .getPartitionKey(request.getUri(), proxyServer).toString();
    }

    private static int getPort(final String scheme, final int p) {
        int port = p;
        if (port == -1) {
            final String protocol = scheme.toLowerCase(Locale.ENGLISH);
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

    private class AhcEndpoint extends Endpoint<SocketAddress> {

        private final String partitionId;
        private final boolean isSecure;
        private final String host;
        private final int port;
        private final InetAddress localAddress;
        private final ConnectorHandler<SocketAddress> connectorHandler;
        
        private AhcEndpoint(final String partitionId,
                final boolean isSecure,
                final String host, final int port,
                final InetAddress localAddress,
                final ConnectorHandler<SocketAddress> connectorHandler) {
            
            this.partitionId = partitionId;
            this.isSecure = isSecure;
            this.host = host;
            this.port = port;
            this.localAddress = localAddress;
            this.connectorHandler = connectorHandler;
        }

        public boolean isSecure() {
            return isSecure;
        }
        
        @Override
        public Object getId() {
            return partitionId;
        }

        @Override
        public GrizzlyFuture<Connection> connect() {
            return (GrizzlyFuture<Connection>) connectorHandler.connect(
                    new InetSocketAddress(host, port),
                    localAddress != null
                            ? new InetSocketAddress(localAddress, 0)
                            : null);
        }

        @Override
        protected void onConnect(final Connection connection,
                final SingleEndpointPool<SocketAddress> pool) {
            if (pool.getKeepAliveTimeout(TimeUnit.MILLISECONDS) == 0) {
                IS_NOT_KEEP_ALIVE.set(connection, Boolean.TRUE);
            }
        }
    }
    
    private class NoSSLPoolCustomizer
            implements MultiEndpointPool.EndpointPoolCustomizer<SocketAddress> {

        @Override
        public void customize(final Endpoint<SocketAddress> endpoint,
                final MultiEndpointPool.EndpointPoolBuilder<SocketAddress> builder) {
            final AhcEndpoint ahcEndpoint = (AhcEndpoint) endpoint;
            if (ahcEndpoint.isSecure()) {
                builder.keepAliveTimeout(0, TimeUnit.SECONDS); // don't pool
            }
        }
        
    }
} // END ConnectionManager
