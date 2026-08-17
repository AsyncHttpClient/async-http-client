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

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a request timeout is delivered from, which is what
 * {@link AsyncHttpClientConfig#isUseEventLoopTimeouts()} changes. Off, every expiry in the client runs on the
 * timer's single thread; on, it runs on an event loop. The thread a timeout is delivered on is observable
 * through {@link AsyncHandler#onThrowable}, so these assert the switch rather than its side effects.
 */
public class EventLoopTimeoutTest extends HttpTest {

    private static final String IO_THREAD_POOL = "ahc-timeout-test";
    // Netty derives the timer's thread names from this, so a timer thread is the one carrying "timer".
    private static final String TIMER_MARKER = "timer";

    private HttpServer server;

    @BeforeEach
    public void start() throws Throwable {
        server = new HttpServer();
        server.start();
    }

    @AfterEach
    public void stop() throws Throwable {
        server.close();
    }

    @Test
    public void byDefaultTheTimeoutIsDeliveredFromTheTimerThread() throws Throwable {
        String thread = threadDeliveringRequestTimeout(false);

        assertTrue(thread.contains(TIMER_MARKER),
                "expected the timer thread by default, got " + thread);
    }

    @Test
    public void withEventLoopTimeoutsTheTimeoutIsDeliveredFromAnEventLoop() throws Throwable {
        String thread = threadDeliveringRequestTimeout(true);

        assertFalse(thread.contains(TIMER_MARKER),
                "expected an event loop, not the timer thread, got " + thread);
        assertTrue(thread.contains(IO_THREAD_POOL),
                "expected one of the client's I/O threads, got " + thread);
    }

    /**
     * Runs one request against an endpoint that answers well after the request timeout, and returns the name of
     * the thread {@code onThrowable} was called on.
     */
    private String threadDeliveringRequestTimeout(boolean useEventLoopTimeouts) throws Throwable {
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicReference<Throwable> cause = new AtomicReference<>();
        CountDownLatch aborted = new CountDownLatch(1);

        DefaultAsyncHttpClientConfig.Builder builder = config()
                .setThreadPoolName(IO_THREAD_POOL)
                .setRequestTimeout(Duration.ofMillis(200))
                .setUseEventLoopTimeouts(useEventLoopTimeouts);

        withClient(builder).run(client -> withServer(server).run(server -> {
            HttpHeaders headers = new DefaultHttpHeaders();
            headers.add("X-Delay", 5_000);
            server.enqueueEcho();

            client.prepareGet(server.getHttpUrl() + "/foo/bar").setHeaders(headers)
                    .execute(new AsyncCompletionHandler<Void>() {
                        @Override
                        public Void onCompleted(Response response) {
                            aborted.countDown();
                            return null;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            thread.set(Thread.currentThread().getName());
                            cause.set(t);
                            aborted.countDown();
                        }
                    });

            assertTrue(aborted.await(30, TimeUnit.SECONDS), "the request neither completed nor timed out");
        }));

        assertNotNull(cause.get(), "expected the request to be aborted");
        assertEquals(TimeoutException.class, cause.get().getClass(),
                "expected a request timeout, got " + cause.get());
        String name = thread.get();
        assertNotNull(name, "onThrowable was not called");
        return name;
    }
}
