/*
 * Copyright (c) 2011 Sonatype, Inc. All rights reserved.
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
import com.ning.http.client.ConnectionsPool;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.NullaryFunction;
import org.glassfish.grizzly.utils.DataStructures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ConnectionsPool} implementation for the Grizzly provider.
 *
 * @author The Grizzly Team
 */
public class GrizzlyConnectionsPool implements ConnectionsPool<String,Connection> {

    private final static Logger LOG = LoggerFactory.getLogger(GrizzlyConnectionsPool.class);

    private final ConcurrentHashMap<String,DelayedExecutor.IdleConnectionQueue> connectionsPool =
            new ConcurrentHashMap<String,DelayedExecutor.IdleConnectionQueue>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger totalCachedConnections = new AtomicInteger(0);
    private final boolean cacheSSLConnections;
    private final int maxConnectionsPerHost;
    private final int maxConnections;
    private final boolean unlimitedConnections;
    private final long timeout;
    private final DelayedExecutor delayedExecutor;
    private final Connection.CloseListener listener;


    // ------------------------------------------------------------ Constructors


    public GrizzlyConnectionsPool(final AsyncHttpClientConfig config) {

        cacheSSLConnections = config.isSslConnectionPoolEnabled();
        timeout = config.getIdleConnectionInPoolTimeoutInMs();
        maxConnectionsPerHost = config.getMaxConnectionPerHost();
        maxConnections = config.getMaxTotalConnections();
        unlimitedConnections = (maxConnections == -1);
        delayedExecutor = new DelayedExecutor(Executors.newSingleThreadExecutor());
        delayedExecutor.start();
        listener = new Connection.CloseListener() {
            @Override
            public void onClosed(Connection connection) throws IOException {
                if (LOG.isInfoEnabled()) {
                    LOG.info("Remote closed connection ({}).  Removing from cache", connection.toString());
                }
                GrizzlyConnectionsPool.this.removeAll(connection);
            }
        };

    }


    // -------------------------------------------- Methods from ConnectionsPool


