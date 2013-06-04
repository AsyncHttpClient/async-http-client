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

package org.asynchttpclient.providers.grizzly;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ConnectionsPool;

import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.utils.DataStructures;
import org.glassfish.grizzly.utils.NullaryFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.util.DateUtil.millisTime;

/**
 * {@link ConnectionsPool} implementation.
 * 
 * @author The Grizzly Team
 * @since 1.7.0
 */
@SuppressWarnings("rawtypes")
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
    private final long maxConnectionLifeTimeInMs;
    private final DelayedExecutor delayedExecutor;
    private final CloseListener listener;


    // ------------------------------------------------------------ Constructors


    public GrizzlyConnectionsPool(final AsyncHttpClientConfig config) {

        cacheSSLConnections = config.isSslConnectionPoolEnabled();
        timeout = config.getIdleConnectionInPoolTimeoutInMs();
        maxConnectionLifeTimeInMs = config.getMaxConnectionLifeTimeInMs();
        maxConnectionsPerHost = config.getMaxConnectionPerHost();
        maxConnections = config.getMaxTotalConnections();
        unlimitedConnections = (maxConnections == -1);
        delayedExecutor = new DelayedExecutor(Executors.newSingleThreadExecutor());
        delayedExecutor.start();
        listener = new CloseListener<Connection,CloseType>() {
            @Override
            public void onClosed(Connection connection, CloseType closeType) throws IOException {
                if (closeType == CloseType.REMOTELY) {
                    if (LOG.isInfoEnabled()) {
                        LOG.info("Remote closed connection ({}).  Removing from cache", connection.toString());
                    }
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
                        new Object[]{connection, uri, size, maxConnectionsPerHost, total});
            }
            return true;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("[offer] Unable to pool connection [{}] for uri [{}]. Current size (for host): [{}].  Max size (for host): [{}].  Total number of cached connections: [{}].",
                    new Object[]{connection, uri, size, maxConnectionsPerHost, totalCachedConnections.get()});
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

        private IdleConnectionQueue createIdleConnectionQueue(final long timeout, final long maxConnectionLifeTimeInMs) {
            final IdleConnectionQueue queue = new IdleConnectionQueue(timeout, maxConnectionLifeTimeInMs);
            queues.add(queue);
            return queue;
        }

        @SuppressWarnings({"NumberEquality"})
        private static boolean wasModified(final Long l1, final Long l2) {
            return l1 != l2 && (l1 != null ? !l1.equals(l2) : !l2.equals(l1));
        }


        // ------------------------------------------------------- Inner Classes


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

            // ---------------------------------------------------- Constructors


            public IdleConnectionQueue(final long timeout, final long maxConnectionLifeTimeInMs) {
                this.timeout = timeout;
                this.maxConnectionLifeTimeInMs = maxConnectionLifeTimeInMs;
            }


            // ------------------------------------------------- Private Methods


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


        // ------------------------------------------------------ Nested Classes


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


            // ------------------------------------------------- Private Methods


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


            // -------------------------------------------------- Nested Classes

            static final class IdleRecord {

                volatile long timeoutMs = UNSET_TIMEOUT;

            } // END IdleRecord

        } // END TimeoutResolver

    } // END DelayedExecutor

}
