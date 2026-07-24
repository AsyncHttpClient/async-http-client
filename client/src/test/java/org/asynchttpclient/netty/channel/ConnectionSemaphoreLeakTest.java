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

import io.github.nettyplus.leakdetector.junit.NettyLeakDetectorExtension;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.exception.TooManyConnectionsPerHostException;
import org.asynchttpclient.testserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.createSslEngineFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression tests for issue #2189 over real sockets: a connection attempt that fails after TCP
 * connect must return its per-host connection permit, otherwise a run of failures against one degraded
 * origin permanently pins {@code maxConnectionsPerHost} and every later request to it fails with
 * {@link TooManyConnectionsPerHostException} instead of the real error.
 *
 * <p>The deterministic interleavings are covered by {@code NettyConnectListenerPermitLeakTest}; these prove
 * the user-visible symptom is gone.
 */
@ExtendWith(NettyLeakDetectorExtension.class)
class ConnectionSemaphoreLeakTest {

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = new HttpServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** Counts acquires/releases and signals when every acquired permit has been returned. */
    private static final class CountingSemaphore implements ConnectionSemaphore {

        private final ConnectionSemaphore delegate;
        final AtomicInteger acquires = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final CountDownLatch released = new CountDownLatch(1);

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
            delegate.releaseChannelLock(partitionKey);
            releases.incrementAndGet();
            released.countDown();
        }
    }

    /**
     * Builds a config with a counting semaphore installed. HTTP/2 is disabled because on permit exhaustion
     * the sender otherwise parks the request in an Http2ConnectionWaiter for connectTimeout before failing,
     * which would mask the symptom under a timeout rather than surfacing it.
     */
    private static DefaultAsyncHttpClientConfig.Builder countingConfig(AtomicReference<CountingSemaphore> probe) {
        return config()
                .setMaxConnectionsPerHost(1)
                .setHttp2Enabled(false)
                .setConnectionSemaphoreFactory(cfg -> {
                    CountingSemaphore semaphore = new CountingSemaphore(
                            new DefaultConnectionSemaphoreFactory().newConnectionSemaphore(cfg));
                    probe.set(semaphore);
                    return semaphore;
                });
    }

    /**
     * A TLS handshake that fails on an untrusted certificate must return the permit. Before the fix the
     * token had already been taken off the future and was never bound to the channel, so the abort released
     * nothing and the second request failed on the exhausted cap rather than on TLS.
     */
    @Test
    @Timeout(60)
    void failedTlsHandshakeDoesNotLeakThePerHostPermit() throws Exception {
        AtomicReference<CountingSemaphore> probe = new AtomicReference<>();
        AsyncHttpClientConfig cfg = countingConfig(probe)
                .setMaxRequestRetry(0)
                .setRequestTimeout(Duration.ofSeconds(10))
                .setSslEngineFactory(createSslEngineFactory(new AtomicBoolean(false)))
                .build();

        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            String url = server.getHttpsUrl() + "/foo";

            ExecutionException first = assertThrows(ExecutionException.class,
                    () -> client.prepareGet(url).execute().get(30, TimeUnit.SECONDS));
            assertFalse(first.getCause() instanceof TooManyConnectionsPerHostException,
                    "sanity: the first request must fail on TLS, not on the permit");

            CountingSemaphore semaphore = probe.get();
            assertTrue(semaphore.released.await(10, TimeUnit.SECONDS),
                    "issue #2189: a failed TLS handshake must return the per-host permit");

            // With a cap of 1, a leaked permit makes every later request to this origin fail on the cap.
            ExecutionException second = assertThrows(ExecutionException.class,
                    () -> client.prepareGet(url).execute().get(30, TimeUnit.SECONDS));
            assertFalse(second.getCause() instanceof TooManyConnectionsPerHostException,
                    "issue #2189: the second request must still fail on TLS, not on a leaked permit");

            assertEquals(semaphore.acquires.get(), semaphore.releases.get(),
                    "every acquired permit must be returned");
        }
    }

    /**
     * The reported production scenario: a peer that accepts TCP and never speaks TLS, with the request
     * timeout firing while the handshake is still in flight. The abort cannot reclaim the token, so the
     * permit must come back through the channel.
     */
    @Test
    @Timeout(60)
    void requestTimeoutDuringTlsHandshakeDoesNotLeakThePerHostPermit() throws Exception {
        List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());
        int port = startBlackHoleServer(accepted);
        AtomicReference<CountingSemaphore> probe = new AtomicReference<>();
        AsyncHttpClientConfig cfg = countingConfig(probe)
                .setMaxRequestRetry(0)
                .setRequestTimeout(Duration.ofMillis(300))
                .setHandshakeTimeout(2000)
                .setSslEngineFactory(createSslEngineFactory(new AtomicBoolean(true)))
                .build();

        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.prepareGet("https://localhost:" + port + "/foo").execute().get(30, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, e.getCause(),
                    "sanity: the request timeout must win over the handshake timeout");

            CountingSemaphore semaphore = probe.get();
            assertTrue(semaphore.released.await(20, TimeUnit.SECONDS),
                    "issue #2189: the permit must come back after a request timeout during the TLS handshake");
            assertEquals(semaphore.acquires.get(), semaphore.releases.get());
        } finally {
            closeAll(accepted);
        }
    }

    /**
     * The other half of the invariant: a successful HTTP/1.1 request must hold its permit for the life of
     * the connection and return it exactly once, so the fix must not release it early. With a cap of 1 and
     * a pool disabled, three sequential requests each need the permit back from the previous one.
     */
    @Test
    @Timeout(60)
    void successfulPlaintextRequestsReturnTheirPermit() throws Exception {
        AtomicReference<CountingSemaphore> probe = new AtomicReference<>();
        AsyncHttpClientConfig cfg = countingConfig(probe)
                .setKeepAlive(false)
                .setRequestTimeout(Duration.ofSeconds(10))
                .build();

        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            for (int i = 0; i < 3; i++) {
                server.enqueueOk();
                Response response = client.prepareGet(server.getHttpUrl() + "/foo").execute().get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode(), "request " + i + " must succeed");
            }

            CountingSemaphore semaphore = probe.get();
            assertEquals(3, semaphore.acquires.get(), "one new connection per request with keep-alive off");
            assertTrue(semaphore.releases.get() <= semaphore.acquires.get(),
                    "a permit must never be released more times than it was acquired");
        }
    }

    /** Accepts TCP connections and then says nothing at all, so a TLS handshake stalls forever. */
    private static int startBlackHoleServer(List<Socket> accepted) throws Exception {
        Exchanger<Integer> portHolder = new Exchanger<>();
        Thread thread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(0)) {
                portHolder.exchange(serverSocket.getLocalPort());
                while (!Thread.currentThread().isInterrupted()) {
                    accepted.add(serverSocket.accept());
                }
            } catch (Exception ignored) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        thread.start();
        return portHolder.exchange(0);
    }

    private static void closeAll(List<Socket> sockets) {
        synchronized (sockets) {
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // best effort test cleanup
                }
            }
            sockets.clear();
        }
    }
}