    /**
     * {@inheritDoc}
     */
    public boolean offer(String uri, Connection connection) {

        if (cacheSSLConnections && isSecure(uri)) {
            return false;
        }

        DelayedExecutor.IdleConnectionQueue conQueue = connectionsPool.get(uri);
        if (conQueue == null) {
            DelayedExecutor.IdleConnectionQueue newPool =
                    delayedExecutor.createIdleConnectionQueue(timeout);
            conQueue = connectionsPool.putIfAbsent(uri, newPool);
            if (conQueue == null) {
                conQueue = newPool;
            }
        }

        final int size = conQueue.size();

        if (maxConnectionsPerHost == -1 || size < maxConnectionsPerHost) {
            conQueue.offer(connection);
            connection.addCloseListener(listener);
            totalCachedConnections.incrementAndGet();
            return true;
        }
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public Connection poll(String uri) {

        if (!cacheSSLConnections && isSecure(uri)) {
            return null;
        }

        Connection connection = null;
        DelayedExecutor.IdleConnectionQueue conQueue = connectionsPool.get(uri);
        if (conQueue != null) {
            boolean poolEmpty = false;
            while (!poolEmpty && connection == null) {
                if (conQueue.size() > 0) {
                    connection = conQueue.poll();
                }

                if (connection == null) {
                    poolEmpty = true;
                } else if (!connection.isOpen()) {
                    removeAll(connection);
                    connection = null;
                }
            }
        }
        if (connection != null) {
            totalCachedConnections.decrementAndGet();
        }
        if (connection != null) {
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


    // --------------------------------------------------------- Private Methods


    private boolean isSecure(String uri) {

        return (uri.charAt(0) == 'h' && uri.charAt(4) == 's');

    }


    // ---------------------------------------------------------- Nested Classes


    private static final class DelayedExecutor {

        public final static long UNSET_TIMEOUT = -1;
        private final ExecutorService threadPool;
        private final DelayedRunnable runnable = new DelayedRunnable();
        private final BlockingQueue<IdleConnectionQueue> queues =
                DataStructures.getLTQInstance(IdleConnectionQueue.class);
        private final Object sync = new Object();
        private volatile boolean isStarted;
        private final long checkIntervalMs;


        // -------------------------------------------------------- Constructors


        private DelayedExecutor(final ExecutorService threadPool) {
            this(threadPool, 1000, TimeUnit.MILLISECONDS);
        }


        // ----------------------------------------------------- Private Methods

        private DelayedExecutor(final ExecutorService threadPool,
                               final long checkInterval,
                               final TimeUnit timeunit) {
            this.threadPool = threadPool;
            this.checkIntervalMs = TimeUnit.MILLISECONDS.convert(checkInterval, timeunit);
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

        private IdleConnectionQueue createIdleConnectionQueue(final long timeout) {
            final IdleConnectionQueue queue = new IdleConnectionQueue(timeout);
            queues.add(queue);
            return queue;
        }

        @SuppressWarnings({"NumberEquality"})
        private static boolean wasModified(final Long l1, final Long l2) {
            return l1 != l2 && (l1 != null ? !l1.equals(l2) : !l2.equals(l1));
        }


        // ------------------------------------------------------- Inner Classes


        private class DelayedRunnable implements Runnable {

            @SuppressWarnings("unchecked")
            @Override
            public void run() {
                while (isStarted) {
                    final long currentTimeMs = System.currentTimeMillis();

                    for (final IdleConnectionQueue delayQueue : queues) {
                        if (delayQueue.queue.isEmpty()) continue;

                        final TimeoutResolver resolver = delayQueue.resolver;

                        for (Iterator<Connection> it = delayQueue.queue.iterator(); it.hasNext(); ) {
                            final Connection element = it.next();
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
                                        if (LOG.isInfoEnabled()) {
                                            LOG.info("Idle connection ({}) detected.  Removing from cache.", element.toString());
                                        }
                                        element.close().markForRecycle(true);
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
            final BlockingQueue<Connection> queue =
                    DataStructures.getLTQInstance(Connection.class);


            final TimeoutResolver resolver = new TimeoutResolver();
            final long timeout;

            // ---------------------------------------------------- Constructors


            public IdleConnectionQueue(final long timeout) {
                this.timeout = timeout;
            }


            // ------------------------------------------------- Private Methods


            private void offer(final Connection c) {
                if (timeout >= 0) {
                    resolver.setTimeoutMs(c, System.currentTimeMillis() + timeout);
                }
                queue.offer(c);
            }

            private Connection poll() {
                return queue.poll();
            }

            public boolean remove(final Connection c) {
                if (timeout >= 0) {
                    resolver.removeTimeout(c);

                }
                return queue.remove(c);
            }

            public int size() {
                return queue.size();
            }

            public void destroy() {
                try {
                    for (Connection c : queue) {
                        c.close().markForRecycle(true);
                    }
                    queue.clear();
                } catch (IOException ioe) {
                    // TODO log
                }
                queues.remove(this);
            }

        } // END IdleConnectionQueue


        // ------------------------------------------------------ Nested Classes


        private static final class TimeoutResolver {

            private static final String IDLE_ATTRIBUTE_NAME = "grizzly-ahc-conn-pool-idle-attribute";
            private static final Attribute<IdleRecord> IDLE_ATTR =
                    Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                            IDLE_ATTRIBUTE_NAME, new NullaryFunction<IdleRecord>() {

                        @Override
                        public IdleRecord evaluate() {
                            return new IdleRecord();
                        }
                    });


            // ------------------------------------------------- Private Methods


            private boolean removeTimeout(final Connection c) {
                IDLE_ATTR.get(c).timeoutMs = 0;
                return true;
            }

            private Long getTimeoutMs(final Connection c) {
                return IDLE_ATTR.get(c).timeoutMs;
            }

            private void setTimeoutMs(final Connection c, final long timeoutMs) {
                IDLE_ATTR.get(c).timeoutMs = timeoutMs;
            }


            // -------------------------------------------------- Nested Classes

            private static final class IdleRecord {

                private volatile long timeoutMs;

            } // END IdleRecord

        } // END TimeoutResolver

    } // END DelayedExecutor

}
