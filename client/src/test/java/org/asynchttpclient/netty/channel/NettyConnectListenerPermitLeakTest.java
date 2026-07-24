/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.channel;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.timeout.TimeoutsHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for issue #2189: the per-host connection permit must be conserved across every exit
 * from {@link NettyConnectListener#onSuccess}.
 *
 * <p>{@code onSuccess} moves the permit token off the future with a {@code getAndSet(null)}, after which
 * {@code future.abort(...)} can no longer release it. Before the fix the token then lived in a bare local
 * until the TLS handshake completed, so every failure in between - a failed or timed-out handshake, a
 * crashing {@code AsyncHandler} callback - dropped it permanently: the channel closed but no release had
 * ever been bound to it. That is the "permits pinned at the cap while zero sockets to the peer exist"
 * state the issue reports.
 *
 * <p>These drive the listener directly over an {@link EmbeddedChannel}, so the interleavings are exact and
 * single-threaded rather than timing-dependent. The end-to-end path over a real socket is covered by
 * {@code ConnectionSemaphoreLeakTest}.
 */
class NettyConnectListenerPermitLeakTest {

    private static final String HTTPS_URL = "https://example.com:12345";

    private AsyncHttpClientConfig config;
    private ChannelManager channelManager;
    private Timer timer;

    @BeforeEach
    void setUp() {
        config = config().build();
        timer = new HashedWheelTimer();
        channelManager = new ChannelManager(config, timer);
    }

    @AfterEach
    void tearDown() {
        if (channelManager != null) {
            channelManager.close();
        }
        if (timer != null) {
            timer.stop();
        }
    }

    private static AsyncHandler<Object> noopHandler() {
        return new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        };
    }

    private NettyResponseFuture<Object> newFuture(String url, ConnectionSemaphore semaphore, AsyncHandler<Object> handler) {
        Request request = new RequestBuilder().setUrl(url).build();
        // maxRetry = 0 keeps onFailure from reaching requestSender.retry(), which is why every test here
        // can pass a null requestSender.
        NettyResponseFuture<Object> future = new NettyResponseFuture<>(request, handler, null, 0,
                ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, semaphore, null);
        future.setTimeoutsHolder(new TimeoutsHolder(null, future, null, config, null));
        return future;
    }

    /**
     * Available per-host permits for {@code key}. Reads the map directly rather than through
     * {@code getFreeConnectionsForHost}, which is a {@code computeIfAbsent} and would resurrect an entry
     * that was legitimately pruned after its last release.
     */
    private static int availablePerHost(PerHostConnectionSemaphore semaphore, Object key) {
        Semaphore freeConnections = semaphore.freeChannelsPerHost.get(key);
        return freeConnections != null ? freeConnections.availablePermits() : semaphore.maxConnectionsPerHost;
    }

    /**
     * Counts acquire/release calls. Once the last permit is returned the per-host entry is pruned, so a
     * double release is invisible to a permit count - only a call count can prove "exactly once".
     */
    private static final class CountingSemaphore implements ConnectionSemaphore {

        private final ConnectionSemaphore delegate;
        final AtomicInteger acquires = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        CountingSemaphore(ConnectionSemaphore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void acquireChannelLock(Object partitionKey) throws IOException {
            delegate.acquireChannelLock(partitionKey);
            acquires.incrementAndGet();
        }

        @Override
        public void acquireChannelLock(Object partitionKey, boolean nonBlocking) throws IOException {
            delegate.acquireChannelLock(partitionKey, nonBlocking);
            acquires.incrementAndGet();
        }

        @Override
        public void releaseChannelLock(Object partitionKey) {
            releases.incrementAndGet();
            delegate.releaseChannelLock(partitionKey);
        }
    }

    /**
     * The peer answers the TLS ClientHello with something that is not a TLS record, so the handshake fails
     * and {@code SslHandler} closes the connection. The permit must come back.
     */
    @Test
    void tlsHandshakeFailureReleasesThePerHostPermit() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        NettyResponseFuture<Object> future = newFuture(HTTPS_URL, semaphore, noopHandler());
        future.acquirePartitionLockLazily();
        Object key = future.basePartitionKey();
        assertEquals(0, availablePerHost(semaphore, key), "sanity: the future holds the only permit");

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertTrue(channel.isOpen(), "sanity: the TLS handshake is in flight");
            assertEquals(0, availablePerHost(semaphore, key), "the permit is still held during the handshake");

            DecoderException decoderException = assertThrows(DecoderException.class,
                    () -> channel.writeInbound(Unpooled.wrappedBuffer("not-a-tls-record!".getBytes(StandardCharsets.US_ASCII))),
                    "sanity: garbage in place of a ServerHello fails the handshake");
            assertInstanceOf(NotSslRecordException.class, decoderException.getCause());

            assertEquals(1, availablePerHost(semaphore, key),
                    "issue #2189: a failed TLS handshake must not strand the per-host permit");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The permit must be owned by the channel from the moment it leaves the future, so that closing the
     * channel alone returns it. Before the fix the binding happened only after the handshake completed, so
     * a connection that closed first leaked it.
     */
    @Test
    void permitIsBoundToTheChannelBeforeTheHandshakeCompletes() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        NettyResponseFuture<Object> future = newFuture(HTTPS_URL, semaphore, noopHandler());
        future.acquirePartitionLockLazily();
        Object key = future.basePartitionKey();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertEquals(0, availablePerHost(semaphore, key));

            channel.close().sync();

            assertEquals(1, availablePerHost(semaphore, key),
                    "issue #2189: the permit must be bound to the channel's close, not to handshake completion");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The production trigger: a peer that accepts TCP but never speaks TLS. Netty's own handshake timeout
     * eventually closes the socket - which is why the reporter saw zero sockets to the peer - and the permit
     * must come back with it. Driven on the EmbeddedChannel's virtual clock, so this costs no wall time.
     */
    @Test
    void sslHandshakeTimeoutReleasesThePerHostPermit() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        NettyResponseFuture<Object> future = newFuture(HTTPS_URL, semaphore, noopHandler());
        future.acquirePartitionLockLazily();
        Object key = future.basePartitionKey();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertEquals(0, availablePerHost(semaphore, key));

            channel.advanceTimeBy(config.getHandshakeTimeout() + 1L, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();

            assertFalse(channel.isOpen(), "sanity: the handshake timeout closes the connection");
            assertEquals(1, availablePerHost(semaphore, key),
                    "issue #2189: a handshake timeout must not strand the per-host permit");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * An AsyncHandler crashing inside the TLS window routes through onFailure, which closes the channel and
     * aborts the future - but the abort cannot reach a token onSuccess already took.
     */
    @Test
    void asyncHandlerCrashInTheTlsWindowReleasesThePerHostPermit() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        AsyncHandler<Object> crashing = new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }

            @Override
            public void onTlsHandshakeAttempt() {
                throw new IllegalStateException("boom");
            }
        };
        NettyResponseFuture<Object> future = newFuture(HTTPS_URL, semaphore, crashing);
        future.acquirePartitionLockLazily();
        Object key = future.basePartitionKey();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));

            assertFalse(channel.isOpen(), "sanity: onFailure closes the freshly connected channel");
            assertEquals(1, availablePerHost(semaphore, key),
                    "issue #2189: an AsyncHandler crash in the TLS window must not strand the permit");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The interleaving from the issue, sequenced on one thread: onSuccess takes the token, then the request
     * timeout aborts the future. The abort provably cannot reclaim the token, so conservation depends
     * entirely on the channel owning it by then.
     */
    @Test
    void requestTimeoutAbortDuringTheHandshakeDoesNotStrandThePermit() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        NettyResponseFuture<Object> future = newFuture(HTTPS_URL, semaphore, noopHandler());
        future.acquirePartitionLockLazily();
        Object key = future.basePartitionKey();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertEquals(0, availablePerHost(semaphore, key));

            future.abort(new TimeoutException("Request timeout to example.com:12345 after 100 ms"));

            assertTrue(future.isDone());

            // The orphaned socket eventually goes away (peer close, or the handshake timeout).
            channel.close().sync();

            assertEquals(1, availablePerHost(semaphore, key),
                    "issue #2189: the permit must come back when the orphaned connection closes");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A request timeout aborts via {@code NettyRequestSender.abort(future.channel(), ...)}, which closes
     * nothing when the channel is null. Publishing the channel before the handshake is what lets that abort
     * close the socket at timeout time instead of leaving it until handshakeTimeout.
     */
    @Test
    void connectingChannelIsPublishedBeforeTheTlsHandshake() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        NettyResponseFuture<Object> future = newFuture(HTTPS_URL, semaphore, noopHandler());
        future.acquirePartitionLockLazily();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));

            assertTrue(channel.isOpen(), "sanity: the handshake is still in flight");
            assertSame(channel, future.channel(),
                    "issue #2189: a request-timeout abort must be able to close the connecting channel");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Retries compound the leak. Once onSuccess has taken the token, a retry re-enters sendRequest, where
     * acquirePartitionLockLazily sees a null partitionKeyLock and takes a *fresh* permit - so each failed
     * attempt burned one, and a cap of 1 exhausted itself after the first. Replays the sequence a retry
     * performs, in order, against a cap of 1.
     */
    @Test
    void retryAfterAFailedHandshakeCanReacquireThePermit() throws Exception {
        PerHostConnectionSemaphore semaphore = new PerHostConnectionSemaphore(1, 0);
        NettyResponseFuture<Object> future = newFuture(HTTPS_URL, semaphore, noopHandler());
        future.acquirePartitionLockLazily();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertThrows(DecoderException.class,
                    () -> channel.writeInbound(Unpooled.wrappedBuffer("not-a-tls-record!".getBytes(StandardCharsets.US_ASCII))));

            // What NettyRequestSender.sendRequestWithNewChannel does on the retry.
            assertDoesNotThrow(() -> future.acquirePartitionLockLazily(false),
                    "issue #2189: a retry must not be blocked by the permit its own failed attempt leaked");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Every release path races the channel closeFuture; a double release would push the semaphore above
     * maxConnectionsPerHost and can prematurely prune a live per-host entry.
     */
    @Test
    void thePermitIsReleasedExactlyOnce() throws Exception {
        CountingSemaphore semaphore = new CountingSemaphore(new PerHostConnectionSemaphore(1, 0));
        NettyResponseFuture<Object> future = newFuture(HTTPS_URL, semaphore, noopHandler());
        future.acquirePartitionLockLazily();

        NettyConnectListener<Object> listener = new NettyConnectListener<>(future, null, channelManager, semaphore);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            listener.onSuccess(channel, new InetSocketAddress("127.0.0.1", 12345));
            assertThrows(DecoderException.class,
                    () -> channel.writeInbound(Unpooled.wrappedBuffer("not-a-tls-record!".getBytes(StandardCharsets.US_ASCII))));

            // Neither a later close nor a late abort may release a second time.
            channel.close().sync();
            future.abort(new IOException("late abort"));

            assertEquals(1, semaphore.acquires.get(), "sanity: one connection attempt took one permit");
            assertEquals(1, semaphore.releases.get(), "the permit must be released exactly once");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

}
