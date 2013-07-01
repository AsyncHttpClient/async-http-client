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

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionPoolKeyStrategy;
import org.asynchttpclient.ConnectionsPool;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.util.ProxyUtils;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.utils.DataStructures;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.glassfish.grizzly.utils.NullaryFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.util.DateUtil.millisTime;

public class ConnectionManager {

    private static final Attribute<Boolean> DO_NOT_CACHE =
        Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(ConnectionManager.class.getName());
    private final ConnectionsPool<String,Connection> pool;
    private final ProxyAwareConnectorHandler connectionHandler;
    private final ConnectionMonitor connectionMonitor;
    private final GrizzlyAsyncHttpProvider provider;
    private final boolean connectAsync;


    // ------------------------------------------------------------ Constructors


    @SuppressWarnings("unchecked")
    ConnectionManager(final GrizzlyAsyncHttpProvider provider,
                      final ProxyAwareConnectorHandler connectionHandler) {

        ConnectionsPool<String,Connection> connectionPool;
        this.provider = provider;
        final AsyncHttpClientConfig config = provider.getClientConfig();
        if (config.getAllowPoolingConnection()) {
            ConnectionsPool pool = config.getConnectionsPool();
            if (pool != null) {
                //noinspection unchecked
                connectionPool = (ConnectionsPool<String, Connection>) pool;
            } else {
                connectionPool = new Pool((config));
            }
        } else {
            connectionPool = new NonCachingPool();
        }
        pool = connectionPool;
        this.connectionHandler = connectionHandler;
        connectionMonitor = new ConnectionMonitor(config.getMaxTotalConnections());
        connectAsync = provider.getClientConfig().isAsyncConnectMode();

    }


    // ---------------------------------------------------------- Public Methods


    public void doTrackedConnection(final Request request,
                                    final GrizzlyResponseFuture requestFuture,
                                    final CompletionHandler<Connection> connectHandler)
    throws IOException {
        Connection c =
                pool.poll(getPoolKey(request, requestFuture.getProxyServer()));
        if (c == null) {
            if (!connectionMonitor.acquire()) {
                throw new IOException("Max connections exceeded");
            }
            if (connectAsync) {
                doAsyncConnect(request, requestFuture, connectHandler);
            } else {
                try {
                    c = obtainConnection0(request, requestFuture);
                    connectHandler.completed(c);
                } catch (Exception e) {
                    connectHandler.failed(e);
                }
            }
        } else {
            provider.touchConnection(c, request);
            connectHandler.completed(c);
        }

    }

    public Connection obtainConnection(final Request request,
                                       final GrizzlyResponseFuture requestFuture)
    throws ExecutionException, InterruptedException, TimeoutException {

        final Connection c = obtainConnection0(request, requestFuture);
        markConnectionAsDoNotCache(c);
        return c;

    }

    void doAsyncConnect(final Request request,
                        final GrizzlyResponseFuture requestFuture,
                        final CompletionHandler<Connection> connectHandler) {

        CompletionHandler<Connection> ch =
                createConnectionCompletionHandler(request,
                                                  requestFuture,
                                                  connectHandler);
        final ProxyServer proxyServer = requestFuture.getProxyServer();
        ProxyAwareConnectorHandler.setRequest(request);
        ProxyAwareConnectorHandler.setProxy(proxyServer);
        SocketAddress address = getRemoteAddress(request, proxyServer);
        if (request.getLocalAddress() != null) {
            connectionHandler.connect(new InetSocketAddress(request.getLocalAddress(), 0),
                                      address,
                                      ch);
        } else {
            connectionHandler.connect(address, ch);
        }

    }


    // --------------------------------------------------Package Private Methods


    static void markConnectionAsDoNotCache(final Connection c) {
        DO_NOT_CACHE.set(c, Boolean.TRUE);
    }

    static boolean isConnectionCacheable(final Connection c) {
        final Boolean canCache =  DO_NOT_CACHE.get(c);
        return ((canCache != null) ? canCache : false);
    }

