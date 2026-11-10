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
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One timer entry serving both the request and the read timeout of an exchange. When the read timeout is the
 * shorter of the two, {@link TimeoutsHolder#start} arms the request timeout's entry for when a read timeout
 * would be due, and the read timeout then rides on it instead of taking an entry of its own.
 * <p>
 * The holder's clocks, the timer and the event loop are all fakes: the schedulers record what is armed and run
 * nothing, so each test fires an entry by hand at the instant it sets on the clocks. The wall clock can be
 * stepped apart from the monotonic one, as a clock correction does.
 */
public class RequestTimeoutTimerTaskTest {

    private static final long READ_TIMEOUT_MS = 1_000;
    private static final long REQUEST_TIMEOUT_MS = 5_000;
    private static final long ONE_HOUR_MS = TimeUnit.HOURS.toMillis(1);

    private final List<Armed> armed = new ArrayList<>();
    private long elapsedMillis;
    private long wallClockStepMillis;
    private long wallOrigin;
    private long nanoOrigin;
    // When set, the first entry armed runs before the scheduler hands its handle back, as it can when the
    // arming thread is descheduled for longer than the entry's delay.
    private boolean firstEntryRunsBeforeItIsRecorded;
    // When set, the monotonic clock jumps ahead by this much right after it is next read.
    private long pauseAfterNextNanoReadMillis;
    private int nanoReads;
    // Runs as each entry is armed, before the scheduler hands its handle back.
    private Consumer<Armed> onArm = entry -> { };

    private HashedWheelTimer senderTimer;
    private ChannelManager channelManager;
    private Timer timer;
    private EventExecutor loop;
    private NettyResponseFuture<?> future;
    private NettyRequestSender requestSender;
    private AsyncHttpClientConfig config;

    @BeforeEach
    void setUp() {
        timer = mock(Timer.class);
        when(timer.newTimeout(any(), anyLong(), any())).thenAnswer(invocation -> {
            Timeout timeout = mock(Timeout.class);
            Armed entry = arm(invocation.getArgument(0), invocation.getArgument(1), timeout);
            when(timeout.cancel()).thenAnswer(ignored -> !entry.ran);
            return handBack(entry, timeout);
        });
        loop = mock(EventExecutor.class);
        when(loop.schedule(any(Runnable.class), anyLong(), any())).thenAnswer(invocation -> {
            ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
            Armed entry = arm(invocation.getArgument(0), invocation.getArgument(1), scheduled);
            when(scheduled.cancel(anyBoolean())).thenAnswer(ignored -> !entry.ran);
            return handBack(entry, scheduled);
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
    public void anAttemptWithNothingToReArmDoesNotReadTheMonotonicClock() {
        // The default: a read timeout no shorter than the request timeout, on the timer.
        TimeoutsHolder holder = startedHolder(READ_TIMEOUT_MS);
        holder.startReadTimeout();
        holder.cancel();

        assertEquals(0, nanoReads, "only an entry that may be re-armed needs the request deadline on the nano clock");
    }

    @Test
    public void anEarlyFireReadsTheMonotonicClockOnce() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();
        nanoReads = 0;

        fire(0, READ_TIMEOUT_MS / 2);

        assertEquals(2, armed.size());
        assertEquals(1, nanoReads, "one reading should both check the request deadline and time the next entry");
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
    public void anAbsoluteDeadlineWithLessLeftThanTheReadTimeoutTakesTheEntryAlone() {
        // An earlier hop already spent most of the exchange's budget.
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, true, false, null, REQUEST_TIMEOUT_MS - READ_TIMEOUT_MS / 2);
        holder.start();
        holder.startReadTimeout();

        assertEquals(1, armed.size(), "the read timeout cannot be due before what is left of the request timeout");
        assertEquals(READ_TIMEOUT_MS / 2, armed.get(0).delay);

        fire(0, REQUEST_TIMEOUT_MS);
        assertTimedOut("Request timeout");
    }

    @Test
    public void anAbsoluteDeadlineIsNotExtendedByTimePassingWhileTheHolderIsBuilt() {
        pauseAfterNextNanoReadMillis = 3_000;
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, true, false, null, 0);
        holder.start();
        assertEquals(1, armed.size());

        // The read timeout has not started, so the entry goes back to the request deadline.
        fire(0, 4_000);

        assertEquals(2, armed.size());
        assertEquals(REQUEST_TIMEOUT_MS - 4_000, armed.get(1).delay,
                "the exchange's deadline should stay where its start and budget put it");
    }

    @Test
    public void anEntryTheTimerRejectsFailsTheExchangeRatherThanLeaveItWithoutADeadline() {
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, false, false, null, 0);
        future.setTimeoutsHolder(holder);
        holder.startReadTimeout();
        onArm = entry -> {
            if (armed.size() == 2) {
                throw new RejectedExecutionException("timer full");
            }
        };

        fire(0, READ_TIMEOUT_MS / 2);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(0, TimeUnit.MILLISECONDS));
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    }

    @Test
    public void aRejectedReArmOfARetiredHolderLeavesTheNextAttemptAlone() {
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, false, false, null, 0);
        future.setTimeoutsHolder(holder);
        holder.startReadTimeout();
        // A redirect installs the next attempt's holder while the old entry is re-arming, and then the timer
        // rejects the old entry.
        onArm = entry -> {
            if (armed.size() == 2) {
                future.setTimeoutsHolder(new TimeoutsHolder(timer, null, future, requestSender, config, null,
                        this::millis, this::nanos));
                throw new RejectedExecutionException("timer full");
            }
        };

        fire(0, READ_TIMEOUT_MS / 2);

        assertFalse(future.isDone(), "the next attempt should not be failed by the holder it replaced");
    }

    @Test
    public void aRejectedReArmOfAHolderTheFutureNoLongerHoldsAbortsNothing() {
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, false, false, null, 0);
        future.setTimeoutsHolder(holder);
        holder.startReadTimeout();
        // Completion and the next attempt both take the holder off the future before they cancel it; the
        // rejection lands in between.
        onArm = entry -> {
            if (armed.size() == 2) {
                detachTimeoutsHolder();
                throw new RejectedExecutionException("timer full");
            }
        };

        fire(0, READ_TIMEOUT_MS / 2);

        assertFalse(future.isDone(), "a holder the future no longer holds should leave the exchange alone");
    }

    @Test
    public void aRejectedReArmAfterTheExchangeCompletedAbortsNothing() {
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, false, false, null, 0);
        future.setTimeoutsHolder(holder);
        holder.startReadTimeout();
        onArm = entry -> {
            if (armed.size() == 2) {
                future.cancelTimeouts();
                throw new RejectedExecutionException("timer full");
            }
        };

        fire(0, READ_TIMEOUT_MS / 2);

        assertFalse(future.isDone(), "a holder retired by completion should leave the exchange alone");
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
    public void aWallClockStepForwardDoesNotBringTheRequestDeadlineForward() {
        startedHolder(REQUEST_TIMEOUT_MS);

        wallClockStepMillis = ONE_HOUR_MS;
        fire(0, READ_TIMEOUT_MS);

        assertFalse(future.isDone(), "a step of the wall clock is not time spent on the request");
        assertEquals(2, armed.size());
        assertEquals(REQUEST_TIMEOUT_MS - READ_TIMEOUT_MS, armed.get(1).delay);
    }

    @Test
    public void aWallClockStepBackDoesNotPushTheRequestDeadlineBack() {
        startedHolder(REQUEST_TIMEOUT_MS);

        wallClockStepMillis = -ONE_HOUR_MS;
        fire(0, READ_TIMEOUT_MS);

        assertEquals(2, armed.size());
        assertEquals(REQUEST_TIMEOUT_MS - READ_TIMEOUT_MS, armed.get(1).delay,
                "the request deadline should stay where the monotonic clock put it");
    }

    @Test
    public void aRequestTimeoutPastWhatTheMonotonicClockCanHoldIsNotTakenForPassed() {
        TimeoutsHolder holder = holderWithTheLongestRequestTimeout();
        holder.start();
        holder.startReadTimeout();

        fire(0, READ_TIMEOUT_MS / 2);

        assertFalse(future.isDone(), "the request deadline is nowhere near");
        assertEquals(2, armed.size());
        assertTrue(holder.isRequestTimeoutArmedEarly());
    }

    @Test
    public void aRequestTimeoutPastWhatTheClocksCanHoldStillLetsAReadTimeoutStart() {
        TimeoutsHolder holder = holderWithTheLongestRequestTimeout();
        holder.start();
        // Still connecting or writing when the entry fires, so the read timeout takes an entry of its own.
        fire(0, READ_TIMEOUT_MS);

        holder.startReadTimeout();

        assertEquals(3, armed.size(), "the request deadline is nowhere near");
        assertInstanceOf(ReadTimeoutTimerTask.class, armed.get(2).task);
    }

    @Test
    public void aReadTimeoutStartedBeforeTheHolderIsStartedStillGetsAnEntry() {
        // setTimeoutsHolder makes the holder reachable through the future before it starts it.
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, false, false, null, 0);

        holder.startReadTimeout();

        assertEquals(1, armed.size(), "the whole request timeout is still ahead");
        assertInstanceOf(ReadTimeoutTimerTask.class, armed.get(0).task);
    }

    @Test
    public void aWallClockStepForwardDoesNotKeepAReadTimeoutFromStarting() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        fire(0, READ_TIMEOUT_MS);

        wallClockStepMillis = ONE_HOUR_MS;
        holder.startReadTimeout();

        assertEquals(3, armed.size(), "a step of the wall clock is not time spent on the request");
        assertInstanceOf(ReadTimeoutTimerTask.class, armed.get(2).task);
    }

    @Test
    public void aWallClockStepForwardDoesNotKeepAReadTimeoutFromStartingUnderAnAbsoluteDeadline() {
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, true, false, null, 2_000);
        holder.start();
        fire(0, READ_TIMEOUT_MS);

        wallClockStepMillis = ONE_HOUR_MS;
        holder.startReadTimeout();

        assertEquals(3, armed.size(), "a step of the wall clock is not time spent on the exchange");
        assertInstanceOf(ReadTimeoutTimerTask.class, armed.get(2).task);
    }

    @Test
    public void aWallClockStepBackDoesNotStartAReadTimeoutThatTheRequestDeadlineBeats() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        fire(0, READ_TIMEOUT_MS);

        elapsedMillis = REQUEST_TIMEOUT_MS - READ_TIMEOUT_MS / 2;
        wallClockStepMillis = -ONE_HOUR_MS;
        holder.startReadTimeout();

        assertEquals(2, armed.size(), "the request timeout is due first, so the read timeout needs no entry");
    }

    @Test
    public void aWallClockStepBackDoesNotStartAReadTimeoutNoShorterThanTheRequestTimeout() {
        TimeoutsHolder holder = startedHolder(READ_TIMEOUT_MS);

        wallClockStepMillis = -ONE_HOUR_MS;
        holder.startReadTimeout();

        assertEquals(1, armed.size(), "the request timeout is due first, so the read timeout needs no entry");
        assertEquals(0, nanoReads);
    }

    @Test
    public void aWallClockStepBackDoesNotPutTheNextReadCheckOutOfReach() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();

        wallClockStepMillis = -ONE_HOUR_MS;
        fire(0, READ_TIMEOUT_MS / 2);

        assertEquals(2, armed.size());
        assertEquals(READ_TIMEOUT_MS, armed.get(1).delay, "the read timeout should be checked again within a window");
        assertTrue(holder.isRequestTimeoutArmedEarly());
    }

    @Test
    public void aReadTimeoutStartingWhileTheEntryReArmsGetsItsOwnEntry() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        // The request is written while the entry, finding no read timeout yet, is re-arming for the deadline.
        onArm = entry -> {
            if (armed.size() == 2) {
                holder.startReadTimeout();
            }
        };

        fire(0, READ_TIMEOUT_MS);

        assertEquals(3, armed.size());
        assertEquals(REQUEST_TIMEOUT_MS - READ_TIMEOUT_MS, armed.get(1).delay);
        assertInstanceOf(ReadTimeoutTimerTask.class, armed.get(2).task, "the read timeout should not be lost");
        assertEquals(READ_TIMEOUT_MS, armed.get(2).delay);

        holder.cancel();
        verify((Timeout) armed.get(1).timeout).cancel();
        verify((Timeout) armed.get(2).timeout).cancel();
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

        verify((Timeout) armed.get(1).timeout).cancel();
        RequestTimeoutTimerTask task = (RequestTimeoutTimerTask) armed.get(1).task;
        assertTrue(task.isClaimed());
        assertNull(task.nettyResponseFuture, "a cancelled entry should not pin the exchange");
    }

    @Test
    public void anEntryThatFiresBeforeItIsRecordedDoesNotHideItsSuccessorOnTheTimer() {
        firstEntryRunsBeforeItIsRecorded = true;
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        assertEquals(2, armed.size(), "the first entry should have re-armed while it was still being recorded");

        holder.cancel();

        verify((Timeout) armed.get(1).timeout).cancel();
    }

    @Test
    public void anEntryThatFiresBeforeItIsRecordedDoesNotHideItsSuccessorOnTheLoop() {
        firstEntryRunsBeforeItIsRecorded = true;
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, false, true, loop, 0);
        holder.start();
        assertEquals(2, armed.size(), "the first entry should have re-armed while it was still being recorded");

        holder.cancel();

        verify((ScheduledFuture<?>) armed.get(1).timeout).cancel(false);
    }

    @Test
    public void cancellingAfterAnEarlyFireOnTheLoopTakesTheNewEntryBackOut() {
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, false, true, loop, 0);
        holder.start();
        holder.startReadTimeout();

        fire(0, READ_TIMEOUT_MS / 2);
        assertEquals(2, armed.size());

        holder.cancel();

        verify((ScheduledFuture<?>) armed.get(1).timeout).cancel(false);
    }

    @Test
    public void rehomingBeforeTheReadTimeoutStartsKeepsTheEntryEarly() {
        // The connect path arms on the timer, before there is a channel whose loop could carry the entry.
        TimeoutsHolder holder = holder(REQUEST_TIMEOUT_MS, false, true, null, 0);
        holder.start();
        assertEquals(1, armed.size());

        elapsedMillis = 300;
        holder.rehomeOn(loop);

        verify((Timeout) armed.get(0).timeout).cancel();
        assertEquals(2, armed.size());
        assertInstanceOf(ScheduledFuture.class, armed.get(1).timeout, "the entry should have moved to the loop");
        assertEquals(READ_TIMEOUT_MS, armed.get(1).delay, "the entry should still be armed to check a read timeout");
        assertTrue(holder.isRequestTimeoutArmedEarly());
    }

    @Test
    public void aCompletedExchangeArmsNothingMore() {
        TimeoutsHolder holder = startedHolder(REQUEST_TIMEOUT_MS);
        holder.startReadTimeout();
        future.done();

        // Inside the read window, where an exchange still running would be re-armed.
        fire(0, READ_TIMEOUT_MS / 2);

        assertEquals(1, armed.size(), "a finished exchange should arm nothing more");
        assertTrue(((RequestTimeoutTimerTask) armed.get(0).task).isClaimed());
    }

    private TimeoutsHolder startedHolder(long requestTimeoutMs) {
        TimeoutsHolder holder = holder(requestTimeoutMs, false, false, null, 0);
        holder.start();
        return holder;
    }

    /**
     * @param spentMillis how long the exchange ran before this attempt, which only an absolute deadline counts
     */
    private TimeoutsHolder holder(long requestTimeoutMs, boolean absoluteDeadline, boolean useEventLoopTimeouts,
                                  @Nullable EventExecutor executor, long spentMillis) {
        config = config(requestTimeoutMs, useEventLoopTimeouts);
        senderTimer = new HashedWheelTimer();
        channelManager = new ChannelManager(config, senderTimer);
        requestSender = new NettyRequestSender(config, channelManager, senderTimer, mock(AsyncHttpClientState.class));

        Request request = new RequestBuilder().setUrl("http://example.com:12345").build();
        future = new NettyResponseFuture<>(request, new AsyncCompletionHandler<Object>() {
            @Override
            public Object onCompleted(Response response) {
                return null;
            }
        }, null, 0, ChannelPoolPartitioning.PerHostChannelPoolPartitioning.INSTANCE, null, null);
        future.setUseAbsoluteRequestDeadline(absoluteDeadline);
        wallOrigin = future.getLastTouch();
        nanoOrigin = future.getStartNanos() + TimeUnit.MILLISECONDS.toNanos(spentMillis);
        return new TimeoutsHolder(timer, executor, future, requestSender, config, null, this::millis, this::nanos);
    }

    // Any positive reading plus the longest timeout overflows, which only a comparison by subtraction survives.
    private TimeoutsHolder holderWithTheLongestRequestTimeout() {
        holder(Long.MAX_VALUE, false, false, null, 0);
        nanoOrigin = ONE_HOUR_MS;
        return new TimeoutsHolder(timer, null, future, requestSender, config, null, this::millis, this::nanos);
    }

    // Takes the holder off the future without cancelling it, as NettyResponseFuture does before it cancels.
    private void detachTimeoutsHolder() {
        try {
            Field field = NettyResponseFuture.class.getDeclaredField("timeoutsHolder");
            field.setAccessible(true);
            field.set(future, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static AsyncHttpClientConfig config(long requestTimeoutMs, boolean useEventLoopTimeouts) {
        return new DefaultAsyncHttpClientConfig.Builder()
                .setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .setRequestTimeout(Duration.ofMillis(requestTimeoutMs))
                .setUseEventLoopTimeouts(useEventLoopTimeouts)
                .build();
    }

    private Armed arm(Object task, long delay, Object handle) {
        Armed entry = new Armed((TimeoutTimerTask) task, delay, handle);
        armed.add(entry);
        return entry;
    }

    private <H> H handBack(Armed entry, H handle) {
        onArm.accept(entry);
        if (firstEntryRunsBeforeItIsRecorded && armed.size() == 1) {
            run(entry, READ_TIMEOUT_MS);
        }
        return handle;
    }

    private void fire(int entry, long atElapsedMillis) {
        run(armed.get(entry), atElapsedMillis);
    }

    private void run(Armed entry, long atElapsedMillis) {
        elapsedMillis = atElapsedMillis;
        entry.ran = true;
        try {
            entry.task.run(entry.timeout instanceof Timeout ? (Timeout) entry.timeout : null);
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
        return wallOrigin + elapsedMillis + wallClockStepMillis;
    }

    private long nanos() {
        nanoReads++;
        long nanos = nanoOrigin + TimeUnit.MILLISECONDS.toNanos(elapsedMillis);
        elapsedMillis += pauseAfterNextNanoReadMillis;
        pauseAfterNextNanoReadMillis = 0;
        return nanos;
    }

    private static final class Armed {
        final TimeoutTimerTask task;
        final long delay;
        final Object timeout;
        volatile boolean ran;

        Armed(TimeoutTimerTask task, long delay, Object timeout) {
            this.task = task;
            this.delay = delay;
            this.timeout = timeout;
        }
    }
}
