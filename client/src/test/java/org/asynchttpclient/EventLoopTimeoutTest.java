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
package org.asynchttpclient;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which scheduler an exchange's timeouts are armed on, which is what
 * {@link AsyncHttpClientConfig#isUseEventLoopTimeouts()} chooses. Off, every expiry in the client is delivered
 * from the timer's one thread; on, it is delivered from the event loop of the channel the exchange runs on.
 * <p>
 * The scheduler an expiry came from is observable through the thread {@link AsyncHandler#onThrowable} is called
 * on, so these assert against the timer and the loops themselves rather than against thread names: a name is a
 * property of whichever thread factory the config happens to carry, the loop that owns a channel is not.
 */
public class EventLoopTimeoutTest extends HttpTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);

    private HttpServer server;
    private EventLoopGroup eventLoopGroup;
    private HashedWheelTimer timer;
    private final AtomicReference<Thread> timerThread = new AtomicReference<>();
    // Released before the server is closed, so a request left hanging on purpose never delays teardown.
    private final CountDownLatch released = new CountDownLatch(1);

    @BeforeEach
    public void start() throws Throwable {
        server = new HttpServer();
        server.start();
        eventLoopGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("ahc-timeout-test"));
        // The client's own wheel settings, so that the timer case is timed the way it would be in production.
        timer = new HashedWheelTimer(runnable -> {
            Thread thread = new Thread(runnable, "ahc-timeout-test-timer");
            thread.setDaemon(true);
            timerThread.set(thread);
            return thread;
        }, 100, TimeUnit.MILLISECONDS, 512, false);
    }

    @AfterEach
    public void stop() throws Throwable {
        released.countDown();
        server.close();
        timer.stop();
        eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).await(10, TimeUnit.SECONDS);
    }

    @Test
    public void byDefaultAnExpiryIsDeliveredFromTheTimerThread() throws Throwable {
        Recorder recorder = runAgainstAnUnansweringServer(baseConfig(false));

        assertSame(timerThread.get(), recorder.deliveredOn.get(),
                "expected the timer's own thread, got " + recorder.deliveredOn.get());
    }

    @Test
    public void anExchangeThatConnectedExpiresOnItsChannelsLoop() throws Throwable {
        // The request timeout is armed before the connect, so on this path it starts on the timer and is moved
        // to the loop once there is a channel. A deadline it could reach before connecting would be delivered
        // from the timer quite correctly -- there was no channel to deliver it from -- and prove nothing, hence
        // a budget the first connect of a JVM comfortably fits inside.
        Recorder recorder = runAgainstAnUnansweringServer(baseConfig(true).setRequestTimeout(Duration.ofSeconds(1)));

        assertNull(recorder.pooledChannel.get(), "this request was meant to open its own connection");
        assertDeliveredOnTheLoopOf(recorder.connectedChannel.get(), recorder);
    }

    @Test
    public void anExchangeOnAPooledChannelExpiresOnThatChannelsLoop() throws Throwable {
        Recorder first = new Recorder();
        Recorder second = new Recorder();

        withClient(baseConfig(true)).run(client -> withServer(server).run(server -> {
            server.enqueueOk();
            client.prepareGet(server.getHttpUrl() + "/foo/bar").execute(first);
            first.awaitCompletion();
            // Waited for, not assumed: the connection is offered to the pool around the same time as the future
            // completes, and a second request that overtook the offer would open its own connection and test
            // the wrong branch.
            assertTrue(first.offered.await(10, TimeUnit.SECONDS), "the first connection was never pooled");

            server.enqueueResponse(response -> awaitRelease());
            client.prepareGet(server.getHttpUrl() + "/foo/bar").execute(second);
            second.awaitTimeout();
        }));

        assertNotNull(second.pooledChannel.get(), "the second request did not reuse the pooled connection");
        assertDeliveredOnTheLoopOf(second.pooledChannel.get(), second);
    }

    @Test
    public void aReadTimeoutIsDeliveredFromTheChannelsLoopAsWell() throws Throwable {
        // A request timeout far enough out that the read timeout is the one that fires: the read timeout is
        // armed after the request is written, by which point the exchange is already homed on its loop.
        Recorder recorder = runAgainstAnUnansweringServer(baseConfig(true)
                .setRequestTimeout(Duration.ofSeconds(10))
                .setReadTimeout(SHORT_TIMEOUT));

        assertTrue(recorder.cause.get().getMessage().startsWith("Read timeout"),
                "expected a read timeout, got " + recorder.cause.get().getMessage());
        assertDeliveredOnTheLoopOf(recorder.connectedChannel.get(), recorder);
    }

    private DefaultAsyncHttpClientConfig.Builder baseConfig(boolean useEventLoopTimeouts) {
        return config()
                .setNettyTimer(timer)
                .setEventLoopGroup(eventLoopGroup)
                .setMaxRedirects(0)
                .setRequestTimeout(SHORT_TIMEOUT)
                .setUseEventLoopTimeouts(useEventLoopTimeouts);
    }

    /**
     * Runs one request against an endpoint that never answers, and returns what its handler saw.
     */
    private Recorder runAgainstAnUnansweringServer(DefaultAsyncHttpClientConfig.Builder builder) throws Throwable {
        Recorder recorder = new Recorder();

        withClient(builder).run(client -> withServer(server).run(server -> {
            server.enqueueResponse(response -> awaitRelease());

            client.prepareGet(server.getHttpUrl() + "/foo/bar").execute(recorder);
            recorder.awaitTimeout();
        }));

        return recorder;
    }

    private static void assertDeliveredOnTheLoopOf(Channel channel, Recorder recorder) {
        assertNotNull(channel, "the exchange never reported a channel");
        assertTrue(channel.eventLoop().inEventLoop(recorder.deliveredOn.get()),
                "expected the channel's own loop, got " + recorder.deliveredOn.get());
    }

    private void awaitRelease() {
        try {
            released.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Records the channel the exchange ran on, and the thread its expiry was delivered from.
     */
    private static final class Recorder extends AsyncCompletionHandler<Void> {

        private final CountDownLatch settled = new CountDownLatch(1);
        private final CountDownLatch offered = new CountDownLatch(1);
        private final AtomicReference<Channel> connectedChannel = new AtomicReference<>();
        private final AtomicReference<Channel> pooledChannel = new AtomicReference<>();
        private final AtomicReference<Thread> deliveredOn = new AtomicReference<>();
        private final AtomicReference<Throwable> cause = new AtomicReference<>();

        @Override
        public void onTcpConnectSuccess(InetSocketAddress remoteAddress, Channel connection) {
            connectedChannel.set(connection);
        }

        @Override
        public void onConnectionPooled(Channel connection) {
            pooledChannel.set(connection);
        }

        @Override
        public void onConnectionOffer(Channel connection) {
            offered.countDown();
        }

        @Override
        public Void onCompleted(Response response) {
            settled.countDown();
            return null;
        }

        @Override
        public void onThrowable(Throwable t) {
            deliveredOn.set(Thread.currentThread());
            cause.set(t);
            settled.countDown();
        }

        void awaitCompletion() throws InterruptedException {
            assertTrue(settled.await(30, TimeUnit.SECONDS), "the request never settled");
            assertNull(cause.get(), "the request was meant to succeed, got " + cause.get());
        }

        void awaitTimeout() throws InterruptedException {
            assertTrue(settled.await(30, TimeUnit.SECONDS), "the request neither completed nor timed out");
            assertNotNull(cause.get(), "expected the request to be aborted");
            assertEquals(TimeoutException.class, cause.get().getClass(), "expected a timeout, got " + cause.get());
            assertNotNull(deliveredOn.get(), "onThrowable was not called");
        }
    }
}