    private SocketAddress getRemoteAddress(final Request request,
                                           final ProxyServer proxyServer) {
        final URI requestUri = request.getURI();
        final String host = ((proxyServer != null)
                ? proxyServer.getHost()
                : requestUri.getHost());
        final int port = ((proxyServer != null)
                ? proxyServer.getPort()
                : requestUri.getPort());
        return new InetSocketAddress(host, getPort(request.getURI(), port));
    }

    private static int getPort(final URI uri, final int p) {
        int port = p;
        if (port == -1) {
            final String protocol = uri.getScheme().toLowerCase();
            if ("http".equals(protocol) || "ws".equals(protocol)) {
                port = 80;
            } else if ("https".equals(protocol) || "wss".equals(protocol)) {
                port = 443;
            } else {
                throw new IllegalArgumentException(
                        "Unknown protocol: " + protocol);
            }
        }
        return port;
    }

    private Connection obtainConnection0(final Request request,
                                         final GrizzlyResponseFuture requestFuture)
    throws ExecutionException, InterruptedException, TimeoutException {

        final int cTimeout = provider.getClientConfig().getConnectionTimeoutInMs();
        final FutureImpl<Connection> future = Futures.createSafeFuture();
        final CompletionHandler<Connection> ch = Futures.toCompletionHandler(future,
                createConnectionCompletionHandler(request, requestFuture, null));
        final ProxyServer proxyServer = requestFuture.getProxyServer();
        final SocketAddress address = getRemoteAddress(request, proxyServer);
        ProxyAwareConnectorHandler.setRequest(request);
        ProxyAwareConnectorHandler.setProxy(proxyServer);
        if (cTimeout > 0) {
            connectionHandler.connect(address, ch);
            return future.get(cTimeout, TimeUnit.MILLISECONDS);
        } else {
            connectionHandler.connect(address, ch);
            return future.get();
        }
    }

