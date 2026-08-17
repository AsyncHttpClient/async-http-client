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
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Also a {@link Runnable} so the same task can be armed either on a {@link io.netty.util.Timer} or on an
 * event loop, which schedules {@code Runnable}s. Neither subclass reads the {@link Timeout} handed to
 * {@link TimerTask#run(Timeout)}, so the two entry points are interchangeable.
 */
public abstract class TimeoutTimerTask implements TimerTask, Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutTimerTask.class);

    protected final AtomicBoolean done = new AtomicBoolean();
    protected final NettyRequestSender requestSender;
    final TimeoutsHolder timeoutsHolder;
    volatile NettyResponseFuture<?> nettyResponseFuture;
    /**
     * The scheduled entry this task is armed on: an {@link Timeout} from a {@link io.netty.util.Timer}, or a
     * {@link Future} from an event loop. Held here rather than in a wrapper so arming a timeout allocates
     * nothing beyond what the scheduler itself needs.
     */
    private volatile @Nullable Object armed;

    TimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture, NettyRequestSender requestSender, TimeoutsHolder timeoutsHolder) {
        this.nettyResponseFuture = nettyResponseFuture;
        this.requestSender = requestSender;
        this.timeoutsHolder = timeoutsHolder;
    }

    @Override
    public void run() {
        try {
            run(null);
        } catch (Exception e) {
            // TimerTask#run is declared to throw, and on this entry point the caller is an event loop, where an
            // escaping exception would be swallowed into Netty's own handling. Neither task here throws, so this
            // only matters for a subclass outside the library.
            LOGGER.warn("Timeout task failed", e);
        }
    }

    void armedOn(Object handle) {
        armed = handle;
    }

    /**
     * Cancels the scheduled entry this task was armed on, if any. Never interrupts: on the event-loop path the
     * task may be running on the very thread this is called from, and nothing in it answers interruption.
     */
    void cancelArmed() {
        Object handle = armed;
        armed = null;
        if (handle instanceof Timeout) {
            ((Timeout) handle).cancel();
        } else if (handle instanceof Future) {
            ((Future<?>) handle).cancel(false);
        }
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
