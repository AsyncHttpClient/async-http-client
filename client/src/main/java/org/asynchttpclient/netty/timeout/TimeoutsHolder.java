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
 * The request and read timeouts of one exchange.
 * <p>
 * Timeouts are armed either on the client's {@link Timer} or, when an {@link EventExecutor} is supplied, on
 * that event loop. The two differ in more than which thread runs the task. A wheel fires on the first tick at
 * or after the deadline, so a deadline near or below the tick duration is rounded up, and one thread carries
 * every expiry for the whole client. An event loop schedules by deadline and derives its select timeout from
 * the nearest one, so nothing is rounded, and the loops share the load. See
 * {@link AsyncHttpClientConfig#isUseEventLoopTimeouts()} for what that costs.
 */
public class TimeoutsHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutsHolder.class);

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Timer nettyTimer;
    private final @Nullable EventExecutor eventExecutor;
    private final NettyRequestSender requestSender;
    private final long requestTimeoutMillisTime;
    private final long readTimeoutValue;
    private final @Nullable RequestTimeoutTimerTask requestTimeoutTask;
    // Whether the request timeout was actually armed. Distinct from requestTimeoutTask being non-null: the
    // task exists but is left unarmed when there is nothing to arm it on, and the read timeout is then free to
    // run to its own deadline rather than assuming a request timeout will outrun it.
    private final boolean requestTimeoutArmed;
    private volatile @Nullable ReadTimeoutTimerTask readTimeoutTask;
    private final NettyResponseFuture<?> nettyResponseFuture;
    private volatile InetSocketAddress remoteAddress;

    public TimeoutsHolder(Timer nettyTimer, NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender,
                          AsyncHttpClientConfig config, InetSocketAddress originalRemoteAddress) {
        this(nettyTimer, null, nettyResponseFuture, requestSender, config, originalRemoteAddress);
    }

    /**
     * @param eventExecutor the event loop to arm the timeouts on, or {@code null} to arm them on
     *                      {@code nettyTimer}. Pass the loop that owns the exchange's channel when it is known,
     *                      so the timeout fires on the thread that will have to close it.
     */
    public TimeoutsHolder(Timer nettyTimer, @Nullable EventExecutor eventExecutor, NettyResponseFuture<?> nettyResponseFuture,
                          NettyRequestSender requestSender, AsyncHttpClientConfig config, InetSocketAddress originalRemoteAddress) {
        this.nettyTimer = nettyTimer;
        this.eventExecutor = eventExecutor;
        this.nettyResponseFuture = nettyResponseFuture;
        this.requestSender = requestSender;
        remoteAddress = originalRemoteAddress;

        final Request targetRequest = nettyResponseFuture.getTargetRequest();

        final long readTimeoutInMs = targetRequest.getReadTimeout().toMillis();
        readTimeoutValue = readTimeoutInMs == 0 ? config.getReadTimeout().toMillis() : readTimeoutInMs;

        long requestTimeoutInMs = targetRequest.getRequestTimeout().toMillis();
        if (requestTimeoutInMs == 0) {
            requestTimeoutInMs = config.getRequestTimeout().toMillis();
        }

        if (requestTimeoutInMs > -1) {
            requestTimeoutMillisTime = unpreciseMillisTime() + requestTimeoutInMs;
            requestTimeoutTask = new RequestTimeoutTimerTask(nettyResponseFuture, requestSender, this, requestTimeoutInMs);
            requestTimeoutArmed = arm(requestTimeoutTask, requestTimeoutInMs);
        } else {
            requestTimeoutMillisTime = -1L;
            requestTimeoutTask = null;
            requestTimeoutArmed = false;
        }
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
        if (!requestTimeoutArmed
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

    /**
     * Arms {@code task} to run after {@code delay} milliseconds, recording the scheduled entry on the task so it
     * can cancel itself later.
     *
     * @return whether the task was armed. It is not when the client is shutting down, in which case there is no
     *         timeout to deliver anyway
     */
    private boolean arm(TimeoutTimerTask task, long delay) {
        // requestSender or nettyTimer might be null in unit tests or in some edge
        // cases where a channel's remote address wasn't available. In such cases
        // avoid scheduling any timeouts rather than throwing a NPE.
        if (requestSender == null || requestSender.isClosed()) {
            return false;
        }
        if (eventExecutor != null && !eventExecutor.isShuttingDown()) {
            try {
                task.armedOn(eventExecutor.schedule(task, delay, TimeUnit.MILLISECONDS));
                return true;
            } catch (RejectedExecutionException e) {
                // The loop began shutting down between the check above and here. Losing the timeout entirely
                // would leave the exchange with nothing to end it, so fall through to the timer, which the
                // client keeps running until it is itself closed.
                LOGGER.debug("Event loop rejected a timeout, falling back to the timer", e);
            }
        }
        if (nettyTimer == null) {
            return false;
        }
        task.armedOn(nettyTimer.newTimeout(task, delay, TimeUnit.MILLISECONDS));
        return true;
    }
}
