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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.exception.TooManyConnectionsPerHostException;
import org.asynchttpclient.testserver.HttpServer;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.createSslEngineFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        AtomicReference<CountingSemaphore> probe = new AtomicReference<>();
        AsyncHttpClientConfig cfg = countingConfig(probe)
                .setMaxRequestRetry(0)
                .setRequestTimeout(Duration.ofMillis(300))
                .setHandshakeTimeout(2000)
                .setSslEngineFactory(createSslEngineFactory(new AtomicBoolean(true)))
                .build();

        try (BlackHoleServer server = new BlackHoleServer();
             AsyncHttpClient client = asyncHttpClient(cfg)) {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.prepareGet(server.url()).execute().get(30, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, e.getCause(),
                    "sanity: the request timeout must win over the handshake timeout");

            CountingSemaphore semaphore = probe.get();
            assertTrue(semaphore.released.await(20, TimeUnit.SECONDS),
                    "issue #2189: the permit must come back after a request timeout during the TLS handshake");
            assertEquals(semaphore.acquires.get(), semaphore.releases.get());
        }
    }

    /**
     * The payoff of publishing the channel before the handshake: a request timeout must close the socket
     * itself, at requestTimeout. Without that the connecting channel is invisible to the aborting timeout
     * (NettyRequestSender.abort only closes a non-null channel) and the socket, and its permit, survive
     * until the far longer handshakeTimeout - so the deadline below is what distinguishes the two.
     */
    @Test
    @Timeout(60)
    void requestTimeoutClosesTheConnectingSocketWithoutWaitingForHandshakeTimeout() throws Exception {
        AtomicReference<CountingSemaphore> probe = new AtomicReference<>();
        AsyncHttpClientConfig cfg = countingConfig(probe)
                .setMaxRequestRetry(0)
                .setRequestTimeout(Duration.ofMillis(300))
                .setHandshakeTimeout(10_000)
                .setSslEngineFactory(createSslEngineFactory(new AtomicBoolean(true)))
                .build();

        try (BlackHoleServer server = new BlackHoleServer();
             AsyncHttpClient client = asyncHttpClient(cfg)) {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> client.prepareGet(server.url()).execute().get(30, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, e.getCause());

            Socket peer = server.awaitFirstConnection(10, TimeUnit.SECONDS);
            assertNotNull(peer, "sanity: the client established the TCP connection");
            // Well inside handshakeTimeout: if the abort could not close the connecting channel, draining
            // this socket blocks until the read times out instead of reaching EOF.
            peer.setSoTimeout(3000);
            try (InputStream in = peer.getInputStream()) {
                byte[] buffer = new byte[1024];
                while (in.read(buffer) >= 0) {
                    // drain the ClientHello until the client closes its end
                }
            } catch (SocketTimeoutException timeout) {
                fail("issue #2189: the request timeout must close the connecting socket, not leave it "
                        + "until handshakeTimeout");
            }

            assertTrue(probe.get().released.await(10, TimeUnit.SECONDS), "the permit must come back with it");
        }
    }

    /**
     * The other half of the invariant: an HTTP/1.1 connection must hold its permit for as long as it is
     * serving, so the fix must not release it early. With a cap of 1 and a request held open, a second
     * request to the same host must be refused - a permit released at handshake/response time instead of at
     * close would let it through and breach maxConnectionsPerHost.
     */
    @Test
    @Timeout(60)
    void permitIsHeldWhileTheConnectionIsStillServing() throws Exception {
        AtomicReference<CountingSemaphore> probe = new AtomicReference<>();
        AsyncHttpClientConfig cfg = countingConfig(probe)
                .setRequestTimeout(Duration.ofSeconds(30))
                .build();

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        server.enqueue(new AbstractHandler() {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest,
                               HttpServletRequest request, HttpServletResponse response) throws IOException {
                baseRequest.setHandled(true);
                started.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                response.setStatus(200);
                response.getOutputStream().flush();
            }
        });

        try (AsyncHttpClient client = asyncHttpClient(cfg)) {
            Future<Response> inFlight = client.prepareGet(server.getHttpUrl() + "/foo").execute();
            assertTrue(started.await(20, TimeUnit.SECONDS), "sanity: the first request reached the server");

            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> client.prepareGet(server.getHttpUrl() + "/foo").execute().get(20, TimeUnit.SECONDS),
                    "a second connection must not be admitted while the first still holds the only permit");
            assertInstanceOf(TooManyConnectionsPerHostException.class, refused.getCause());

            release.countDown();
            assertEquals(200, inFlight.get(30, TimeUnit.SECONDS).getStatusCode());
        }
    }

    /**
     * A permit must be returned exactly once per connection: with keep-alive off, three sequential requests
     * against a cap of 1 each need the previous connection's permit back.
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
            assertEquals(semaphore.acquires.get(), semaphore.releases.get(),
                    "every connection's permit must be returned exactly once");
        }
    }

    /**
     * Accepts TCP connections on the loopback address and then says nothing at all, so a TLS handshake
     * started against it stalls until something else closes the socket. Bound to the loopback address rather
     * than the wildcard so the client's connect cannot miss it where localhost resolves to ::1 first.
     */
    private static final class BlackHoleServer implements Closeable {

        private final ServerSocket serverSocket;
        private final List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());
        private final BlockingQueue<Socket> firstAccepted = new ArrayBlockingQueue<>(1);
        private final Thread thread;

        BlackHoleServer() throws IOException {
            serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Socket socket = serverSocket.accept();
                        accepted.add(socket);
                        firstAccepted.offer(socket);
                    }
                } catch (IOException ignored) {
                    // the server socket was closed: normal shutdown
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String url() {
            return "https://" + serverSocket.getInetAddress().getHostAddress() + ':' + serverSocket.getLocalPort() + "/foo";
        }

        Socket awaitFirstConnection(long timeout, TimeUnit unit) throws InterruptedException {
            return firstAccepted.poll(timeout, unit);
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // best effort test cleanup
            }
            thread.interrupt();
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // best effort test cleanup
                    }
                }
                accepted.clear();
            }
        }
    }
}
