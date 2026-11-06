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

import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.ScheduledFuture;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A timeout that can be armed either on a {@link io.netty.util.Timer} or on an event loop; which one an
 * exchange uses is {@link org.asynchttpclient.AsyncHttpClientConfig#isUseEventLoopTimeouts()}. An event loop
 * schedules {@link Runnable}s, so each subclass implements that as a second entry point into the same body.
 * This class stays a {@link TimerTask} alone: {@code Runnable#run} declares no checked exception, so a
 * {@code run()} here would have to catch what {@link TimerTask#run(Timeout)} declares and no subclass throws.
 */
public abstract class TimeoutTimerTask implements TimerTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutTimerTask.class);
    private static final AtomicReferenceFieldUpdater<TimeoutTimerTask, Object> HANDLE =
            AtomicReferenceFieldUpdater.newUpdater(TimeoutTimerTask.class, Object.class, "handle");

    protected final AtomicBoolean done = new AtomicBoolean();
    protected final NettyRequestSender requestSender;
    final TimeoutsHolder timeoutsHolder;
    volatile NettyResponseFuture<?> nettyResponseFuture;
    // The scheduled entry this task is armed on: a timer's Timeout or an event loop's ScheduledFuture, which
    // only the typed armedOn overloads below can store. One field, so that recording an entry and taking it
    // back are each a single atomic step: a task that re-arms itself can record an entry from the thread it
    // fires on while the thread that armed it is still recording the previous one. Held here rather than in a
    // wrapper so arming allocates nothing beyond what the scheduler needs.
    private volatile @Nullable Object handle;

    TimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender, TimeoutsHolder timeoutsHolder) {
        this.nettyResponseFuture = nettyResponseFuture;
        this.requestSender = requestSender;
        this.timeoutsHolder = timeoutsHolder;
    }

    void armedOn(Timeout handle) {
        record(handle);
    }

    void armedOn(ScheduledFuture<?> handle) {
        record(handle);
    }

    /**
     * Records {@code entry} in place of whatever entry was recorded before, unless that one can still run and
     * {@code entry} already has. That is the entry of an arming that lost the race against its own firing:
     * its run re-armed the task and recorded the newer entry first, and overwriting that one would leave it in
     * its scheduler with nobody to cancel it, holding the future until a deadline nobody is waiting for.
     */
    private void record(Object entry) {
        for (;;) {
            Object current = handle;
            if (current != null && canStillRun(current) && hasRun(entry)) {
                return;
            }
            if (HANDLE.compareAndSet(this, current, entry)) {
                return;
            }
        }
    }

    private static boolean canStillRun(Object entry) {
        if (entry instanceof Timeout) {
            Timeout timeout = (Timeout) entry;
            return !timeout.isExpired() && !timeout.isCancelled();
        }
        return !((ScheduledFuture<?>) entry).isDone();
    }

    // An event loop marks an entry done only once it has run, so one that is running counts by being due.
    private static boolean hasRun(Object entry) {
        if (entry instanceof Timeout) {
            return ((Timeout) entry).isExpired();
        }
        ScheduledFuture<?> scheduled = (ScheduledFuture<?>) entry;
        return scheduled.isDone() || scheduled.getDelay(TimeUnit.NANOSECONDS) <= 0;
    }

    /**
     * Cancels the scheduled entry this task was armed on, if any. Never interrupts: on the event-loop path the
     * task may be running on the very thread this is called from, and nothing in it answers interruption.
     *
     * @return whether an entry was taken back out of its scheduler before it could run
     */
    boolean cancelArmed() {
        // Taken in one step: a read followed by a separate clear could clear an entry the task recorded in
        // between from its own firing, and that one would then never be cancelled.
        Object entry = HANDLE.getAndSet(this, null);
        if (entry instanceof Timeout) {
            return ((Timeout) entry).cancel();
        }
        if (entry != null) {
            ScheduledFuture<?> scheduled = (ScheduledFuture<?>) entry;
            try {
                return scheduled.cancel(false);
            } catch (RejectedExecutionException e) {
                // Cancelling from off the loop enqueues the removal, which a loop that is already shutting down
                // rejects. The entry dies with the loop either way, and this runs under
                // ListenableFuture#cancel, which has never thrown for a client that is closing.
                LOGGER.debug("Event loop rejected a timeout cancellation", e);
                return false;
            }
        }
        return false;
    }

    /**
     * Whether this task has been claimed, either by firing or by {@link #clean()}. Stands in for the
     * scheduler's own already-expired flag, which the two schedulers spell differently, and is if anything the
     * more precise of the two: it flips when {@code run} is entered rather than when the entry is marked.
     */
    boolean isClaimed() {
        return done.get();
    }

    void expire(String message, long time) {
        LOGGER.debug("{} for {} after {} ms", message, nettyResponseFuture, time);
        requestSender.abort(nettyResponseFuture.channel(), nettyResponseFuture, new TimeoutException(message));
    }

    /**
     * When the timeout is cancelled, it could still be referenced for quite some time in the Timer. Holding a reference to the future might mean holding a reference to the
     * channel, and heavy objects such as SslEngines
     */
    public void clean() {
        if (done.compareAndSet(false, true)) {
            nettyResponseFuture = null;
        }
    }

    void appendRemoteAddress(StringBuilder sb) {
        InetSocketAddress remoteAddress = timeoutsHolder.remoteAddress();

        // Guard against null remoteAddress which can happen when the TimeoutsHolder
        // was created without an original remote address (for example when using a
        // pooled channel whose remoteAddress() returned null). In that case fall
        // back to the URI host/port from the request to avoid a NPE and provide
        // a useful diagnostic.
        if (remoteAddress == null) {
            if (nettyResponseFuture != null && nettyResponseFuture.getTargetRequest() != null) {
                try {
                    String host = nettyResponseFuture.getTargetRequest().getUri().getHost();
                    int port = nettyResponseFuture.getTargetRequest().getUri().getExplicitPort();
                    sb.append(host == null ? "unknown" : host);
                    sb.append(':').append(port);
                } catch (Exception ignored) {
                    sb.append("unknown:0");
                }
            } else {
                sb.append("unknown:0");
            }
            return;
        }

        sb.append(remoteAddress.getHostString());
        if (!remoteAddress.isUnresolved()) {
            sb.append('/').append(remoteAddress.getAddress().getHostAddress());
        }
        sb.append(':').append(remoteAddress.getPort());
    }
}
