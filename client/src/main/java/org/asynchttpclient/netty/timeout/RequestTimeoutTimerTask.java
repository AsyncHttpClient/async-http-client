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
import org.asynchttpclient.netty.NettyResponseBodyControl;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.util.StringBuilderPool;

import static org.asynchttpclient.util.DateUtils.unpreciseMillisTime;

public class RequestTimeoutTimerTask extends TimeoutTimerTask implements Runnable {

    private final long requestTimeout;

    RequestTimeoutTimerTask(NettyResponseFuture<?> nettyResponseFuture,
                            NettyRequestSender requestSender,
                            TimeoutsHolder timeoutsHolder,
                            long requestTimeout) {
        super(nettyResponseFuture, requestSender, timeoutsHolder);
        this.requestTimeout = requestTimeout;
    }

    /**
     * The event-loop entry point. Nothing below reads the {@link Timeout}, which is the timer's own handle on
     * this task and something an event loop has no equivalent of, so both entry points share one body.
     */
    @Override
    public void run() {
        run(null);
    }

    @Override
    public void run(Timeout timeout) {
        if (done.getAndSet(true) || requestSender.isClosed()) {
            return;
        }

        if (timeoutsHolder.isRequestTimeoutArmedEarly() && checkReadTimeout()) {
            return;
        }

        // in any case, cancel possible readTimeout sibling
        timeoutsHolder.cancel();

        if (nettyResponseFuture.isDone()) {
            return;
        }

        StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder().append("Request timeout to ");
        appendRemoteAddress(sb);
        String message = sb.append(" after ").append(requestTimeout).append(" ms").toString();
        long age = unpreciseMillisTime() - nettyResponseFuture.getStart();
        expire(message, age);
    }

    /**
     * Runs when this entry was armed ahead of the request deadline, for when the read timeout could be due, so
     * that one entry serves both. Fails the exchange with a read timeout when it is due, and otherwise arms
     * the entry again for whichever of the two comes next.
     *
     * @return {@code false} when the request deadline has passed as well, which leaves the request timeout to
     *         be delivered as though the entry had been armed for it
     */
    private boolean checkReadTimeout() {
        if (nettyResponseFuture.isDone()) {
            timeoutsHolder.cancel();
            return true;
        }

        if (timeoutsHolder.isRequestDeadlinePassed()) {
            return false;
        }
        long now = timeoutsHolder.millisTime();
        boolean suspended = NettyResponseBodyControl.isSuspended(nettyResponseFuture);
        if (timeoutsHolder.isReadTimeoutCovered() && !suspended) {
            long readTimeout = timeoutsHolder.readTimeout();
            long sinceLastTouch = now - nettyResponseFuture.getLastTouch();
            if (sinceLastTouch >= readTimeout) {
                StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder().append("Read timeout to ");
                appendRemoteAddress(sb);
                String message = sb.append(" after ").append(readTimeout).append(" ms").toString();
                expire(message, sinceLastTouch);
                timeoutsHolder.cancel();
                return true;
            }
        }

        // Read before release: a concurrent cancel cleans the field once the task is unclaimed.
        NettyResponseFuture<?> future = nettyResponseFuture;
        // Released before re-arming: the holder may hand the read timeout an entry of its own, which is only
        // armed while this task is unclaimed.
        done.set(false);
        try {
            timeoutsHolder.rearmRequestTimeout(this, now, suspended);
        } catch (RuntimeException e) {
            // A timer that rejects the entry, being full or stopped, would leave the exchange with no deadline
            // at all, so it is failed instead of left to hang.
            timeoutsHolder.cancel();
            requestSender.abort(future.channel(), future, e);
        }
        return true;
    }
}
