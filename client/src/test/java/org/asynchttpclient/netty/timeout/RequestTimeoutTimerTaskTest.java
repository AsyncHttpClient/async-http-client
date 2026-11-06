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

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpClientState;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.channel.ChannelPoolPartitioning;
import org.asynchttpclient.netty.NettyResponseBodyControl;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One timer entry serving both the request and the read timeout of an exchange. When the read timeout is the
 * shorter of the two, {@link TimeoutsHolder#start} arms the request timeout's entry for when a read timeout
 * would be due, and the read timeout then rides on it instead of taking an entry of its own.
 * <p>
 * The holder's clock and the timer are both fakes: the timer records what is armed and runs nothing, so each
 * test fires an entry by hand at the instant it sets on the clock.
 */
public class RequestTimeoutTimerTaskTest {

    private static final long READ_TIMEOUT_MS = 1_000;
    private static final long REQUEST_TIMEOUT_MS = 5_000;

    private final AtomicLong elapsedMillis = new AtomicLong();
    private final List<Armed> armed = new ArrayList<>();
    private long origin;

    private HashedWheelTimer senderTimer;
    private ChannelManager channelManager;
    private Timer timer;
    private NettyResponseFuture<?> future;

    @BeforeEach
    void setUp() {
        timer = mock(Timer.class);
        when(timer.newTimeout(any(), anyLong(), any())).thenAnswer(invocation -> {
            Timeout timeout = mock(Timeout.class);
            armed.add(new Armed(invocation.getArgument(0), invocation.getArgument(1), timeout));
            return timeout;
        });
    }

    @AfterEach
    void tearDown() {
        if (channelManager != null) {
            channelManager.close();
        }
        if (senderTimer != null) {
            senderTimer.stop();
        }
    }

    @Test
    public void oneEntryServesBothTimeouts() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();

        assertEquals(1, armed.size(), "the read timeout should not have taken an entry of its own");
        assertInstanceOf(RequestTimeoutTimerTask.class, armed.get(0).task);
        assertEquals(READ_TIMEOUT_MS, armed.get(0).delay);
    }

    @Test
    public void anEarlyEntryWaitsOnlyForWhatIsLeftOfTheReadWindow() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();

        fire(0, 600);

        assertFalse(future.isDone());
        assertEquals(2, armed.size());
        assertSame(armed.get(0).task, armed.get(1).task);
        assertEquals(400, armed.get(1).delay, "the entry should be due when the read window runs out");
    }

    @Test
    public void aReadTimeoutIsDeliveredFromTheSharedEntry() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();

        fire(0, READ_TIMEOUT_MS);

        assertTimedOut("Read timeout");
        assertEquals(1, armed.size(), "an expired exchange should arm nothing more");
    }

    @Test
    public void aReadTimeoutThatHasNotStartedGetsItsOwnEntry() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);

        // Still connecting or writing when the entry fires: nothing to check yet, and checking again every
        // window until the read timeout starts would wake the timer for nothing.
        fire(0, READ_TIMEOUT_MS);
        assertEquals(2, armed.size());
        assertEquals(REQUEST_TIMEOUT_MS - READ_TIMEOUT_MS, armed.get(1).delay,
                "the entry should go back to the request deadline");

        holder.startReadTimeout();
        assertEquals(3, armed.size());
        assertInstanceOf(ReadTimeoutTimerTask.class, armed.get(2).task);
        assertEquals(READ_TIMEOUT_MS, armed.get(2).delay);
    }

    @Test
    public void aRequestDeadlineAheadOfTheReadTimeoutTakesTheEntryAlone() {
        TimeoutsHolder holder = startedHolder(READ_TIMEOUT_MS / 2);
        holder.startReadTimeout();

        assertEquals(1, armed.size());
        assertEquals(READ_TIMEOUT_MS / 2, armed.get(0).delay);

        fire(0, READ_TIMEOUT_MS / 2);
        assertTimedOut("Request timeout");
    }

    @Test
    public void anEarlyEntryThatFiresPastTheRequestDeadlineDeliversTheRequestTimeout() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();

        // A timer runs an entry on the first tick at or after it is due, which can be past a deadline that
        // fell between the two.
        fire(0, REQUEST_TIMEOUT_MS);

        assertTimedOut("Request timeout");
    }

    @Test
    public void aSuspendedBodyHoldsOffTheReadTimeoutForAWholeWindow() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            NettyResponseBodyControl.create(future, channel, () -> { }, ignored -> { }).suspend();

            fire(0, 3 * READ_TIMEOUT_MS);

            assertFalse(future.isDone(), "a suspended body is not a read timeout");
            assertEquals(2, armed.size());
            assertEquals(READ_TIMEOUT_MS, armed.get(1).delay);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void cancellingAfterAnEarlyFireTakesTheNewEntryBackOut() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();
        fire(0, READ_TIMEOUT_MS / 2);

        holder.cancel();

        verify(armed.get(1).timeout).cancel();
        RequestTimeoutTimerTask task = (RequestTimeoutTimerTask) armed.get(1).task;
        assertTrue(task.isClaimed());
        assertNull(task.nettyResponseFuture, "a cancelled entry should not pin the exchange");
    }

    @Test
    public void aCompletedExchangeArmsNothingMore() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();
        future.done();

        fire(0, READ_TIMEOUT_MS);

        assertEquals(1, armed.size(), "a finished exchange should arm nothing more");
        assertTrue(((RequestTimeoutTimerTask) armed.get(0).task).isClaimed());
    }

    private TimeoutsHolder startedHolder(long requestTimeoutMs) {
        AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .setRequestTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
        senderTimer = new HashedWheelTimer();
        channelManager = new ChannelManager(config, senderTimer);
        NettyRequestSender requestSender = new NettyRequestSender(config, channelManager, senderTimer,
                mock(AsyncHttpClientState.class));

        Request request = new RequestBuilder().setUrl("http://example.com:12345").build();
        future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        }, null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
        origin = future.getLastTouch();

        TimeoutsHolder holder = new TimeoutsHolder(timer, null, future, requestSender, config, null,
                this::millis, System::nanoTime);
        holder.start();
        return holder;
    }

    private void fire(int entry, long atElapsedMillis) {
        elapsedMillis.set(atElapsedMillis);
        Armed armedEntry = armed.get(entry);
        try {
            armedEntry.task.run(armedEntry.timeout);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void assertTimedOut(String prefix) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(0, TimeUnit.MILLISECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().startsWith(prefix), failure.getCause().getMessage());
    }

    private long millis() {
        return origin + elapsedMillis.get();
    }

    private static final class Armed {
        final TimerTask task;
        final long delay;
        final Timeout timeout;

        Armed(TimerTask task, long delay, Timeout timeout) {
            this.task = task;
            this.delay = delay;
            this.timeout = timeout;
        }
    }
}
