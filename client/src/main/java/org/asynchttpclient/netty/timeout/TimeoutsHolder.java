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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

/**
 * The request and read timeouts of one exchange, armed either on the client's {@link Timer} or on the event
 * loop of the channel the exchange runs on. What the two differ in, and why the choice is the caller's, is
 * {@link AsyncHttpClientConfig#isUseEventLoopTimeouts()}.
 */
public class TimeoutsHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutsHolder.class);

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Timer nettyTimer;
    private volatile @Nullable EventExecutor eventExecutor;
    private final NettyRequestSender requestSender;
    private final long requestTimeoutMillisTime;
    private final long requestTimeoutValue;
    private final long readTimeoutValue;
    private final boolean useEventLoopTimeouts;
    private final @Nullable RequestTimeoutTimerTask requestTimeoutTask;
    private volatile @Nullable ReadTimeoutTimerTask readTimeoutTask;
    private final NettyResponseFuture<?> nettyResponseFuture;
    private volatile InetSocketAddress remoteAddress;

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
        this.nettyTimer = nettyTimer;
        this.eventExecutor = eventExecutor;
        this.nettyResponseFuture = nettyResponseFuture;
        this.requestSender = requestSender;
        useEventLoopTimeouts = config.isUseEventLoopTimeouts();
        remoteAddress = originalRemoteAddress;

        final Request targetRequest = nettyResponseFuture.getTargetRequest();

        final long readTimeoutInMs = targetRequest.getReadTimeout().toMillis();
        readTimeoutValue = readTimeoutInMs == 0 ? config.getReadTimeout().toMillis() : readTimeoutInMs;

        long requestTimeoutInMs = targetRequest.getRequestTimeout().toMillis();
        if (requestTimeoutInMs == 0) {
            requestTimeoutInMs = config.getRequestTimeout().toMillis();
        }

        requestTimeoutValue = requestTimeoutInMs;
        if (requestTimeoutInMs > -1) {
            requestTimeoutMillisTime = unpreciseMillisTime() + requestTimeoutInMs;
            requestTimeoutTask = new RequestTimeoutTimerTask(nettyResponseFuture, requestSender, this, requestTimeoutInMs);
        } else {
            requestTimeoutMillisTime = -1L;
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
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (requestTimeoutTask != null) {
            // The configured duration rather than the remaining time: this runs within microseconds of the
            // constructor, and reading the clock again would only expose the deadline to a step between the two.
            arm(requestTimeoutTask, requestTimeoutValue);
        }
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
        if (task == null || cancelled.get() || task.isClaimed() || !task.cancelArmed()) {
            return;
        }
        arm(task, remainingRequestTimeout());
    }

    public void setResolvedRemoteAddress(InetSocketAddress address) {
        remoteAddress = address;
    }

    InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public void startReadTimeout() {
        if (readTimeoutValue != -1) {
            startReadTimeout(null);
        }
    }

    void startReadTimeout(@Nullable ReadTimeoutTimerTask task) {
        if (requestTimeoutTask == null
                || !requestTimeoutTask.isClaimed() && readTimeoutValue < requestTimeoutMillisTime - unpreciseMillisTime()) {
            // only schedule a new readTimeout if the requestTimeout doesn't happen first
            if (task == null) {
                // first call triggered from outside (else is read timeout is re-scheduling itself)
                task = new ReadTimeoutTimerTask(nettyResponseFuture, requestSender, this, readTimeoutValue);
            }
            readTimeoutTask = task;
            arm(task, readTimeoutValue);

        } else if (task != null) {
            // read timeout couldn't re-scheduling itself, clean up
            task.clean();
        }
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            release(requestTimeoutTask);
            release(readTimeoutTask);
        }
    }

    private static void release(@Nullable TimeoutTimerTask task) {
        if (task != null) {
            task.cancelArmed();
            task.clean();
        }
    }

    private long remainingRequestTimeout() {
        // A deadline already behind us is armed at zero rather than negative, so the task still runs and still
        // cancels its read-timeout sibling, which is bookkeeping only it does.
        return Math.max(requestTimeoutMillisTime - unpreciseMillisTime(), 0L);
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
                task.armedOn(handle);
                cancelIfRaced(task);
                return;
            }
        }
        if (nettyTimer == null) {
            return;
        }
        task.armedOn(nettyTimer.newTimeout(task, delay, TimeUnit.MILLISECONDS));
        cancelIfRaced(task);
    }

    /**
     * Takes a just-armed entry back out of its scheduler when the exchange finished while it was being armed.
     * {@link #cancel} is one shot, so a handle recorded after it ran is one nobody would ever cancel: the entry
     * would sit in the scheduler until the full deadline, waking a loop for a request that is long done.
     */
    private void cancelIfRaced(TimeoutTimerTask task) {
        if (cancelled.get()) {
            release(task);
        }
    }
}