    boolean returnConnection(final Request request, final Connection c) {
        ProxyServer proxyServer = ProxyUtils.getProxyServer(
                provider.getClientConfig(), request);
        final boolean result = (DO_NOT_CACHE.get(c) == null
                                   && pool.offer(getPoolKey(request, proxyServer), c));
        if (result) {
            if (provider.getResolver() != null) {
                provider.getResolver().setTimeoutMillis(c, IdleTimeoutFilter.FOREVER);
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

    private static String getPoolKey(final Request request, ProxyServer proxyServer) {
        final ConnectionPoolKeyStrategy keyStrategy = request.getConnectionPoolKeyStrategy();
        URI uri = proxyServer != null? proxyServer.getURI(): request.getURI();
        return keyStrategy.getKey(uri);
    }


    // ---------------------------------------------------------- Nested Classes


    private static class ConnectionMonitor implements
            CloseListener<Closeable,CloseType> {

    private final Semaphore connections;


        // -------------------------------------------------------- Constructors


        ConnectionMonitor(final int maxConnections) {
            if (maxConnections != -1) {
                connections = new Semaphore(maxConnections);
            } else {
                connections = null;
            }
        }


        // ------------------------------------------------------ Public Methods


        public boolean acquire() {

            return (connections == null || connections.tryAcquire());

        }


        // ------------------------------- Methods from Connection.CloseListener


        @Override
        public void onClosed(Closeable closeable, CloseType closeType) throws IOException {

            if (connections != null) {
                connections.release();
            }

        }

    } // END ConnectionMonitor


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

    /**
     * {@link org.asynchttpclient.ConnectionsPool} implementation.
     *
     * @author The Grizzly Team
     * @since 1.7.0
     */
    @SuppressWarnings("rawtypes")
    public static class Pool implements ConnectionsPool<String,Connection> {

        private final static Logger
                LOG = LoggerFactory.getLogger(Pool.class);

        private final
        ConcurrentHashMap<String,DelayedExecutor.IdleConnectionQueue>
                connectionsPool =
                new ConcurrentHashMap<String,DelayedExecutor.IdleConnectionQueue>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicInteger totalCachedConnections = new AtomicInteger(0);
        private final boolean cacheSSLConnections;
        private final int maxConnectionsPerHost;
        private final int maxConnections;
        private final boolean unlimitedConnections;
        private final long timeout;
        private final long maxConnectionLifeTimeInMs;
        private final DelayedExecutor delayedExecutor;
        private final CloseListener listener;


        // -------------------------------------------------------- Constructors


        public Pool(final AsyncHttpClientConfig config) {

            cacheSSLConnections = config.isSslConnectionPoolEnabled();
            timeout = config.getIdleConnectionInPoolTimeoutInMs();
            maxConnectionLifeTimeInMs = config.getMaxConnectionLifeTimeInMs();
            maxConnectionsPerHost = config.getMaxConnectionPerHost();
            maxConnections = config.getMaxTotalConnections();
            unlimitedConnections = (maxConnections == -1);
            delayedExecutor = new DelayedExecutor(
                    Executors.newSingleThreadExecutor());
            delayedExecutor.start();
            listener = new CloseListener<Connection,CloseType>() {
                @Override
                public void onClosed(Connection connection, CloseType closeType) throws IOException {
                    if (closeType == CloseType.REMOTELY) {
                        if (LOG.isInfoEnabled()) {
                            LOG.info("Remote closed connection ({}).  Removing from cache", connection.toString());
                        }
                    }
                    Pool.this.removeAll(connection);
                }
            };

        }


        // ---------------------------------------- Methods from ConnectionsPool


        /**
         * {@inheritDoc}
         */
        public boolean offer(String uri, Connection connection) {

            if (cacheSSLConnections && Utils.isSecure(uri)) {
                return false;
            }

            DelayedExecutor.IdleConnectionQueue conQueue = connectionsPool.get(uri);
            if (conQueue == null) {
                LOG.debug("Creating new Connection queue for uri [{}] and connection [{}]",
                            uri, connection);
                DelayedExecutor.IdleConnectionQueue newPool =
                        delayedExecutor.createIdleConnectionQueue(timeout, maxConnectionLifeTimeInMs);
                conQueue = connectionsPool.putIfAbsent(uri, newPool);
                if (conQueue == null) {
                    conQueue = newPool;
                }
            }

            final int size = conQueue.size();
            if (maxConnectionsPerHost == -1 || size < maxConnectionsPerHost) {
                conQueue.offer(connection);
                connection.addCloseListener(listener);
                final int total = totalCachedConnections.incrementAndGet();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[offer] Pooling connection [{}] for uri [{}].  Current size (for host; before pooling): [{}].  Max size (for host): [{}].  Total number of cached connections: [{}].",
                              connection, uri, size, maxConnectionsPerHost, total);
                }
                return true;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("[offer] Unable to pool connection [{}] for uri [{}]. Current size (for host): [{}].  Max size (for host): [{}].  Total number of cached connections: [{}].",
                          connection, uri, size, maxConnectionsPerHost, totalCachedConnections.get());
            }

            return false;
        }


        /**
         * {@inheritDoc}
         */
        public Connection poll(String uri) {

            if (!cacheSSLConnections && Utils.isSecure(uri)) {
                return null;
            }

            Connection connection = null;
            DelayedExecutor.IdleConnectionQueue conQueue = connectionsPool.get(uri);
            if (conQueue != null) {
                boolean poolEmpty = false;
                while (!poolEmpty && connection == null) {
                    if (!conQueue.isEmpty()) {
                        connection = conQueue.poll();
                    }

                    if (connection == null) {
                        poolEmpty = true;
                    } else if (!connection.isOpen()) {
                        removeAll(connection);
                        connection = null;
                    }
                }
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[poll] No existing queue for uri [{}].",
                            new Object[]{uri});
                }
            }
            if (connection != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[poll] Found pooled connection [{}] for uri [{}].",
                            new Object[]{connection, uri});
                }
                totalCachedConnections.decrementAndGet();
                connection.removeCloseListener(listener);
            }
            return connection;

        }


        /**
         * {@inheritDoc}
         */
        public boolean removeAll(Connection connection) {

            if (connection == null || closed.get()) {
                return false;
            }
            connection.removeCloseListener(listener);
            boolean isRemoved = false;
            for (Map.Entry<String, DelayedExecutor.IdleConnectionQueue> entry : connectionsPool.entrySet()) {
                boolean removed = entry.getValue().remove(connection);
                isRemoved |= removed;
            }
            return isRemoved;

        }


        /**
         * {@inheritDoc}
         */
        public boolean canCacheConnection() {

            return !(!closed.get()
                           && !unlimitedConnections
                           && totalCachedConnections.get() >= maxConnections);

        }

        /**
         * {@inheritDoc}
         */
        public void destroy() {

            if (closed.getAndSet(true)) {
                return;
            }

            for (Map.Entry<String, DelayedExecutor.IdleConnectionQueue> entry : connectionsPool.entrySet()) {
                entry.getValue().destroy();
            }
            connectionsPool.clear();
            delayedExecutor.stop();
            delayedExecutor.getThreadPool().shutdownNow();

        }


        // ------------------------------------------------------ Nested Classes


        private static final class DelayedExecutor {

            private static final long DEFAULT_CHECK_INTERVAL = 1000;

            public final static long UNSET_TIMEOUT = -1;
            private final ExecutorService threadPool;
            private final DelayedRunnable runnable = new DelayedRunnable();
            private final BlockingQueue<IdleConnectionQueue> queues =
                    DataStructures.getLTQInstance(IdleConnectionQueue.class);
            private final Object sync = new Object();
            private volatile boolean isStarted;
            private final long checkIntervalMs;


            // ---------------------------------------------------- Constructors


            private DelayedExecutor(final ExecutorService threadPool) {
                this(threadPool, DEFAULT_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
            }


            // ------------------------------------------------- Private Methods

            private DelayedExecutor(final ExecutorService threadPool,
                                   final long checkInterval,
                                   final TimeUnit timeunit) {
                this.threadPool = threadPool;
                this.checkIntervalMs = TimeUnit.MILLISECONDS.convert(
                        checkInterval, timeunit);
            }

            private void start() {
                synchronized (sync) {
                    if (!isStarted) {
                        isStarted = true;
                        threadPool.execute(runnable);
                    }
                }
            }

            private void stop() {
                synchronized (sync) {
                    if (isStarted) {
                        isStarted = false;
                        sync.notify();
                    }
                }
            }

            private ExecutorService getThreadPool() {
                return threadPool;
            }

            private IdleConnectionQueue createIdleConnectionQueue(final long timeout, final long maxConnectionLifeTimeInMs) {
                final IdleConnectionQueue queue = new IdleConnectionQueue(timeout, maxConnectionLifeTimeInMs);
                queues.add(queue);
                return queue;
            }

            @SuppressWarnings({"NumberEquality"})
            private static boolean wasModified(final Long l1, final Long l2) {
                return l1 != l2 && (l1 != null ? !l1.equals(l2) : !l2.equals(l1));
            }


            // --------------------------------------------------- Inner Classes


            private class DelayedRunnable implements Runnable {

                @Override
                public void run() {
                    while (isStarted) {
                        final long currentTimeMs = millisTime();

                        for (final IdleConnectionQueue delayQueue : queues) {
                            if (delayQueue.queue.isEmpty()) continue;

                            final TimeoutResolver resolver = delayQueue.resolver;

                            for (Iterator<Connection> it = delayQueue.queue.iterator(); it.hasNext(); ) {
                                final Connection<?> element = (Connection<?>) it.next();
                                final Long timeoutMs = resolver.getTimeoutMs(element);

                                if (timeoutMs == null || timeoutMs == UNSET_TIMEOUT) {
                                    it.remove();
                                    if (wasModified(timeoutMs,
                                                    resolver.getTimeoutMs(element))) {
                                        delayQueue.queue.offer(element);
                                    }
                                } else if (currentTimeMs - timeoutMs >= 0) {
                                    it.remove();
                                    if (wasModified(timeoutMs,
                                                    resolver.getTimeoutMs(element))) {
                                        delayQueue.queue.offer(element);
                                    } else {
                                        try {
                                            if (LOG.isDebugEnabled()) {
                                                LOG.debug("Idle connection ({}) detected.  Removing from cache.", element.toString());
                                            }
                                            element.close().recycle(true);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                            }
                        }

                        synchronized (sync) {
                            if (!isStarted) return;

                            try {
                                sync.wait(checkIntervalMs);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                }

            } // END DelayedRunnable


            final class IdleConnectionQueue {
                final ConcurrentLinkedQueue<Connection> queue =
                        new ConcurrentLinkedQueue<Connection>();


                final TimeoutResolver resolver = new TimeoutResolver();
                final long timeout;
                final AtomicInteger count = new AtomicInteger(0);
                final long maxConnectionLifeTimeInMs;

                // ------------------------------------------------ Constructors


                public IdleConnectionQueue(final long timeout, final long maxConnectionLifeTimeInMs) {
                    this.timeout = timeout;
                    this.maxConnectionLifeTimeInMs = maxConnectionLifeTimeInMs;
                }


                // ------------------------------------- Package Private Methods


                void offer(final Connection c) {
                    long timeoutMs = UNSET_TIMEOUT;
                    long currentTime = millisTime();
                    if (maxConnectionLifeTimeInMs < 0 && timeout >= 0) {
                        timeoutMs = currentTime + timeout;
                    } else if (maxConnectionLifeTimeInMs >= 0) {
                        long t = resolver.getTimeoutMs(c);
                        if (t == UNSET_TIMEOUT) {
                            if (timeout >= 0) {
                                timeoutMs = currentTime + Math.min(maxConnectionLifeTimeInMs, timeout);
                            } else {
                                timeoutMs = currentTime + maxConnectionLifeTimeInMs;
                            }
                        } else {
                            if (timeout >= 0) {
                                timeoutMs = Math.min(t, currentTime + timeout);
                            }
                        }
                    }
                    resolver.setTimeoutMs(c, timeoutMs);
                    queue.offer(c);
                    count.incrementAndGet();
                }

                Connection poll() {
                    count.decrementAndGet();
                    return queue.poll();
                }

                boolean remove(final Connection c) {
                    if (timeout >= 0) {
                        resolver.removeTimeout(c);

                    }
                    count.decrementAndGet();
                    return queue.remove(c);
                }

                int size() {
                    return count.get();
                }

                boolean isEmpty() {
                    return (count.get() == 0);
                }

                void destroy() {
                    for (Connection c : queue) {
                        c.close().recycle(true);
                    }
                    queue.clear();
                    queues.remove(this);
                }

            } // END IdleConnectionQueue


            // -------------------------------------------------- Nested Classes


            static final class TimeoutResolver {

                private static final String IDLE_ATTRIBUTE_NAME = "grizzly-ahc-conn-pool-idle-attribute";
                private static final Attribute<IdleRecord> IDLE_ATTR =
                        Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                                IDLE_ATTRIBUTE_NAME, new NullaryFunction<IdleRecord>() {

                            @Override
                            public IdleRecord evaluate() {
                                return new IdleRecord();
                            }
                        });


                // --------------------------------------------- Private Methods


                boolean removeTimeout(final Connection c) {
                    IDLE_ATTR.get(c).timeoutMs = 0;
                    return true;
                }

                Long getTimeoutMs(final Connection c) {
                    return IDLE_ATTR.get(c).timeoutMs;
                }

                void setTimeoutMs(final Connection c, final long timeoutMs) {
                    IDLE_ATTR.get(c).timeoutMs = timeoutMs;
                }


                // ---------------------------------------------- Nested Classes

                static final class IdleRecord {

                    volatile long timeoutMs = UNSET_TIMEOUT;

                } // END IdleRecord

            } // END TimeoutResolver

        } // END DelayedExecutor

    } // END Pool
}
