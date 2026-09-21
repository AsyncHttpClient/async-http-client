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
package org.asynchttpclient.netty.timeout;

import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deadline a holder computes, which is where
 * {@link AsyncHttpClientConfig#isUseAbsoluteRequestDeadline()} takes effect. A redirect, a retry and an auth
 * replay each build a new holder for the same future, so what a second holder makes of the same exchange is
 * the whole of the difference between the two modes.
 * <p>
 * No timer and no request sender: without them the holder computes its deadline and arms nothing, which is
 * exactly the part worth testing directly rather than through a request.
 */
public class TimeoutsHolderTest {

    private static final Duration BUDGET = Duration.ofMillis(600);
    private static final long ELAPSED_MS = 100;

    // Both fake clocks move only when a test moves them. The nano one runs from the exchange's own start,
    // which the future reads off System.nanoTime().
    private long nanoOrigin;
    private final AtomicLong elapsedMillis = new AtomicLong();

    @Test
    public void anAbsoluteDeadlineStaysWhereTheExchangeStarted() {
        NettyResponseFuture<?> future = exchange(true);

        long firstHop = deadlineOf(future, BUDGET);
        elapsedMillis.addAndGet(ELAPSED_MS);
        long secondHop = deadlineOf(future, BUDGET);

        assertEquals(firstHop, secondHop, "the second hop moved the deadline");
    }

    @Test
    public void aPerAttemptTimeoutGivesTheSecondHopItsOwnBudget() {
        NettyResponseFuture<?> future = exchange(false);

        long firstHop = deadlineOf(future, BUDGET);
        elapsedMillis.addAndGet(ELAPSED_MS);
        long secondHop = deadlineOf(future, BUDGET);

        assertEquals(firstHop + ELAPSED_MS, secondHop, "the second hop should have started a budget of its own");
    }

    @Test
    public void anExchangeThatOutranItsDeadlineHasNothingLeft() {
        NettyResponseFuture<?> future = exchange(true);
        elapsedMillis.addAndGet(ELAPSED_MS);

        assertTrue(TimeoutsHolder.remainingBudget(config(Duration.ofMillis(1)), future, this::nanos) <= 0,
                "a spent deadline should leave nothing to send a further hop with");
    }

    @Test
    public void aPerAttemptExchangeIsNotBoundedAsAWhole() {
        // Asserted on the deadline the holder computes rather than on the budget: per attempt there is no
        // exchange-wide budget to run out of, so the arithmetic is not what the answer rests on.
        NettyResponseFuture<?> future = exchange(false);
        elapsedMillis.addAndGet(ELAPSED_MS);

        assertEquals(millis() + BUDGET.toMillis(), deadlineOf(future, BUDGET),
                "a hop should be given the configured timeout of its own however long the exchange has run");
    }

    private long millis() {
        return elapsedMillis.get();
    }

    private long nanos() {
        return nanoOrigin + TimeUnit.MILLISECONDS.toNanos(elapsedMillis.get());
    }

    private long deadlineOf(NettyResponseFuture<?> future, Duration requestTimeout) {
        return new TimeoutsHolder(null, null, future, null, config(requestTimeout), null, this::millis, this::nanos)
                .requestTimeoutMillisTime();
    }

    private static AsyncHttpClientConfig config(Duration requestTimeout) {
        return new DefaultAsyncHttpClientConfig.Builder().setRequestTimeout(requestTimeout).build();
    }

    private NettyResponseFuture<?> exchange(boolean useAbsoluteRequestDeadline) {
        Request request = new RequestBuilder().setUrl("http://example.com:12345").build();
        NettyResponseFuture<?> future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        }, null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
        future.setUseAbsoluteRequestDeadline(useAbsoluteRequestDeadline);
        nanoOrigin = future.getStartNanos();
        return future;
    }
}
