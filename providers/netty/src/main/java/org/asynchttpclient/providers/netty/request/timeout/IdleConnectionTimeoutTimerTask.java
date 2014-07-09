/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.request.timeout;

import static org.asynchttpclient.util.DateUtils.millisTime;

import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;

import io.netty.util.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

public class IdleConnectionTimeoutTimerTask extends TimeoutTimerTask {

    private final long idleConnectionTimeout;
    private final long requestTimeoutInstant;

    public IdleConnectionTimeoutTimerTask(//
            NettyResponseFuture<?> nettyResponseFuture,//
            Channels channels,//
            TimeoutsHolder timeoutsHolder,//
            AtomicBoolean clientClosed,//
            long requestTimeout,//
            long idleConnectionTimeout) {
        super(nettyResponseFuture, channels, timeoutsHolder, clientClosed);
        this.idleConnectionTimeout = idleConnectionTimeout;
        requestTimeoutInstant = requestTimeout >= 0 ? nettyResponseFuture.getStart() + requestTimeout : Long.MAX_VALUE;
    }

    @Override
    public void run(Timeout timeout) throws Exception {
        if (clientClosed.get()) {
            timeoutsHolder.cancel();
            return;
        }

        if (!nettyResponseFuture.isDone() && !nettyResponseFuture.isCancelled()) {

            long now = millisTime();

            long currentIdleConnectionTimeoutInstant = idleConnectionTimeout + nettyResponseFuture.getLastTouch();
            long durationBeforeCurrentIdleConnectionTimeout = currentIdleConnectionTimeoutInstant - now;

            if (durationBeforeCurrentIdleConnectionTimeout <= 0L) {
                // idleConnectionTimeout reached
                String message = "Idle connection timeout to " + nettyResponseFuture.getChannelRemoteAddress() + " of " + idleConnectionTimeout + " ms";
                long durationSinceLastTouch = now - nettyResponseFuture.getLastTouch();
                expire(message, durationSinceLastTouch);
                nettyResponseFuture.setIdleConnectionTimeoutReached();

            } else if (currentIdleConnectionTimeoutInstant < requestTimeoutInstant) {
                // reschedule
                timeoutsHolder.idleConnectionTimeout = channels.newTimeoutInMs(this, durationBeforeCurrentIdleConnectionTimeout);

            } else {
                // otherwise, no need to reschedule: requestTimeout will happen sooner
                timeoutsHolder.idleConnectionTimeout = null;
            }

        } else {
            timeoutsHolder.cancel();
        }
    }
}
