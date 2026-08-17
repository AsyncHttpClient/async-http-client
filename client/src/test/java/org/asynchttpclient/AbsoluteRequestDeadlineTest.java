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

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AsyncHttpClientConfig#isUseAbsoluteRequestDeadline()} decides whether the request timeout bounds the
 * whole exchange or each attempt within it. A redirect builds a fresh {@code TimeoutsHolder} for the same
 * future, so with the deadline anchored on the holder each hop gets a budget of its own, and with it anchored
 * on the future a later hop gets only what is left.
 * <p>
 * Timing-based, so repeated: the margins are wide (a 600 ms budget against hops of 400 ms) but a loaded CI box
 * can still miss one.
 */
public class AbsoluteRequestDeadlineTest extends HttpTest {

    private static final Duration BUDGET = Duration.ofMillis(600);
    private static final long HOP_DELAY_MS = 400;

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

    @RepeatedIfExceptionsTest(repeats = 5)
    public void byDefaultEachHopGetsItsOwnBudget() throws Throwable {
        // Two hops of 400 ms against a 600 ms budget. Each hop on its own fits, the pair does not, so with a
        // per-attempt timeout the exchange completes.
        enqueueTwoDelayedHops();

        Throwable cause = runAndAwait(baseConfig(), null);

        assertNull(cause, "per-attempt timeouts should let both hops run, got " + cause);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void withAnAbsoluteDeadlineTheChainCannotOutrunTheBudget() throws Throwable {
        enqueueTwoDelayedHops();

        Throwable cause = runAndAwait(baseConfig().setUseAbsoluteRequestDeadline(true), null);

        assertNotNull(cause, "the exchange should have run out of budget across the two hops");
        assertEquals(TimeoutException.class, cause.getClass(), "expected a request timeout, got " + cause);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aRequestCanAskForAnAbsoluteDeadlineOnAPerAttemptClient() throws Throwable {
        enqueueTwoDelayedHops();

        Throwable cause = runAndAwait(baseConfig(), Boolean.TRUE);

        assertNotNull(cause, "the request-level override should have bounded the exchange");
        assertEquals(TimeoutException.class, cause.getClass(), "expected a request timeout, got " + cause);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aRequestCanOptOutOfAnAbsoluteDeadlineClient() throws Throwable {
        enqueueTwoDelayedHops();

        Throwable cause = runAndAwait(baseConfig().setUseAbsoluteRequestDeadline(true), Boolean.FALSE);

        assertNull(cause, "the request-level override should have restored per-attempt timeouts, got " + cause);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aSingleHopStillGetsTheWholeBudget() throws Throwable {
        // Guards the other direction: with a deadline, the first hop must not be handed a shortened budget.
        enqueueDelayed(HOP_DELAY_MS, 200, null);

        Throwable cause = runAndAwait(baseConfig().setUseAbsoluteRequestDeadline(true), null);

        assertNull(cause, "a single hop well inside the budget should not time out, got " + cause);
    }

    private DefaultAsyncHttpClientConfig.Builder baseConfig() {
        return config().setRequestTimeout(BUDGET).setFollowRedirect(true).setMaxRedirects(5);
    }

    private void enqueueTwoDelayedHops() {
        enqueueDelayed(HOP_DELAY_MS, 302, "/foo/bar2");
        enqueueDelayed(HOP_DELAY_MS, 200, null);
    }

    /**
     * Answers after {@code delayMs}, so the hop consumes a known slice of the budget before the client sees a
     * status at all.
     */
    private void enqueueDelayed(long delayMs, int status, @Nullable String location) {
        server.enqueueResponse(response -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            response.setStatus(status);
            if (location != null) {
                response.setHeader(HttpHeaderNames.LOCATION.toString(), location);
            }
        });
    }

    /**
     * @return the throwable the exchange was aborted with, or null when it completed
     */
    private Throwable runAndAwait(DefaultAsyncHttpClientConfig.Builder builder,
                                  @Nullable Boolean perRequestOverride) throws Throwable {
        AtomicReference<Throwable> cause = new AtomicReference<>();
        CountDownLatch settled = new CountDownLatch(1);

        withClient(builder).run(client -> withServer(server).run(server -> {
            BoundRequestBuilder request = client.prepareGet(server.getHttpUrl() + "/foo/bar");
            if (perRequestOverride != null) {
                request.setUseAbsoluteRequestDeadline(perRequestOverride);
            }
            request.execute(new AsyncCompletionHandler<Void>() {
                @Override
                public Void onCompleted(Response response) {
                    settled.countDown();
                    return null;
                }

                @Override
                public void onThrowable(Throwable t) {
                    cause.set(t);
                    settled.countDown();
                }
            });

            assertTrue(settled.await(30, TimeUnit.SECONDS), "the exchange neither completed nor failed");
        }));

        return cause.get();
    }
}
