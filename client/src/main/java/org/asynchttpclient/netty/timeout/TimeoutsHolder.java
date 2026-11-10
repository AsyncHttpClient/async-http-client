/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
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

import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.util.DateUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.LongSupplier;

/**
 * The request and read timeouts of one exchange, armed either on the client's {@link Timer} or on the event
 * loop of the channel the exchange runs on. What the two differ in, and why the choice is the caller's, is
 * {@link AsyncHttpClientConfig#isUseEventLoopTimeouts()}.
 */
public class TimeoutsHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutsHolder.class);

    private static final AtomicIntegerFieldUpdater<TimeoutsHolder> CANCELLED =
            AtomicIntegerFieldUpdater.newUpdater(TimeoutsHolder.class, "cancelled");
    private static final AtomicIntegerFieldUpdater<TimeoutsHolder> STARTED =
            AtomicIntegerFieldUpdater.newUpdater(TimeoutsHolder.class, "started");
    private static final AtomicIntegerFieldUpdater<TimeoutsHolder> READ_STATE =
            AtomicIntegerFieldUpdater.newUpdater(TimeoutsHolder.class, "readState");

    // How the read timeout is armed. Separate: on an entry of its own. Pending: start armed the request
    // timeout's entry for when a read timeout would be due, so that once the read timeout starts that one
    // entry serves both. Covered: the read timeout has started and rides on the request timeout's entry.
    private static final int READ_SEPARATE = 0;
    private static final int READ_PENDING = 1;
    private static final int READ_COVERED = 2;

    private volatile int cancelled;
    private volatile int started;
    private volatile int readState;
    // Whether the request timeout's entry is armed ahead of the request deadline, to check the read timeout.
    private volatile boolean requestTimeoutArmedEarly;
    private final Timer nettyTimer;
    private volatile @Nullable EventExecutor eventExecutor;
    private final NettyRequestSender requestSender;
    // The request deadline on nanoClock, for everything that re-arms or checks it after start: a timer entry
    // is already timed on a monotonic clock, and a deadline re-derived from the wall clock would move with
    // every step of it. Set only where something will read it -- an entry armed early, one rehomeOn may move,
    // or a read timeout shorter than the request timeout asking whether it needs an entry of its own -- since
    // reading the clock is a measurable part of what an attempt that needs none of them costs.
    private volatile long requestDeadlineNanos;
    private final long requestTimeoutValue;
    private final boolean absoluteDeadline;
    private final long readTimeoutValue;
    private final boolean useEventLoopTimeouts;
    private final @Nullable RequestTimeoutTimerTask requestTimeoutTask;
    private volatile @Nullable ReadTimeoutTimerTask readTimeoutTask;
    private final NettyResponseFuture<?> nettyResponseFuture;
    private volatile InetSocketAddress remoteAddress;
    private final LongSupplier millisClock;
    private final LongSupplier nanoClock;

    public TimeoutsHolder(Timer nettyTimer, NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender,
                          AsyncHttpClientConfig config, InetSocketAddress originalRemoteAddress) {
        this(nettyTimer, null, nettyResponseFuture, requestSender, config, originalRemoteAddress);
    }

    /**
     * @param eventExecutor the loop of the channel this exchange will run on, or {@code null} to arm the
     *                      timeouts on {@code nettyTimer} instead. Only ever a channel's own loop, so that an
     *                      expiry runs on the thread that would have to close the socket and cancelling one on
     *                      completion touches no other loop's queue. Null until a channel exists;
     *                      {@link #rehomeOn} moves the timeouts once one does.
     */
    public TimeoutsHolder(Timer nettyTimer, @Nullable EventExecutor eventExecutor, NettyResponseFuture<?> nettyResponseFuture,
                          NettyRequestSender requestSender, AsyncHttpClientConfig config, InetSocketAddress originalRemoteAddress) {
        this(nettyTimer, eventExecutor, nettyResponseFuture, requestSender, config, originalRemoteAddress,
                DateUtils::unpreciseMillisTime, System::nanoTime);
    }

    // For tests: a deadline can be checked without letting time pass. nanoClock must read the clock the
    // future took its start from, and millisClock the one touch() stamps lastTouch with, since the read
    // timeout measures its window between the two.
    TimeoutsHolder(Timer nettyTimer, @Nullable EventExecutor eventExecutor, NettyResponseFuture<?> nettyResponseFuture,
                   NettyRequestSender requestSender, AsyncHttpClientConfig config, InetSocketAddress originalRemoteAddress,
                   LongSupplier millisClock, LongSupplier nanoClock) {
        this.millisClock = millisClock;
        this.nanoClock = nanoClock;
        this.nettyTimer = nettyTimer;
        this.eventExecutor = eventExecutor;
        this.nettyResponseFuture = nettyResponseFuture;
        this.requestSender = requestSender;
        useEventLoopTimeouts = config.isUseEventLoopTimeouts();
        remoteAddress = originalRemoteAddress;

        final Request targetRequest = nettyResponseFuture.getTargetRequest();

        final long readTimeoutInMs = targetRequest.getReadTimeout().toMillis();
        readTimeoutValue = readTimeoutInMs == 0 ? config.getReadTimeout().toMillis() : readTimeoutInMs;

        long requestTimeoutInMs = requestTimeout(config, targetRequest);

        requestTimeoutValue = requestTimeoutInMs;
        absoluteDeadline = nettyResponseFuture.isUseAbsoluteRequestDeadline();
        if (requestTimeoutInMs > -1) {
            if (absoluteDeadline) {
                // A redirect, a retry or an auth replay builds a new holder for the same future. Giving each of
                // those hops the configured timeout lets a chain of n hops run for n times it; anchoring the
                // deadline at the exchange's start bounds it as a whole instead. Which one applies is the
                // caller's choice, per request or per client. Straight off the start rather than off a clock
                // reading, so that time passing while the holder is built cannot extend it.
                requestDeadlineNanos = nettyResponseFuture.getStartNanos() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutInMs);
            }
            requestTimeoutTask = new RequestTimeoutTimerTask(nettyResponseFuture, requestSender, this, requestTimeoutInMs);
        } else {
            requestTimeoutTask = null;
        }
    }

    /**
     * Arms the request timeout, which the constructor deliberately leaves undone. The task holds this holder and
     * can run the moment it is armed, and on an event loop nothing rounds a short deadline up to the next tick,
     * so arming from the constructor let it run before its own fields were frozen, before the future had been
     * handed the holder, and on the pooled path before the channel had been attached to the future -- an expiry
     * that then had no channel to close.
     * <p>
     * Called by {@link org.asynchttpclient.netty.NettyResponseFuture#setTimeoutsHolder}, so that installing a
     * holder is what arms it and neither can be done without the other.
     * <p>
     * Only the first call arms. A second would overwrite the first handle, leaving an entry nobody can
     * cancel to sit in its scheduler until the full deadline, pinning the task, the future and the channel.
     */
    public void start() {
        if (!STARTED.compareAndSet(this, 0, 1)) {
            return;
        }
        if (requestTimeoutTask != null) {
            // Per attempt, the configured duration. An absolute deadline was anchored before this holder
            // existed, so there the remainder is the budget, floored at zero: a task armed at zero still runs,
            // and running is how the exchange gets failed.
            long untilDeadline = absoluteDeadline
                    ? Math.max(remainingBudget(requestTimeoutValue, nettyResponseFuture, nanoClock), 0L) : requestTimeoutValue;
            boolean early = readTimeoutValue != -1 && readTimeoutValue < untilDeadline;
            if (!absoluteDeadline && (early || useEventLoopTimeouts)) {
                // Anchored where the entry is armed, from the same duration. Whenever the read timeout is the
                // shorter, which is also when startReadTimeout reads it.
                requestDeadlineNanos = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutValue);
            }
            if (early) {
                // The read timeout starts once the request is written, and would then take an entry of its own
                // that the exchange almost always cancels unfired. Arming this one for when a read timeout
                // would be due instead lets it serve both: every entry costs a shared timer two queue
                // operations and a slot on its single thread, and an event loop a wakeup.
                readState = READ_PENDING;
                armRequestTimeout(requestTimeoutTask, readTimeoutValue, true);
            } else {
                armRequestTimeout(requestTimeoutTask, untilDeadline, false);
            }
        }
    }

    /**
     * How much of a deadline spanning the whole exchange is left, in milliseconds, negative once it has passed.
     * {@link Long#MAX_VALUE} when the timeout is per attempt or disabled, neither of which bounds an exchange as
     * a whole: an attempt is then given the configured timeout of its own however long the exchange has run.
     * <p>
     * Measured from the future's monotonic start rather than by comparing wall clocks across hops, so a clock
     * correction landing mid-chain cannot move the deadline. Static, and asked of the future rather than of a
     * holder, because a caller deciding whether a request is still worth sending has the future in hand before
     * any holder exists for the attempt it is about to make.
     *
     * @see org.asynchttpclient.AsyncHttpClientConfig#isUseAbsoluteRequestDeadline()
     */
    public static long remainingBudget(AsyncHttpClientConfig config, NettyResponseFuture<?> nettyResponseFuture) {
        return remainingBudget(config, nettyResponseFuture, System::nanoTime);
    }

    static long remainingBudget(AsyncHttpClientConfig config, NettyResponseFuture<?> nettyResponseFuture, LongSupplier nanoClock) {
        if (!nettyResponseFuture.isUseAbsoluteRequestDeadline()) {
            return Long.MAX_VALUE;
        }
        return remainingBudget(requestTimeout(config, nettyResponseFuture.getTargetRequest()), nettyResponseFuture, nanoClock);
    }

    private static long remainingBudget(long requestTimeoutInMs, NettyResponseFuture<?> nettyResponseFuture, LongSupplier nanoClock) {
        if (requestTimeoutInMs <= -1) {
            return Long.MAX_VALUE;
        }
        long spent = TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - nettyResponseFuture.getStartNanos());
        return requestTimeoutInMs - spent;
    }

    /**
     * The request timeout in force for {@code request}: its own, or the client's when it does not carry one.
     */
    private static long requestTimeout(AsyncHttpClientConfig config, Request request) {
        long requestTimeoutInMs = request.getRequestTimeout().toMillis();
        return requestTimeoutInMs == 0 ? config.getRequestTimeout().toMillis() : requestTimeoutInMs;
    }

    // The clock lastTouch is read against, which tests replace.
    long millisTime() {
        return millisClock.getAsLong();
    }

    boolean isRequestTimeoutDisabled() {
        return requestTimeoutTask == null;
    }

    long readTimeout() {
        return readTimeoutValue;
    }

    boolean isRequestTimeoutArmedEarly() {
        return requestTimeoutArmedEarly;
    }

    boolean isReadTimeoutCovered() {
        return readState == READ_COVERED;
    }

    /**
     * What is left until the request deadline in whole milliseconds, rounded up so an entry never runs ahead of
     * it, and floored at zero: a scheduler has no use for a negative delay, and the task has to run either way,
     * since running is what fails the exchange. Zero exactly when the deadline has passed. One clock reading
     * answers both whether it has and how long to re-arm for, so an entry that fires early reads it once.
     */
    long untilRequestDeadline() {
        long left = requestDeadlineNanos - nanoClock.getAsLong();
        if (left <= 0) {
            return 0L;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(left);
        return TimeUnit.MILLISECONDS.toNanos(millis) == left ? millis : millis + 1;
    }

    /**
     * Moves this exchange's timeouts onto {@code executor}, the loop of the channel it turned out to run on. The
     * connect path arms the request timeout before there is a channel -- deliberately, since it bounds address
     * resolution and the connect as well -- so there the loop is only known once the connection succeeds. A
     * no-op when the timeouts belong on the timer, or once the request timeout has fired or been cancelled.
     */
    public void rehomeOn(EventExecutor executor) {
        if (!useEventLoopTimeouts) {
            return;
        }
        eventExecutor = executor;
        RequestTimeoutTimerTask task = requestTimeoutTask;
        if (task == null || cancelled != 0 || task.isClaimed() || !task.cancelArmed()) {
            return;
        }
        armRequestTimeoutFrom(task, millisClock.getAsLong(), untilRequestDeadline(), false);
    }

    public void setResolvedRemoteAddress(InetSocketAddress address) {
        remoteAddress = address;
    }

    InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public void startReadTimeout() {
        if (readTimeoutValue != -1 && !coverReadTimeout()) {
            startReadTimeout(null);
        }
    }

    /**
     * Hands the read timeout to the request timeout's entry when {@link #start} armed that one early for it.
     * Loses to an entry that already fired without finding a read timeout to check, which hands the read
     * timeout back to an entry of its own.
     */
    private boolean coverReadTimeout() {
        int state = readState;
        return state == READ_COVERED || state == READ_PENDING && READ_STATE.compareAndSet(this, READ_PENDING, READ_COVERED);
    }

    /**
     * Re-arms the request timeout after its entry fired ahead of the request deadline and found no timeout due.
     *
     * @param untilDeadline what {@link #untilRequestDeadline} returned when the entry fired
     * @param suspended     whether response body reads are suspended, which holds off the read timeout for a
     *                      whole window however long ago the channel was last read from
     */
    void rearmRequestTimeout(RequestTimeoutTimerTask task, long now, long untilDeadline, boolean suspended) {
        // A read timeout that has not started by now may not start for a long time -- a slow connect or upload
        // -- and checking for it every window would wake the scheduler for nothing, so it gets its own entry
        // when it does start. Read first: on most fires the read timeout is long covered, and a failed CAS
        // costs as much as one that succeeds.
        if (readState == READ_PENDING) {
            READ_STATE.compareAndSet(this, READ_PENDING, READ_SEPARATE);
        }
        armRequestTimeoutFrom(task, now, untilDeadline, suspended);
    }

    private void armRequestTimeoutFrom(RequestTimeoutTimerTask task, long now, long untilDeadline, boolean suspended) {
        long untilRead = Long.MAX_VALUE;
        int state = readState;
        if (state == READ_PENDING) {
            untilRead = readTimeoutValue;
        } else if (state == READ_COVERED) {
            // Capped at a whole window, so a wall clock stepping back cannot put the next check out of reach.
            untilRead = suspended ? readTimeoutValue
                    : Math.min(Math.max(nettyResponseFuture.getLastTouch() + readTimeoutValue - now, 0L), readTimeoutValue);
        }
        if (untilRead < untilDeadline) {
            armRequestTimeout(task, untilRead, true);
        } else {
            armRequestTimeout(task, untilDeadline, false);
        }
    }

    private void armRequestTimeout(RequestTimeoutTimerTask task, long delay, boolean early) {
        // Written before arming: on an event loop the entry can run before arm returns. Only when it changes,
        // since an entry that keeps re-arming early would otherwise pay a volatile write on every fire.
        if (requestTimeoutArmedEarly != early) {
            requestTimeoutArmedEarly = early;
        }
        arm(task, delay);
    }

    void startReadTimeout(@Nullable ReadTimeoutTimerTask task) {
        startReadTimeout(task, readTimeoutValue);
    }

    void startReadTimeout(@Nullable ReadTimeoutTimerTask task, long delay) {
        if (requestTimeoutTask == null || !requestTimeoutTask.isClaimed() && dueBeforeRequestDeadline(delay)) {
            // only schedule a new readTimeout if the requestTimeout doesn't happen first
            if (task == null) {
                // first call triggered from outside (else is read timeout is re-scheduling itself)
                task = new ReadTimeoutTimerTask(nettyResponseFuture, requestSender, this, readTimeoutValue);
            }
            readTimeoutTask = task;
            arm(task, delay);

        } else if (task != null) {
            // read timeout couldn't re-scheduling itself, clean up
            task.clean();
        }
    }

    /**
     * Whether a read timeout armed now for {@code delay} would be due before the request deadline, measured on
     * nanoClock like every other check of that deadline. A delay no shorter than the whole request timeout never
     * is, and is answered without a clock read: that is the default path, on which the deadline is not on
     * nanoClock. Below that it always is: the constructor puts an absolute deadline there, and {@link #start} a
     * per-attempt one whenever the read timeout is the shorter, and a delay shorter than the read timeout only
     * comes from a read timeout that has already passed this check in full. That takes start() to have run,
     * which it has: a read timeout is started through the future, and a holder is started as the future takes
     * it.
     */
    private boolean dueBeforeRequestDeadline(long delay) {
        return delay < requestTimeoutValue && delay < untilRequestDeadline();
    }

    public void cancel() {
        retire();
    }

    /**
     * {@link #cancel}, telling whether this call is the one that cancelled, so that a caller acting on the
     * exchange's behalf can tell a holder still in charge of it from one a completion, a redirect, a retry or
     * an auth replay has already retired.
     */
    boolean retire() {
        if (CANCELLED.compareAndSet(this, 0, 1)) {
            release(requestTimeoutTask);
            release(readTimeoutTask);
            return true;
        }
        return false;
    }

    private static void release(@Nullable TimeoutTimerTask task) {
        if (task != null) {
            task.cancelArmed();
            task.clean();
        }
    }

    /**
     * Arms {@code task} to run after {@code delay} milliseconds, recording the scheduled entry on the task so it
     * can cancel itself later. Leaves it unarmed when the client is shutting down, in which case there is no
     * timeout to deliver anyway.
     *
     * @param <T> a task that both schedulers accept: the timer takes a {@link io.netty.util.TimerTask} and an
     *            event loop a {@link Runnable}, and only the concrete subclasses are both
     */
    private <T extends TimeoutTimerTask & Runnable> void arm(T task, long delay) {
        // requestSender or nettyTimer might be null in unit tests or in some edge
        // cases where a channel's remote address wasn't available. In such cases
        // avoid scheduling any timeouts rather than throwing a NPE.
        if (requestSender == null || requestSender.isClosed()) {
            return;
        }
        int arming = task.beginArming();
        EventExecutor executor = eventExecutor;
        if (executor != null && !executor.isShuttingDown()) {
            ScheduledFuture<?> handle = null;
            try {
                handle = executor.schedule(task, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // The loop began shutting down between the check above and here. Losing the timeout entirely
                // would leave the exchange with nothing to end it, so fall through to the timer, which the
                // client keeps running until it is itself closed.
                LOGGER.debug("Event loop rejected a timeout, falling back to the timer", e);
            }
            // Outside the try: only the schedule above may fall back to the timer. Anything thrown while
            // recording or unwinding the entry belongs to an exchange that is already armed.
            if (handle != null) {
                task.armedOn(handle, arming);
                cancelIfRaced(task);
                return;
            }
        }
        if (nettyTimer == null) {
            return;
        }
        task.armedOn(nettyTimer.newTimeout(task, delay, TimeUnit.MILLISECONDS), arming);
        cancelIfRaced(task);
    }

    /**
     * Takes a just-armed entry back out of its scheduler when the exchange finished while it was being armed.
     * {@link #cancel} is one shot, so a handle recorded after it ran is one nobody would ever cancel: the entry
     * would sit in the scheduler until the full deadline, waking a loop for a request that is long done.
     */
    private void cancelIfRaced(TimeoutTimerTask task) {
        if (cancelled != 0) {
            release(task);
        }
    }
}
