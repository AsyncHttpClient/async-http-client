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
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.DefaultThreadFactory;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final String FIRST_HOP = "/foo/bar";
    private static final String SECOND_HOP = "/foo/bar2";

    private HttpServer server;
    // Coarse on purpose, for the one case that needs the request timeout not to fire: a wheel answers a
    // deadline on its first tick at or after it, so at this granularity nothing expires inside a test.
    private HashedWheelTimer stalledTimer;

    @BeforeEach
    public void start() throws Throwable {
        server = new HttpServer();
        server.start();
        stalledTimer = new HashedWheelTimer(new DefaultThreadFactory("ahc-stalled-timer", true),
                30, TimeUnit.SECONDS, 512, false);
    }

    @AfterEach
    public void stop() throws Throwable {
        server.close();
        stalledTimer.stop();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void byDefaultEachHopGetsItsOwnBudget() throws Throwable {
        // Two hops of 400 ms against a 600 ms budget. Each hop on its own fits, the pair does not, so with a
        // per-attempt timeout the exchange completes.
        enqueueTwoDelayedHops();

        Outcome outcome = runAndAwait(baseConfig(), null);

        outcome.assertReachedTheSecondHop();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void withAnAbsoluteDeadlineTheChainCannotOutrunTheBudget() throws Throwable {
        enqueueTwoDelayedHops();

        Outcome outcome = runAndAwait(baseConfig().setUseAbsoluteRequestDeadline(true), null);

        outcome.assertTimedOut();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aRequestCanAskForAnAbsoluteDeadlineOnAPerAttemptClient() throws Throwable {
        enqueueTwoDelayedHops();

        Outcome outcome = runAndAwait(baseConfig(), Boolean.TRUE);

        outcome.assertTimedOut();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aRequestCanOptOutOfAnAbsoluteDeadlineClient() throws Throwable {
        enqueueTwoDelayedHops();

        Outcome outcome = runAndAwait(baseConfig().setUseAbsoluteRequestDeadline(true), Boolean.FALSE);

        outcome.assertReachedTheSecondHop();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aSingleHopStillGetsTheWholeBudget() throws Throwable {
        // Guards the other direction: with a deadline, the first hop must not be handed a shortened budget.
        enqueueDelayed(HOP_DELAY_MS, 200, null);

        Outcome outcome = runAndAwait(baseConfig().setUseAbsoluteRequestDeadline(true), null);

        outcome.assertCompletedAt(FIRST_HOP);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aHopWithNothingLeftToSpendIsNeverSent() throws Throwable {
        // The first hop answers after the budget is gone, and the timer is too coarse to have expired the
        // exchange in the meantime. That is the window in which a redirect used to be written anyway: a permit
        // taken, a connection taken, the body on the wire, and only then a TimeoutException that reads to the
        // caller as though nothing had been sent.
        AtomicBoolean secondHopServed = new AtomicBoolean();
        enqueueDelayed(BUDGET.toMillis() + HOP_DELAY_MS, 302, SECOND_HOP);
        server.enqueueResponse(response -> {
            secondHopServed.set(true);
            response.setStatus(200);
        });

        Outcome outcome = runAndAwait(baseConfig()
                .setNettyTimer(stalledTimer)
                .setUseAbsoluteRequestDeadline(true), null);

        outcome.assertTimedOutBeforeSending();
        assertFalse(secondHopServed.get(), "the redirect target was sent a request with no budget left");
    }

    private DefaultAsyncHttpClientConfig.Builder baseConfig() {
        return config().setRequestTimeout(BUDGET).setFollowRedirect(true).setMaxRedirects(5);
    }

    private void enqueueTwoDelayedHops() {
        enqueueDelayed(HOP_DELAY_MS, 302, SECOND_HOP);
        enqueueDelayed(HOP_DELAY_MS, 200, null);
    }

    /**
     * What the exchange ended as. The passing cases assert where it ended and not merely that nothing was
     * thrown: a dropped {@code Location} header, or redirects turned off, would satisfy "no exception" having
     * run one hop, which is the opposite of what they are for.
     */
    private static final class Outcome {

        private final @Nullable Throwable cause;
        private final @Nullable Response response;

        private Outcome(@Nullable Throwable cause, @Nullable Response response) {
            this.cause = cause;
            this.response = response;
        }

        void assertCompletedAt(String path) {
            assertNull(cause, "the exchange was not meant to fail, got " + cause);
            assertNotNull(response, "the exchange neither failed nor produced a response");
            assertEquals(200, response.getStatusCode(), "expected the final 200");
            assertEquals(path, response.getUri().getPath(), "the exchange ended on the wrong hop");
        }

        void assertReachedTheSecondHop() {
            assertCompletedAt(SECOND_HOP);
        }

        void assertTimedOut() {
            assertNotNull(cause, "the exchange should have run out of budget");
            assertEquals(TimeoutException.class, cause.getClass(), "expected a request timeout, got " + cause);
        }

        /**
         * That the exchange was failed by the check before the request was written, rather than by a timeout
         * armed at zero expiring once it had been. The message is the only thing that tells the two apart.
         */
        void assertTimedOutBeforeSending() {
            assertTimedOut();
            assertTrue(cause.getMessage().contains("before the request was sent"),
                    "expected the deadline to be caught before the write, got " + cause.getMessage());
        }
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

    private Outcome runAndAwait(DefaultAsyncHttpClientConfig.Builder builder,
                                @Nullable Boolean perRequestOverride) throws Throwable {
        AtomicReference<Throwable> cause = new AtomicReference<>();
        AtomicReference<Response> completed = new AtomicReference<>();
        CountDownLatch settled = new CountDownLatch(1);

        withClient(builder).run(client -> withServer(server).run(server -> {
            BoundRequestBuilder request = client.prepareGet(server.getHttpUrl() + FIRST_HOP);
            if (perRequestOverride != null) {
                request.setUseAbsoluteRequestDeadline(perRequestOverride);
            }
            request.execute(new AsyncCompletionHandler<Void>() {
                @Override
                public Void onCompleted(Response response) {
                    completed.set(response);
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

        return new Outcome(cause.get(), completed.get());
    }
}
