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

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.asynchttpclient.netty.timeout.RequestTimeoutTimerTask;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 * The tests read the budget each attempt is armed with, through {@link BudgetRecordingTimer}, instead of racing
 * a server delay against the budget: connect and JVM warm-up also come out of the budget, so that race was lost
 * on a cold JVM.
 */
public class AbsoluteRequestDeadlineTest extends HttpTest {

    private static final Duration BUDGET = Duration.ofMillis(600);
    // Too large to run out, so the second hop is always sent. Nothing is timed against it.
    private static final Duration UNSPENDABLE_BUDGET = Duration.ofSeconds(20);
    private static final long HOP_DELAY_MS = 400;
    private static final String FIRST_HOP = "/foo/bar";
    private static final String SECOND_HOP = "/foo/bar2";

    private HttpServer server;
    // Coarse on purpose, for the cases that need the request timeout not to fire: a wheel answers a
    // deadline on its first tick at or after it, so at this granularity nothing expires inside a test.
    private HashedWheelTimer stalledTimer;
    private BudgetRecordingTimer budgetTimer;
    // Read just before execute(), so never later than the future's own start.
    private long executeNanos;
    // The server's own measurement of its last delayed hop. Written on a Jetty thread, read here.
    private final AtomicLong hopEnteredNanos = new AtomicLong();
    private final AtomicLong hopAnsweredNanos = new AtomicLong();

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

    @Test
    public void byDefaultEachHopGetsItsOwnBudget() throws Throwable {
        // Two 400 ms hops do not fit in 600 ms, so the second one must have got a budget of its own.
        enqueueTwoDelayedHops();

        Outcome outcome = runAndAwait(recordingConfig(stalledTimer), null);

        outcome.assertReachedTheSecondHop();
        assertSecondHopGotAFreshBudget();
    }

    @Test
    public void anAbsoluteDeadlineLeavesTheSecondHopOnlyWhatIsLeft() throws Throwable {
        // The upper bounds below are the only checks that fail if the full timeout is armed instead of the
        // remainder. Not BUDGET: at 600 ms the second hop is only sent when the first round trip is fast.
        enqueueDelayed(HOP_DELAY_MS, 302, SECOND_HOP);
        server.enqueueOk();

        Outcome outcome = runAndAwait(recordingConfig(stalledTimer)
                .setRequestTimeout(UNSPENDABLE_BUDGET)
                .setUseAbsoluteRequestDeadline(true), null);

        outcome.assertReachedTheSecondHop();
        assertEquals(2, budgetTimer.armedAttempts(), "expected one armed attempt per hop");
        long budget = UNSPENDABLE_BUDGET.toMillis();
        long first = budgetTimer.budgetOfAttempt(0);
        long armed = budgetTimer.budgetOfAttempt(1);
        assertTrue(armed < first, "the second hop was armed with " + armed + " ms, no less than the "
                + first + " ms the first hop got, so nothing was netted off");
        // The server's measured delay, not HOP_DELAY_MS: Thread.sleep accuracy must not decide this.
        long served = TimeUnit.NANOSECONDS.toMillis(hopAnsweredNanos.get() - hopEnteredNanos.get());
        assertTrue(armed <= budget - served, "the second hop was armed with " + armed + " ms, more than the "
                + (budget - served) + " ms left after a first hop the server took " + served + " ms over");
        long spendable = TimeUnit.NANOSECONDS.toMillis(budgetTimer.armedAtNanos(1) - executeNanos);
        assertTrue(armed >= budget - spendable, "the second hop was armed with " + armed + " ms of "
                + budget + " ms, the exchange having spent at most " + spendable + " ms");
    }

    @Test
    public void aRequestCanAskForAnAbsoluteDeadlineOnAPerAttemptClient() throws Throwable {
        enqueueTheWholeBudgetThenAPromptHop();

        Outcome outcome = runAndAwait(recordingConfig(stalledTimer), Boolean.TRUE);

        outcome.assertTimedOutBeforeSending();
    }

    @Test
    public void aRequestCanOptOutOfAnAbsoluteDeadlineClient() throws Throwable {
        enqueueTwoDelayedHops();

        Outcome outcome = runAndAwait(recordingConfig(stalledTimer).setUseAbsoluteRequestDeadline(true),
                Boolean.FALSE);

        outcome.assertReachedTheSecondHop();
        assertSecondHopGotAFreshBudget();
    }

