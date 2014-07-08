/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.uri.UriComponents;
import org.asynchttpclient.util.Base64;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.connectionpool.EndpointKey;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.ssl.SSLBaseFilter;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.ssl.SSLUtils;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class ConnectionManager {

    private final static Logger LOGGER = LoggerFactory.getLogger(ConnectionManager.class);

    private static final Attribute<Boolean> DO_NOT_CACHE = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(ConnectionManager.class
            .getName());
    private final ConnectionPool connectionPool;
    private final GrizzlyAsyncHttpProvider provider;
    private final boolean canDestroyPool;
    private final Map<String, EndpointKey<SocketAddress>> endpointKeyMap = new HashMap<String, EndpointKey<SocketAddress>>();
    private final FilterChainBuilder secureBuilder;
    private final FilterChainBuilder nonSecureBuilder;
    private final boolean asyncConnect;
    private final SSLFilter sslFilter;

    // ------------------------------------------------------------ Constructors

    ConnectionManager(final GrizzlyAsyncHttpProvider provider,//
            final ConnectionPool connectionPool,//
            final FilterChainBuilder secureBuilder,//
            final FilterChainBuilder nonSecureBuilder,//
            final SSLFilter sslFilter) {

        this.provider = provider;
        final AsyncHttpClientConfig config = provider.getClientConfig();
        if (connectionPool != null) {
            this.connectionPool = connectionPool;
            canDestroyPool = false;
        } else {
            this.connectionPool = new ConnectionPool(config.getMaxConnectionPerHost(),//
                    config.getMaxTotalConnections(),//
                    null,//
                    config.getConnectionTimeoutInMs(),//
                    config.getIdleConnectionInPoolTimeoutInMs(),//
                    2000);
            canDestroyPool = true;
        }
        this.secureBuilder = secureBuilder;
        this.nonSecureBuilder = nonSecureBuilder;
        AsyncHttpProviderConfig<?, ?> providerConfig = config.getAsyncHttpProviderConfig();
        asyncConnect = providerConfig instanceof GrizzlyAsyncHttpProviderConfig ? GrizzlyAsyncHttpProviderConfig.class.cast(providerConfig)
                .isAsyncConnectMode() : false;
        this.sslFilter = sslFilter;
    }

    // ---------------------------------------------------------- Public Methods

    public void doTrackedConnection(final Request request,//
            final GrizzlyResponseFuture requestFuture,//
            final CompletionHandler<Connection> connectHandler) throws IOException {
        final EndpointKey<SocketAddress> key = getEndPointKey(request, requestFuture.getProxyServer());
        CompletionHandler<Connection> handler = wrapHandler(request, getVerifier(), connectHandler, sslFilter);
        if (asyncConnect) {
            connectionPool.take(key, handler);
        } else {
            IOException ioe = null;
            GrizzlyFuture<Connection> future = connectionPool.take(key);
            try {
                // No explicit timeout when calling get() here as the Grizzly
                // endpoint pool will time it out based on the connect timeout
                // setting.
                handler.completed(future.get());
            } catch (CancellationException e) {
                handler.cancelled();
            } catch (ExecutionException ee) {
                final Throwable cause = ee.getCause();
                if (cause instanceof ConnectionPool.MaxCapacityException) {
                    ioe = (IOException) cause;
                } else {
                    handler.failed(ee.getCause());
                }
            } catch (Exception ie) {
                handler.failed(ie);
            }
            if (ioe != null) {
                throw ioe;
            }
        }
    }

    public Connection obtainConnection(final Request request, final GrizzlyResponseFuture requestFuture) throws ExecutionException,
            InterruptedException, TimeoutException, IOException {

        final Connection c = obtainConnection0(request, requestFuture);
        markConnectionAsDoNotCache(c);
        return c;

    }

    // --------------------------------------------------Package Private Methods

    static CompletionHandler<Connection> wrapHandler(final Request request, final HostnameVerifier verifier,
            final CompletionHandler<Connection> delegate, final SSLFilter sslFilter) {
        final UriComponents uri = request.getURI();
        if (Utils.isSecure(uri) && verifier != null) {
            SSLBaseFilter.HandshakeListener handshakeListener = new SSLBaseFilter.HandshakeListener() {
                @Override
                public void onStart(Connection connection) {
                    // do nothing
                    LOGGER.debug("SSL Handshake onStart: ");
                }

                @Override
                public void onComplete(Connection connection) {
                    sslFilter.removeHandshakeListener(this);

                    final String host = uri.getHost();
                    final SSLSession session = SSLUtils.getSSLEngine(connection).getSession();
                    LOGGER.debug("SSL Handshake onComplete: session = {}, id = {}, isValid = {}, host = {}", session.toString(), Base64.encode(session.getId()), session.isValid(), host);

                    if (!verifier.verify(host, session)) {
                        connection.close(); // XXX what's the correct way to kill a connection?
                        IOException e = new ConnectException("Host name verification failed for host " + host);
                        delegate.failed(e);
                    }
                }
            };
            sslFilter.addHandshakeListener(handshakeListener);
        }
        return delegate;
    }

    static void markConnectionAsDoNotCache(final Connection c) {
        DO_NOT_CACHE.set(c, Boolean.TRUE);
    }

    static boolean isConnectionCacheable(final Connection c) {
        final Boolean canCache = DO_NOT_CACHE.get(c);
        return ((canCache != null) ? canCache : false);
    }

    // --------------------------------------------------------- Private Methods

    private HostnameVerifier getVerifier() {
        return provider.getClientConfig().getHostnameVerifier();
    }

    private EndpointKey<SocketAddress> getEndPointKey(final Request request, final ProxyServer proxyServer) throws IOException {
        final String stringKey = getPoolKey(request, proxyServer);
        EndpointKey<SocketAddress> key = endpointKeyMap.get(stringKey);
        if (key == null) {
            synchronized (endpointKeyMap) {
                key = endpointKeyMap.get(stringKey);
                if (key == null) {
                    SocketAddress address = getRemoteAddress(request, proxyServer);
                    InetAddress localAddress = request.getLocalAddress();
                    InetSocketAddress localSocketAddress = null;
                    if (localAddress != null) {
                        localSocketAddress = new InetSocketAddress(localAddress.getHostName(), 0);
                    }
                    
                    ProxyAwareConnectorHandler handler = ProxyAwareConnectorHandler.builder(provider.clientTransport)
                            .nonSecureFilterChainTemplate(nonSecureBuilder).secureFilterChainTemplate(secureBuilder)
                            .asyncHttpClientConfig(provider.getClientConfig()).uri(request.getURI()).proxyServer(proxyServer).build();
                    EndpointKey<SocketAddress> localKey = new EndpointKey<SocketAddress>(stringKey, address, localSocketAddress, handler);
                    endpointKeyMap.put(stringKey, localKey);
                    key = localKey;
                }
            }
        }
        return key;
    }

    private SocketAddress getRemoteAddress(final Request request, final ProxyServer proxyServer) {
        final UriComponents requestUri = request.getURI();
        final String host = ((proxyServer != null) ? proxyServer.getHost() : requestUri.getHost());
        final int port = ((proxyServer != null) ? proxyServer.getPort() : requestUri.getPort());
        return new InetSocketAddress(host, getPort(request.getURI(), port));
    }

    private static int getPort(final UriComponents uri, final int p) {
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

    private Connection obtainConnection0(final Request request, final GrizzlyResponseFuture requestFuture) throws ExecutionException,
            InterruptedException, TimeoutException, IOException {

        final int cTimeout = provider.getClientConfig().getConnectionTimeoutInMs();
        final FutureImpl<Connection> future = Futures.createSafeFuture();
        final CompletionHandler<Connection> ch = Futures.toCompletionHandler(future,
                createConnectionCompletionHandler(request, requestFuture, null));
        final ProxyServer proxyServer = requestFuture.getProxyServer();
        final SocketAddress address = getRemoteAddress(request, proxyServer);

        ProxyAwareConnectorHandler handler = ProxyAwareConnectorHandler.builder(provider.clientTransport)
                .nonSecureFilterChainTemplate(nonSecureBuilder)//
                .secureFilterChainTemplate(secureBuilder)//
                .asyncHttpClientConfig(provider.getClientConfig())//
                .uri(request.getURI())//
                .proxyServer(proxyServer)//
                .build();
        if (cTimeout > 0) {
            handler.connect(address, ch);
            return future.get(cTimeout, MILLISECONDS);
        } else {
            handler.connect(address, ch);
            return future.get();
        }
    }

    boolean returnConnection(final Connection c) {
        final boolean result = (DO_NOT_CACHE.get(c) == null && connectionPool.release(c));
        if (result) {
            if (provider.getResolver() != null) {
                provider.getResolver().setTimeoutMillis(c, IdleTimeoutFilter.FOREVER);
            }
        }
        return result;
    }

    void destroy() {
        if (canDestroyPool) {
            connectionPool.close();
        }
    }

    CompletionHandler<Connection> createConnectionCompletionHandler(//
            final Request request,//
            final GrizzlyResponseFuture future,//
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
                    //connection.addCloseListener(connectionMonitor);
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

    private static String getPoolKey(final Request request, ProxyServer proxyServer) {
        final ConnectionPoolKeyStrategy keyStrategy = request.getConnectionPoolKeyStrategy();
        return keyStrategy.getKey(request.getURI(), proxyServer);
    }
}
