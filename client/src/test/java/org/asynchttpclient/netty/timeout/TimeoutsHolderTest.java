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

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseFuture;

import java.time.Duration;

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
    // The deadline is a wall-clock reading and the budget is netted off in whole milliseconds, so an anchored
    // deadline lands within a few milliseconds of itself rather than exactly on it.
    private static final long TOLERANCE_MS = 30;

    @RepeatedIfExceptionsTest(repeats = 5)
    public void anAbsoluteDeadlineStaysWhereTheExchangeStarted() throws Exception {
        NettyResponseFuture<?> future = exchange(true);

        long firstHop = deadlineOf(future, BUDGET);
        Thread.sleep(ELAPSED_MS);
        long secondHop = deadlineOf(future, BUDGET);

        assertTrue(Math.abs(secondHop - firstHop) <= TOLERANCE_MS,
                "the second hop moved the deadline by " + (secondHop - firstHop) + " ms");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aPerAttemptTimeoutGivesTheSecondHopItsOwnBudget() throws Exception {
        NettyResponseFuture<?> future = exchange(false);

        long firstHop = deadlineOf(future, BUDGET);
        Thread.sleep(ELAPSED_MS);
        long secondHop = deadlineOf(future, BUDGET);

        assertTrue(secondHop - firstHop >= ELAPSED_MS / 2,
                "the second hop should have started a budget of its own, moved by only "
                        + (secondHop - firstHop) + " ms");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void anExchangeThatOutranItsDeadlineHasNothingLeft() throws Exception {
        // A budget this small is spent by the time the sleep is over, so the next hop has nothing to run in.
        NettyResponseFuture<?> future = exchange(true);
        Thread.sleep(ELAPSED_MS);

        assertTrue(TimeoutsHolder.remainingBudget(config(Duration.ofMillis(1)), future) <= 0,
                "a spent deadline should leave nothing to send a further hop with");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void aPerAttemptExchangeIsNotBoundedAsAWhole() throws Exception {
        // Asserted on the deadline the holder computes rather than on the budget: per attempt there is no
        // exchange-wide budget to run out of, so the arithmetic is not what the answer rests on.
        NettyResponseFuture<?> future = exchange(false);
        Thread.sleep(ELAPSED_MS);

        long deadline = deadlineOf(future, BUDGET);

        assertTrue(deadline - System.currentTimeMillis() >= BUDGET.toMillis() - TOLERANCE_MS,
                "a hop should be given the configured timeout of its own however long the exchange has run, got "
                        + (deadline - System.currentTimeMillis()) + " ms");
    }

    private static long deadlineOf(NettyResponseFuture<?> future, Duration requestTimeout) {
        return holder(future, requestTimeout).requestTimeoutMillisTime();
    }

    private static TimeoutsHolder holder(NettyResponseFuture<?> future, Duration requestTimeout) {
        return new TimeoutsHolder(null, future, null, config(requestTimeout), null);
    }

    private static AsyncHttpClientConfig config(Duration requestTimeout) {
        return new DefaultAsyncHttpClientConfig.Builder().setRequestTimeout(requestTimeout).build();
    }

    private static NettyResponseFuture<?> exchange(boolean useAbsoluteRequestDeadline) {
        Request request = new RequestBuilder().setUrl("http://example.com:12345").build();
        NettyResponseFuture<?> future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        }, null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
        future.setUseAbsoluteRequestDeadline(useAbsoluteRequestDeadline);
        return future;
    }
}