    @Test
    public void aSingleHopStillGetsTheWholeBudget() throws Throwable {
        // Guards the other direction: with a deadline, the first hop must not be handed a shortened budget.
        server.enqueueOk();

        Outcome outcome = runAndAwait(recordingConfig(stalledTimer).setUseAbsoluteRequestDeadline(true), null);

        outcome.assertCompletedAt(FIRST_HOP);
        long armed = budgetTimer.budgetOfAttempt(0);
        long spendable = TimeUnit.NANOSECONDS.toMillis(budgetTimer.armedAtNanos(0) - executeNanos);
        assertTrue(armed >= BUDGET.toMillis() - spendable, "the first hop was armed with " + armed
                + " ms of a " + BUDGET.toMillis() + " ms budget, having spent at most " + spendable + " ms");
        assertTrue(armed <= BUDGET.toMillis(),
                "the first hop was armed with " + armed + " ms, more than the configured budget");
    }

    @Test
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

        Outcome outcome = runAndAwait(recordingConfig(stalledTimer).setUseAbsoluteRequestDeadline(true), null);

        outcome.assertTimedOutBeforeSending();
        assertFalse(secondHopServed.get(), "the redirect target was sent a request with no budget left");
        assertEquals(1, budgetTimer.armedAttempts(),
                "a hop with nothing left to spend was armed a budget of its own");
    }

    private DefaultAsyncHttpClientConfig.Builder baseConfig() {
        return config().setRequestTimeout(BUDGET).setFollowRedirect(true).setMaxRedirects(5);
    }

    private void enqueueTwoDelayedHops() {
        enqueueDelayed(HOP_DELAY_MS, 302, SECOND_HOP);
        enqueueDelayed(HOP_DELAY_MS, 200, null);
    }

    /**
     * The first hop outlasts the whole budget, so the redirect is refused however fast the box is.
     */
    private void enqueueTheWholeBudgetThenAPromptHop() {
        enqueueDelayed(BUDGET.toMillis() + HOP_DELAY_MS, 302, SECOND_HOP);
        server.enqueueOk();
    }

    /**
     * Event-loop timeouts are pinned off because the recorder only sees timeouts armed through the {@link Timer}.
     */
    private DefaultAsyncHttpClientConfig.Builder recordingConfig(Timer wheel) {
        budgetTimer = new BudgetRecordingTimer(wheel);
        return baseConfig().setNettyTimer(budgetTimer).setUseEventLoopTimeouts(false);
    }

    private void assertSecondHopGotAFreshBudget() {
        assertEquals(BUDGET.toMillis(), budgetTimer.budgetOfAttempt(1),
                "the second hop was not armed the whole budget over again");
    }

    /**
     * Records the delay each request timeout is armed with. Delegates to the stalled wheel, so nothing fires.
     */
    private static final class BudgetRecordingTimer implements Timer {

        private final Timer delegate;
        private final List<Arming> armings = new CopyOnWriteArrayList<>();

        private BudgetRecordingTimer(Timer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
            // The cookie evictor and the pool cleaner use this timer too.
            if (task instanceof RequestTimeoutTimerTask) {
                armings.add(new Arming(unit.toMillis(delay), System.nanoTime()));
            }
            return delegate.newTimeout(task, delay, unit);
        }

        @Override
        public Set<Timeout> stop() {
            return delegate.stop();
        }

        long budgetOfAttempt(int index) {
            return arming(index).budgetMillis;
        }

        long armedAtNanos(int index) {
            return arming(index).nanos;
        }

        int armedAttempts() {
            return armings.size();
        }

        private Arming arming(int index) {
            assertTrue(armings.size() > index, "attempt " + (index + 1)
                    + " armed no request timeout, only " + armings.size() + " did");
            return armings.get(index);
        }

        private static final class Arming {

            private final long budgetMillis;
            private final long nanos;

            private Arming(long budgetMillis, long nanos) {
                this.budgetMillis = budgetMillis;
                this.nanos = nanos;
            }
        }
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
            hopEnteredNanos.set(System.nanoTime());
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            // Stamped before the status is set, while the client is still waiting on this hop.
            hopAnsweredNanos.set(System.nanoTime());
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
            executeNanos = System.nanoTime();
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
